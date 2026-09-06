import os
import sqlite3
import json
from datetime import datetime, timedelta, timezone
try:
    from zoneinfo import ZoneInfo
    SERVER_TZ = ZoneInfo(os.environ.get("ERSALYAR_TZ", "Asia/Tehran"))
except Exception:
    SERVER_TZ = timezone.utc
from functools import wraps
from collections import defaultdict, deque
from threading import Lock

import jwt
from flask import Flask, g, jsonify, request, send_from_directory
from werkzeug.security import check_password_hash, generate_password_hash

APP_SECRET = os.environ.get("ERSALYAR_SECRET", "")
DB_PATH = os.environ.get("ERSALYAR_DB", "ersalyar.db")
MAX_DEVICES_PER_USER = max(1, int(os.environ.get("ERSALYAR_MAX_DEVICES_PER_USER", "5")))

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = int(os.environ.get("ERSALYAR_MAX_BODY_BYTES", "65536"))

# Small dependency-free rate limiter. For production behind multiple workers,
# move this to Redis so limits are shared across instances.
_rate_lock = Lock()
_rate_hits = defaultdict(deque)
RATE_RULES = {
    "login": (5, 60),
    "register": (3, 600),
    "support": (12, 60),
    "general": (120, 60),
}

def client_key():
    # Do not trust X-Forwarded-For unless the reverse proxy is explicitly configured.
    return request.remote_addr or "unknown"

def rate_limited(bucket="general"):
    limit, window = RATE_RULES[bucket]
    now = datetime.now(timezone.utc).timestamp()
    key = (bucket, client_key())
    with _rate_lock:
        q = _rate_hits[key]
        cutoff = now - window
        while q and q[0] <= cutoff:
            q.popleft()
        if len(q) >= limit:
            retry = max(1, int(window - (now - q[0])))
            return retry
        q.append(now)
    return 0

def security_headers(response):
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Cache-Control"] = "no-store" if request.path.startswith("/api/") else "no-cache"
    if request.is_secure:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return response

app.after_request(security_headers)

if not APP_SECRET or APP_SECRET == "CHANGE_THIS_IN_PRODUCTION":
    # Refuse to start with a known/default JWT secret. This is intentional.
    raise RuntimeError("ERSALYAR_SECRET must be a strong production secret")

def db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
    return g.db

@app.teardown_appcontext
def close_db(exc):
    conn = g.pop("db", None)
    if conn:
        conn.close()

def init_db():
    conn = db()
    conn.executescript(open(os.path.join(os.path.dirname(__file__), "schema.sql"), encoding="utf-8").read())
    # Lightweight migration for databases created by older versions.
    log_cols = {r[1] for r in conn.execute("PRAGMA table_info(send_logs)").fetchall()}
    if "content" not in log_cols:
        conn.execute("ALTER TABLE send_logs ADD COLUMN content TEXT NOT NULL DEFAULT ''")
    if "local_id" not in log_cols:
        conn.execute("ALTER TABLE send_logs ADD COLUMN local_id TEXT")
    # v2.8 discount-code/payment migrations for existing databases.
    payment_table_cols = {r[1] for r in conn.execute("PRAGMA table_info(payments)").fetchall()}
    if "coupon_code" not in payment_table_cols: conn.execute("ALTER TABLE payments ADD COLUMN coupon_code TEXT")
    if "original_amount" not in payment_table_cols: conn.execute("ALTER TABLE payments ADD COLUMN original_amount INTEGER NOT NULL DEFAULT 0")
    if "discount_amount" not in payment_table_cols: conn.execute("ALTER TABLE payments ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0")
    conn.execute("CREATE TABLE IF NOT EXISTS discount_codes (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, percent INTEGER NOT NULL, max_uses INTEGER NOT NULL DEFAULT 0, used_count INTEGER NOT NULL DEFAULT 0, expires_at TEXT, active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    payment_cols = {r[1] for r in conn.execute("PRAGMA table_info(payment_settings)").fetchall()}
    if "support_phone" not in payment_cols:
        conn.execute("ALTER TABLE payment_settings ADD COLUMN support_phone TEXT NOT NULL DEFAULT '09372544666'")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_send_logs_user_local_id ON send_logs(user_id,local_id) WHERE local_id IS NOT NULL")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(messages)").fetchall()}
    if "batch_id" not in cols:
        conn.execute("ALTER TABLE messages ADD COLUMN batch_id INTEGER")
    if "batch_order" not in cols:
        conn.execute("ALTER TABLE messages ADD COLUMN batch_order INTEGER DEFAULT 0")
    conn.commit()

def install_id_from_request():
    value = (request.headers.get("X-Install-ID") or "").strip()
    return value if 16 <= len(value) <= 128 else None

def register_device(conn, user_id, install_id, app_version=""):
    if not install_id:
        return False, "شناسه نصب برنامه ارسال نشده است."
    now = datetime.now(timezone.utc).isoformat()
    existing = conn.execute("SELECT id FROM user_devices WHERE user_id=? AND install_id=?", (user_id, install_id)).fetchone()
    if existing:
        conn.execute("UPDATE user_devices SET app_version=?,last_seen_at=? WHERE id=?", (app_version[:32], now, existing["id"]))
        conn.commit(); return True, None
    count = conn.execute("SELECT COUNT(*) AS c FROM user_devices WHERE user_id=?", (user_id,)).fetchone()["c"]
    if count >= MAX_DEVICES_PER_USER:
        return False, "تعداد دستگاه‌های فعال این حساب به سقف مجاز رسیده است."
    conn.execute("INSERT INTO user_devices(user_id,install_id,app_version,created_at,last_seen_at) VALUES(?,?,?,?,?)", (user_id, install_id, app_version[:32], now, now))
    conn.commit(); return True, None

def token_for(user_id, role):
    now = datetime.now(timezone.utc)
    payload = {
        "sub": user_id,
        "role": role,
        "ver": 1,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(days=7)).timestamp()),
    }
    return jwt.encode(payload, APP_SECRET, algorithm="HS256")

def auth_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        retry = rate_limited("general")
        if retry:
            return jsonify(error="تعداد درخواست‌ها زیاد است؛ کمی بعد دوباره تلاش کنید", retry_after=retry), 429
        raw = request.headers.get("Authorization", "")
        if not raw.startswith("Bearer "):
            return jsonify(error="احراز هویت لازم است"), 401
        try:
            payload = jwt.decode(raw[7:], APP_SECRET, algorithms=["HS256"])
        except jwt.PyJWTError:
            return jsonify(error="توکن نامعتبر یا منقضی شده است"), 401
        try:
            g.user_id = int(payload["sub"])
        except (KeyError, TypeError, ValueError):
            return jsonify(error="توکن نامعتبر است"), 401
        g.role = payload.get("role", "user")
        user = db().execute("SELECT status,role FROM users WHERE id=?", (g.user_id,)).fetchone()
        if not user or user["status"] != "active":
            return jsonify(error="حساب کاربری غیرفعال یا حذف شده است"), 403
        # Role is always read from the database; never trust a stale role claim.
        g.role = user["role"]
        install_id = install_id_from_request()
        if not install_id:
            return jsonify(error="شناسه نصب برنامه معتبر نیست"), 401
        device = db().execute("SELECT id FROM user_devices WHERE user_id=? AND install_id=?", (g.user_id, install_id)).fetchone()
        if not device:
            return jsonify(error="این نصب برنامه برای حساب شما ثبت نشده است؛ دوباره وارد شوید."), 401
        db().execute("UPDATE user_devices SET last_seen_at=? WHERE id=?", (datetime.now(timezone.utc).isoformat(), device["id"]))
        db().commit()
        return fn(*args, **kwargs)
    return wrapper


@app.get("/admin")
def admin_page():
    return send_from_directory(os.path.dirname(__file__), "admin.html")

@app.get("/api/health")
def health():
    return jsonify(ok=True, service="ersalyar-api")

@app.post("/api/auth/register")
def register():
    retry = rate_limited("register")
    if retry:
        return jsonify(error="تعداد درخواست‌ها زیاد است؛ کمی بعد دوباره تلاش کنید", retry_after=retry), 429
    data = request.get_json(silent=True) or {}
    mobile = str(data.get("mobile", "")).strip()
    password = str(data.get("password", ""))
    name = str(data.get("name", "")).strip()
    if not mobile or len(mobile) > 32 or len(password) < 8 or len(password) > 256:
        return jsonify(error="شماره موبایل و رمز عبور معتبر لازم است"), 400

    conn = db()
    try:
        cur = conn.execute(
            "INSERT INTO users(name,mobile,password_hash,role,status) VALUES(?,?,?,?,?)",
            (name, mobile, generate_password_hash(password), "user", "active"),
        )
        user_id = cur.lastrowid
        # Trial subscription: duration is controlled by admin settings.
        trial_row = conn.execute("SELECT trial_days FROM payment_settings WHERE id=1").fetchone()
        trial_days = int(trial_row["trial_days"] if trial_row else 7)
        now = datetime.now(timezone.utc)
        conn.execute(
            """INSERT INTO subscriptions(user_id,plan_type,start_date,end_date,status)
               VALUES(?,?,?,?,?)""",
            (user_id, "trial", now.isoformat(), (now + timedelta(days=trial_days)).isoformat(), "active"),
        )
        conn.commit()
    except sqlite3.IntegrityError:
        return jsonify(error="این شماره موبایل قبلاً ثبت شده است"), 409

    ok, error = register_device(conn, user_id, install_id_from_request(), request.headers.get("X-App-Version", ""))
    if not ok: return jsonify(error=error), 400
    return jsonify(token=token_for(user_id, "user"), user_id=user_id), 201

@app.post("/api/auth/login")
def login():
    retry = rate_limited("login")
    if retry:
        return jsonify(error="تعداد تلاش‌های ورود زیاد است؛ کمی بعد دوباره تلاش کنید", retry_after=retry), 429
    data = request.get_json(silent=True) or {}
    mobile = str(data.get("mobile", "")).strip()
    password = str(data.get("password", ""))
    row = db().execute("SELECT * FROM users WHERE mobile=?", (mobile,)).fetchone()
    if not row or not check_password_hash(row["password_hash"], password):
        return jsonify(error="شماره موبایل یا رمز عبور اشتباه است"), 401
    if row["status"] != "active":
        return jsonify(error="حساب کاربری غیرفعال است"), 403
    ok, error = register_device(db(), row["id"], install_id_from_request(), request.headers.get("X-App-Version", ""))
    if not ok: return jsonify(error=error), 403
    return jsonify(token=token_for(row["id"], row["role"]), user_id=row["id"])

@app.post("/api/auth/logout")
@auth_required
def logout():
    install_id = install_id_from_request()
    if install_id:
        db().execute("DELETE FROM user_devices WHERE user_id=? AND install_id=?", (g.user_id, install_id))
        db().commit()
    return jsonify(ok=True)

def current_subscription(conn, user_id):
    row = conn.execute("SELECT plan_type,start_date,end_date,status FROM subscriptions WHERE user_id=? ORDER BY id DESC LIMIT 1", (user_id,)).fetchone()
    if not row: return None, False
    try:
        end = datetime.fromisoformat(row["end_date"].replace("Z", "+00:00"))
        if end.tzinfo is None: end=end.replace(tzinfo=timezone.utc)
    except (ValueError,TypeError): return row, False
    return row, row["status"] == "active" and end > datetime.now(timezone.utc)

def require_active_subscription(conn, user_id):
    _, active = current_subscription(conn, user_id)
    return (True,None) if active else (False,"اشتراک شما منقضی یا غیرفعال شده است. برای ادامه، اشتراک را تمدید کنید.")

def plan_daily_limit(conn, user_id):
    row, active = current_subscription(conn, user_id)
    if not active: return 0
    limit=conn.execute("SELECT max_slots_per_day FROM plan_limits WHERE plan_type=?",(row["plan_type"],)).fetchone()
    return max(1,int(limit["max_slots_per_day"])) if limit else 3

def usage_date():
    return datetime.now(SERVER_TZ).date().isoformat()

def daily_direct_usage(conn, user_id):
    row=conn.execute("SELECT direct_sends FROM daily_slot_usage WHERE user_id=? AND usage_date=?",(user_id,usage_date())).fetchone()
    return int(row["direct_sends"]) if row else 0

def record_direct_usage(conn, user_id, count=1):
    day=usage_date()
    conn.execute("INSERT INTO daily_slot_usage(user_id,usage_date,direct_sends) VALUES(?,?,?) ON CONFLICT(user_id,usage_date) DO UPDATE SET direct_sends=direct_sends+excluded.direct_sends",(user_id,day,count))

def check_user_slot_limit(conn, user_id, extra_slots=1, exclude_batch_id=None, exclude_message_id=None):
    """Global scheduled-slot quota. A batch counts as ONE slot regardless of
    how many texts, groups or recipients it contains. Group count is unlimited."""
    limit = plan_daily_limit(conn, user_id)
    if limit <= 0:
        return False, "اشتراک شما فعال نیست."
    params=[user_id]
    standalone = "SELECT COUNT(*) FROM messages m WHERE m.user_id=? AND m.status='active' AND m.batch_id IS NULL"
    if exclude_message_id is not None:
        standalone += " AND m.id<>?"; params.append(exclude_message_id)
    used = conn.execute(standalone, tuple(params)).fetchone()[0]
    bparams=[user_id]
    batches = "SELECT COUNT(*) FROM message_batches b WHERE b.user_id=? AND b.status='active'"
    if exclude_batch_id is not None:
        batches += " AND b.id<>?"; bparams.append(exclude_batch_id)
    used += conn.execute(batches, tuple(bparams)).fetchone()[0]
    used += daily_direct_usage(conn, user_id)
    if used + extra_slots > limit:
        return False, f"سقف نوبت‌های روزانه شما تکمیل شده است؛ حداکثر {limit} نوبت فعال در روز مجاز است. اکنون {used} نوبت فعال دارید."
    return True, None

@app.post("/api/schedules")
@auth_required
def create_schedule():
    data = request.get_json(silent=True) or {}
    required = ["content", "channel", "schedule_type", "schedule_time"]
    if any(not str(data.get(k, "")).strip() for k in required):
        return jsonify(error="اطلاعات زمان‌بندی کامل نیست"), 400

    groups = data.get("group_names")
    if not isinstance(groups, list):
        legacy = str(data.get("group_name", "")).strip()
        groups = [legacy] if legacy else []
    groups = [str(x).strip() for x in groups if str(x).strip()]
    recipients = data.get("recipient_names")
    image_lists = data.get("image_uris_per_content", [])
    if not isinstance(recipients, list): recipients = []
    recipients = [str(x).strip() for x in recipients if str(x).strip()]
    if not groups and not recipients:
        return jsonify(error="حداقل یک گروه یا یک مخاطب انتخاب کنید"), 400

    # Keep platform adapters independent from the core scheduler.
    channel = str(data["channel"]).lower()
    if channel not in {"whatsapp", "bale", "telegram"}:
        return jsonify(error="پیام‌رسان پشتیبانی نمی‌شود"), 400

    conn = db()
    ok_sub, sub_error = require_active_subscription(conn, g.user_id)
    if not ok_sub: return jsonify(error=sub_error), 403
    ok, limit_error = check_user_slot_limit(conn, g.user_id, 1)
    if not ok: return jsonify(error=limit_error), 409
    cur = conn.execute(
        """INSERT INTO messages
        (user_id,content,channel,group_name,schedule_type,schedule_time,weekdays,status)
        VALUES(?,?,?,?,?,?,?,?)""",
        (
            g.user_id, data["content"], channel, groups[0] if groups else "",
            data["schedule_type"], data["schedule_time"],
            str(data.get("weekdays", "")), "active"
        ),
    )
    message_id = cur.lastrowid
    conn.executemany("INSERT INTO message_groups(message_id,group_name) VALUES(?,?)", [(message_id, name) for name in groups])
    conn.executemany("INSERT INTO message_recipients(message_id,recipient_name) VALUES(?,?)", [(message_id, name) for name in recipients])
    conn.commit()
    return jsonify(id=message_id, group_names=groups, group_count=len(groups), recipient_names=recipients, recipient_count=len(recipients)), 201

@app.post("/api/message-batches")
@auth_required
def create_message_batch():
    data = request.get_json(silent=True) or {}
    contents = data.get("contents")
    groups = data.get("group_names")
    recipients = data.get("recipient_names")
    channel = str(data.get("channel", "")).lower().strip()
    schedule_type = str(data.get("schedule_type", "")).strip()
    schedule_time = str(data.get("schedule_time", "")).strip()
    weekdays = str(data.get("weekdays", ""))
    try:
        interval = max(0, min(86400, int(data.get("interval_seconds", 0))))
    except (TypeError, ValueError): interval = 0
    if not isinstance(contents, list): return jsonify(error="contents باید لیست باشد"), 400
    contents = [str(x).strip() for x in contents if str(x).strip()]
    if not contents: return jsonify(error="حداقل یک متن لازم است"), 400
    if not isinstance(groups, list): groups=[]
    groups = [str(x).strip() for x in groups if str(x).strip()]
    if not isinstance(recipients, list): recipients=[]
    recipients = [str(x).strip() for x in recipients if str(x).strip()]
    if not isinstance(image_lists, list): image_lists=[]
    normalized_images=[]
    for i in range(len(contents)):
        raw = image_lists[i] if i < len(image_lists) else []
        if not isinstance(raw, list): raw=[]
        normalized_images.append([str(x).strip() for x in raw if str(x).strip()][:10])
    if not groups and not recipients: return jsonify(error="حداقل یک گروه یا یک مخاطب انتخاب کنید"), 400
    if channel not in {"whatsapp", "bale", "telegram"}: return jsonify(error="پیام‌رسان پشتیبانی نمی‌شود"), 400
    if not schedule_type or not schedule_time: return jsonify(error="نوع و ساعت زمان‌بندی الزامی است"), 400
    try: base = datetime.strptime(schedule_time, "%H:%M")
    except ValueError: return jsonify(error="ساعت باید به شکل HH:MM باشد"), 400
    for idx in range(len(contents)):
        if (base + timedelta(seconds=interval * idx)).day != base.day:
            return jsonify(error="فاصله پیام‌ها باعث عبور از نیمه‌شب می‌شود؛ فاصله را کمتر کنید"), 400
    conn = db()
    ok_sub, sub_error = require_active_subscription(conn, g.user_id)
    if not ok_sub: return jsonify(error=sub_error), 403
    ok, limit_error = check_user_slot_limit(conn, g.user_id, 1)
    if not ok: return jsonify(error=limit_error), 409
    cur = conn.execute("INSERT INTO message_batches(user_id,channel,schedule_type,schedule_time,weekdays,interval_seconds,status) VALUES(?,?,?,?,?,?,?)",
                       (g.user_id, channel, schedule_type, schedule_time, weekdays, interval, "active"))
    batch_id = cur.lastrowid
    ids=[]
    for idx, content in enumerate(contents):
        st=(base+timedelta(seconds=interval*idx)).strftime("%H:%M")
        cur=conn.execute("INSERT INTO messages (user_id,content,image_uris,channel,group_name,schedule_type,schedule_time,weekdays,status,batch_id,batch_order) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                         (g.user_id,content,json.dumps(normalized_images[idx], ensure_ascii=False),channel,groups[0] if groups else "",schedule_type,st,weekdays,"active",batch_id,idx))
        mid=cur.lastrowid; ids.append(mid)
        conn.executemany("INSERT INTO message_groups(message_id,group_name) VALUES(?,?)", [(mid,x) for x in groups])
        conn.executemany("INSERT INTO message_recipients(message_id,recipient_name) VALUES(?,?)", [(mid,x) for x in recipients])
    conn.commit()
    return jsonify(batch_id=batch_id,ids=ids,message_count=len(ids),group_count=len(groups),recipient_count=len(recipients),interval_seconds=interval),201

@app.get("/api/message-batches")
@auth_required
def message_batches():
    batches=db().execute("SELECT * FROM message_batches WHERE user_id=? ORDER BY id DESC",(g.user_id,)).fetchall()
    out=[]
    for b in batches:
        msgs=db().execute("SELECT id,content,image_uris,schedule_time,status,batch_order FROM messages WHERE batch_id=? AND user_id=? ORDER BY batch_order,id",(b["id"],g.user_id)).fetchall()
        groups=[]
        if msgs:
            gs=db().execute("SELECT group_name FROM message_groups WHERE message_id=? ORDER BY id",(msgs[0]["id"],)).fetchall()
            groups=[x["group_name"] for x in gs]
        recips=[]
        if msgs:
            recips=[x["recipient_name"] for x in db().execute("SELECT recipient_name FROM message_recipients WHERE message_id=? ORDER BY id",(msgs[0]["id"],)).fetchall()]
        out.append({"id":b["id"],"channel":b["channel"],"schedule_type":b["schedule_type"],"schedule_time":b["schedule_time"],"weekdays":b["weekdays"],"interval_seconds":b["interval_seconds"],"status":b["status"],"group_names":groups,"group_count":len(groups),"recipient_names":recips,"recipient_count":len(recips),"contents":[dict(x) for x in msgs]})
    return jsonify(items=out)

@app.put("/api/message-batches/<int:batch_id>")
@auth_required
def update_message_batch(batch_id):
    data=request.get_json(silent=True) or {}; conn=db()
    b=conn.execute("SELECT * FROM message_batches WHERE id=? AND user_id=?",(batch_id,g.user_id)).fetchone()
    if not b: return jsonify(error="مجموعه پیدا نشد"),404
    contents=data.get("contents",None); groups=data.get("group_names",None); recipients=data.get("recipient_names",None)
    image_lists=data.get("image_uris_per_content",None)
    if contents is None:
        msgs=conn.execute("SELECT content FROM messages WHERE batch_id=? ORDER BY batch_order,id",(batch_id,)).fetchall(); contents=[x["content"] for x in msgs]
    contents=[str(x).strip() for x in contents if str(x).strip()]
    if not contents: return jsonify(error="حداقل یک متن لازم است"),400
    if image_lists is None:
        image_lists=[]
    if not isinstance(image_lists,list): image_lists=[]
    normalized_images=[]
    for i in range(len(contents)):
        raw=image_lists[i] if i < len(image_lists) else []
        if not isinstance(raw,list): raw=[]
        normalized_images.append([str(x).strip() for x in raw if str(x).strip()][:10])
    m=conn.execute("SELECT id FROM messages WHERE batch_id=? ORDER BY batch_order,id LIMIT 1",(batch_id,)).fetchone()
    if groups is None:
        gs=conn.execute("SELECT group_name FROM message_groups WHERE message_id=? ORDER BY id",(m["id"],)).fetchall() if m else []
        groups=[x["group_name"] for x in gs]
    if recipients is None:
        rs=conn.execute("SELECT recipient_name FROM message_recipients WHERE message_id=? ORDER BY id",(m["id"],)).fetchall() if m else []
        recipients=[x["recipient_name"] for x in rs]
    if not isinstance(groups,list): groups=[]
    if not isinstance(recipients,list): recipients=[]
    groups=[str(x).strip() for x in groups if str(x).strip()]
    recipients=[str(x).strip() for x in recipients if str(x).strip()]
    if not groups and not recipients: return jsonify(error="حداقل یک گروه یا یک مخاطب انتخاب کنید"),400
    channel=str(data.get("channel",b["channel"])).lower().strip(); stype=str(data.get("schedule_type",b["schedule_type"])).strip(); stime=str(data.get("schedule_time",b["schedule_time"])).strip(); weekdays=str(data.get("weekdays",b["weekdays"]))
    try: interval=max(0,min(86400,int(data.get("interval_seconds",b["interval_seconds"]))))
    except: interval=b["interval_seconds"]
    status=str(data.get("status",b["status"])).lower().strip(); status=status if status in {"active","paused"} else b["status"]
    if channel not in {"whatsapp","bale","telegram"}: return jsonify(error="پیام‌رسان پشتیبانی نمی‌شود"),400
    try: base=datetime.strptime(stime,"%H:%M")
    except ValueError: return jsonify(error="ساعت باید به شکل HH:MM باشد"),400
    if any((base+timedelta(seconds=interval*i)).day!=base.day for i in range(len(contents))): return jsonify(error="فاصله پیام‌ها باعث عبور از نیمه‌شب می‌شود"),400
    if status == "active":
        ok_sub, sub_error = require_active_subscription(conn, g.user_id)
        if not ok_sub: return jsonify(error=sub_error),403
    if status == "active":
        ok, limit_error = check_user_slot_limit(conn, g.user_id, 1, exclude_batch_id=batch_id)
        if not ok: return jsonify(error=limit_error), 409
    conn.execute("UPDATE message_batches SET channel=?,schedule_type=?,schedule_time=?,weekdays=?,interval_seconds=?,status=? WHERE id=? AND user_id=?",(channel,stype,stime,weekdays,interval,status,batch_id,g.user_id))
    old=conn.execute("SELECT id FROM messages WHERE batch_id=? AND user_id=? ORDER BY batch_order,id",(batch_id,g.user_id)).fetchall()
    for r in old: conn.execute("DELETE FROM messages WHERE id=?",(r["id"],))
    ids=[]
    for idx,content in enumerate(contents):
        mt=(base+timedelta(seconds=interval*idx)).strftime("%H:%M")
        cur=conn.execute("INSERT INTO messages (user_id,content,image_uris,channel,group_name,schedule_type,schedule_time,weekdays,status,batch_id,batch_order) VALUES(?,?,?,?,?,?,?,?,?,?,?)",(g.user_id,content,json.dumps(normalized_images[idx], ensure_ascii=False),channel,groups[0] if groups else "",stype,mt,weekdays,status,batch_id,idx))
        mid=cur.lastrowid; ids.append(mid); conn.executemany("INSERT INTO message_groups(message_id,group_name) VALUES(?,?)",[(mid,x) for x in groups]); conn.executemany("INSERT INTO message_recipients(message_id,recipient_name) VALUES(?,?)",[(mid,x) for x in recipients])
    conn.commit(); return jsonify(ok=True,batch_id=batch_id,ids=ids,status=status,recipient_names=recipients)

@app.post("/api/message-batches/<int:batch_id>/status")
@auth_required
def batch_status(batch_id):
    data=request.get_json(silent=True) or {}; status=str(data.get("status","")).lower().strip()
    if status not in {"active","paused"}: return jsonify(error="وضعیت باید active یا paused باشد"),400
    conn=db()
    if status == "active":
        ok_sub, sub_error = require_active_subscription(conn, g.user_id)
        if not ok_sub: return jsonify(error=sub_error),403
        current=conn.execute("SELECT status FROM message_batches WHERE id=? AND user_id=?",(batch_id,g.user_id)).fetchone()
        if not current: return jsonify(error="مجموعه پیدا نشد"),404
        if current["status"] != "active":
            ok, limit_error=check_user_slot_limit(conn,g.user_id,1)
            if not ok: return jsonify(error=limit_error),409
    cur=conn.execute("UPDATE message_batches SET status=? WHERE id=? AND user_id=?",(status,batch_id,g.user_id))
    if not cur.rowcount: return jsonify(error="مجموعه پیدا نشد"),404
    conn.execute("UPDATE messages SET status=? WHERE batch_id=? AND user_id=?",(status,batch_id,g.user_id)); conn.commit(); return jsonify(ok=True,batch_id=batch_id,status=status)

@app.delete("/api/message-batches/<int:batch_id>")
@auth_required
def delete_message_batch(batch_id):
    conn=db(); cur=conn.execute("DELETE FROM message_batches WHERE id=? AND user_id=?",(batch_id,g.user_id))
    if not cur.rowcount: return jsonify(error="مجموعه پیدا نشد"),404
    conn.execute("DELETE FROM messages WHERE batch_id=? AND user_id=?",(batch_id,g.user_id)); conn.commit(); return jsonify(ok=True)

@app.get("/api/schedules")
@auth_required
def schedules():
    rows = db().execute(
        """SELECT id,content,channel,group_name,schedule_type,schedule_time,
                  weekdays,status,created_at
           FROM messages WHERE user_id=? ORDER BY schedule_time""",
        (g.user_id,),
    ).fetchall()
    items=[]
    for r in rows:
        item=dict(r)
        gs=db().execute("SELECT group_name FROM message_groups WHERE message_id=? ORDER BY id", (r["id"],)).fetchall()
        item["group_names"]=[x["group_name"] for x in gs] or ([r["group_name"]] if r["group_name"] else [])
        item["group_count"]=len(item["group_names"])
        item["recipient_names"]=[x["recipient_name"] for x in db().execute("SELECT recipient_name FROM message_recipients WHERE message_id=? ORDER BY id",(r["id"],)).fetchall()]
        item["recipient_count"]=len(item["recipient_names"])
        items.append(item)
    return jsonify(items=items)

@app.put("/api/schedules/<int:message_id>")
@auth_required
def update_schedule(message_id):
    data = request.get_json(silent=True) or {}
    conn = db()
    row = conn.execute("SELECT * FROM messages WHERE id=? AND user_id=?", (message_id, g.user_id)).fetchone()
    if not row:
        return jsonify(error="زمان‌بندی پیدا نشد"), 404
    content = str(data.get("content", row["content"])).strip()
    channel = str(data.get("channel", row["channel"])).lower().strip()
    schedule_type = str(data.get("schedule_type", row["schedule_type"])).strip()
    schedule_time = str(data.get("schedule_time", row["schedule_time"])).strip()
    weekdays = str(data.get("weekdays", row["weekdays"]))
    status = str(data.get("status", row["status"])).lower().strip()
    if not content or channel not in {"whatsapp", "bale", "telegram"} or not schedule_type:
        return jsonify(error="اطلاعات زمان‌بندی نامعتبر است"), 400
    try:
        datetime.strptime(schedule_time, "%H:%M")
    except ValueError:
        return jsonify(error="ساعت باید به شکل HH:MM باشد"), 400
    if status not in {"active", "paused"}:
        status = row["status"] if row["status"] in {"active", "paused"} else "active"
    groups = data.get("group_names", None)
    recipients = data.get("recipient_names", None)
    if groups is None:
        gs = conn.execute("SELECT group_name FROM message_groups WHERE message_id=? ORDER BY id", (message_id,)).fetchall()
        groups = [x["group_name"] for x in gs] or ([row["group_name"]] if row["group_name"] else [])
    if recipients is None:
        rs = conn.execute("SELECT recipient_name FROM message_recipients WHERE message_id=? ORDER BY id", (message_id,)).fetchall()
        recipients = [x["recipient_name"] for x in rs]
    if not isinstance(groups, list): groups=[]
    if not isinstance(recipients, list): recipients=[]
    groups = [str(x).strip() for x in groups if str(x).strip()]
    recipients = [str(x).strip() for x in recipients if str(x).strip()]
    if not groups and not recipients:
        return jsonify(error="حداقل یک گروه یا یک مخاطب انتخاب کنید"), 400
    if status == "active" and row["status"] != "active":
        ok_sub, sub_error = require_active_subscription(conn, g.user_id)
        if not ok_sub: return jsonify(error=sub_error),403
        ok, limit_error = check_user_slot_limit(conn, g.user_id, 1)
        if not ok: return jsonify(error=limit_error),409
    conn.execute("UPDATE messages SET content=?,channel=?,group_name=?,schedule_type=?,schedule_time=?,weekdays=?,status=? WHERE id=? AND user_id=?",
                 (content, channel, groups[0], schedule_type, schedule_time, weekdays, status, message_id, g.user_id))
    conn.execute("DELETE FROM message_groups WHERE message_id=?", (message_id,))
    conn.executemany("INSERT INTO message_groups(message_id,group_name) VALUES(?,?)", [(message_id, x) for x in groups])
    conn.execute("DELETE FROM message_recipients WHERE message_id=?", (message_id,))
    conn.executemany("INSERT INTO message_recipients(message_id,recipient_name) VALUES(?,?)", [(message_id, x) for x in recipients])
    conn.commit()
    return jsonify(ok=True, id=message_id, group_names=groups, group_count=len(groups), recipient_names=recipients, recipient_count=len(recipients), status=status)

@app.post("/api/schedules/<int:message_id>/status")
@auth_required
def schedule_status(message_id):
    data = request.get_json(silent=True) or {}
    status = str(data.get("status", "")).lower().strip()
    if status not in {"active", "paused"}:
        return jsonify(error="وضعیت باید active یا paused باشد"), 400
    conn = db()
    if status == "active":
        ok_sub, sub_error = require_active_subscription(conn, g.user_id)
        if not ok_sub: return jsonify(error=sub_error), 403
        current=conn.execute("SELECT status FROM messages WHERE id=? AND user_id=?",(message_id,g.user_id)).fetchone()
        if not current: return jsonify(error="زمان‌بندی پیدا نشد"),404
        if current["status"] != "active":
            ok, limit_error=check_user_slot_limit(conn,g.user_id,1)
            if not ok: return jsonify(error=limit_error),409
    cur = conn.execute("UPDATE messages SET status=? WHERE id=? AND user_id=?", (status, message_id, g.user_id))
    conn.commit()
    if cur.rowcount == 0:
        return jsonify(error="زمان‌بندی پیدا نشد"), 404
    return jsonify(ok=True, id=message_id, status=status)

@app.delete("/api/schedules/<int:message_id>")
@auth_required
def delete_schedule(message_id):
    conn = db()
    cur = conn.execute("DELETE FROM messages WHERE id=? AND user_id=?", (message_id, g.user_id))
    conn.commit()
    if cur.rowcount == 0:
        return jsonify(error="زمان‌بندی پیدا نشد"), 404
    return jsonify(ok=True)


@app.get("/api/recipients")
@auth_required
def recipients():
    rows=db().execute("SELECT id,name,channel,target_key,created_at FROM recipients WHERE user_id=? ORDER BY channel,name",(g.user_id,)).fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.post("/api/recipients")
@auth_required
def add_recipient():
    data=request.get_json(silent=True) or {}
    name=str(data.get("name","")).strip(); channel=str(data.get("channel","")).lower().strip(); target_key=str(data.get("target_key","")).strip()
    if not name or channel not in {"whatsapp","bale","telegram"}:
        return jsonify(error="نام مخاطب و پیام‌رسان معتبر لازم است"),400
    conn=db()
    try:
        cur=conn.execute("INSERT INTO recipients(user_id,name,channel,target_key) VALUES(?,?,?,?)",(g.user_id,name,channel,target_key))
        conn.commit()
    except sqlite3.IntegrityError:
        return jsonify(error="این مخاطب قبلاً در دفترچه ثبت شده است"),409
    return jsonify(id=cur.lastrowid,name=name,channel=channel,target_key=target_key),201

@app.delete("/api/recipients/<int:recipient_id>")
@auth_required
def delete_recipient(recipient_id):
    conn=db(); cur=conn.execute("DELETE FROM recipients WHERE id=? AND user_id=?",(recipient_id,g.user_id)); conn.commit()
    if cur.rowcount==0: return jsonify(error="مخاطب پیدا نشد"),404
    return jsonify(ok=True)

@app.post("/api/direct-messages")
@auth_required
def create_direct_message():
    data=request.get_json(silent=True) or {}
    content=str(data.get("content","")).strip(); channel=str(data.get("channel","")).lower().strip()
    ids=data.get("recipient_ids")
    if not content: return jsonify(error="متن پیام الزامی است"),400
    if channel not in {"whatsapp","bale","telegram"}: return jsonify(error="پیام‌رسان پشتیبانی نمی‌شود"),400
    if not isinstance(ids,list) or not ids: return jsonify(error="حداقل یک مخاطب انتخاب کنید"),400
    conn=db(); ok_sub,sub_error=require_active_subscription(conn,g.user_id)
    if not ok_sub: return jsonify(error=sub_error),403
    ok, limit_error = check_user_slot_limit(conn, g.user_id, 1)
    if not ok: return jsonify(error=limit_error),409
    clean=[]
    for x in ids:
        try: clean.append(int(x))
        except: pass
    if not clean: return jsonify(error="مخاطب معتبر نیست"),400
    marks=','.join('?' for _ in clean)
    rows=conn.execute(f"SELECT id,name,channel,target_key FROM recipients WHERE user_id=? AND id IN ({marks}) AND channel=? ORDER BY id",[g.user_id,*clean,channel]).fetchall()
    if len(rows)!=len(set(clean)): return jsonify(error="برخی مخاطبان متعلق به این حساب یا این پیام‌رسان نیستند"),400
    record_direct_usage(conn,g.user_id,1)
    conn.commit()
    return jsonify(ok=True,channel=channel,content=content,recipients=[dict(r) for r in rows]),202

@app.get("/api/recipient-lists")
@auth_required
def recipient_lists():
    conn=db()
    rows=conn.execute("SELECT id,name,channel,created_at FROM recipient_lists WHERE user_id=? ORDER BY channel,name",(g.user_id,)).fetchall()
    out=[]
    for r in rows:
        count=conn.execute("SELECT COUNT(*) FROM recipient_list_items li JOIN recipients p ON p.id=li.recipient_id WHERE li.list_id=? AND p.user_id=?",(r["id"],g.user_id)).fetchone()[0]
        out.append({**dict(r),"recipient_count":int(count)})
    return jsonify(items=out)

@app.post("/api/recipient-lists")
@auth_required
def create_recipient_list():
    data=request.get_json(silent=True) or {}
    name=str(data.get("name","")).strip(); channel=str(data.get("channel","")).lower().strip()
    ids=data.get("recipient_ids",[])
    if not name or channel not in {"whatsapp","bale","telegram"}: return jsonify(error="نام لیست و پیام‌رسان معتبر لازم است"),400
    if not isinstance(ids,list): ids=[]
    clean=[]
    for x in ids:
        try: clean.append(int(x))
        except: pass
    conn=db()
    try:
        cur=conn.execute("INSERT INTO recipient_lists(user_id,name,channel) VALUES(?,?,?)",(g.user_id,name,channel))
        list_id=cur.lastrowid
    except sqlite3.IntegrityError:
        return jsonify(error="این نام لیست برای این پیام‌رسان قبلاً ثبت شده است"),409
    if clean:
        marks=','.join('?' for _ in clean)
        rows=conn.execute(f"SELECT id FROM recipients WHERE user_id=? AND channel=? AND id IN ({marks})",[g.user_id,channel,*clean]).fetchall()
        conn.executemany("INSERT OR IGNORE INTO recipient_list_items(list_id,recipient_id) VALUES(?,?)",[(list_id,r["id"]) for r in rows])
    conn.commit()
    return jsonify(id=list_id,name=name,channel=channel,recipient_count=len(clean)),201

@app.get("/api/recipient-lists/<int:list_id>")
@auth_required
def get_recipient_list(list_id):
    conn=db(); row=conn.execute("SELECT id,name,channel,created_at FROM recipient_lists WHERE id=? AND user_id=?",(list_id,g.user_id)).fetchone()
    if not row: return jsonify(error="لیست پیدا نشد"),404
    rows=conn.execute("SELECT p.id,p.name,p.channel,p.target_key,p.created_at FROM recipient_list_items li JOIN recipients p ON p.id=li.recipient_id WHERE li.list_id=? AND p.user_id=? ORDER BY p.name",(list_id,g.user_id)).fetchall()
    return jsonify(list={**dict(row),"recipient_count":len(rows)},recipients=[dict(r) for r in rows])

@app.put("/api/recipient-lists/<int:list_id>")
@auth_required
def update_recipient_list(list_id):
    data=request.get_json(silent=True) or {}; conn=db()
    row=conn.execute("SELECT * FROM recipient_lists WHERE id=? AND user_id=?",(list_id,g.user_id)).fetchone()
    if not row: return jsonify(error="لیست پیدا نشد"),404
    name=str(data.get("name",row["name"])).strip(); channel=str(data.get("channel",row["channel"])).lower().strip(); ids=data.get("recipient_ids",None)
    if not name or channel not in {"whatsapp","bale","telegram"}: return jsonify(error="اطلاعات لیست نامعتبر است"),400
    try:
        conn.execute("UPDATE recipient_lists SET name=?,channel=? WHERE id=? AND user_id=?",(name,channel,list_id,g.user_id))
    except sqlite3.IntegrityError:
        return jsonify(error="این نام لیست قبلاً استفاده شده است"),409
    if ids is not None:
        if not isinstance(ids,list): ids=[]
        clean=[]
        for x in ids:
            try: clean.append(int(x))
            except: pass
        conn.execute("DELETE FROM recipient_list_items WHERE list_id=?",(list_id,))
        if clean:
            marks=','.join('?' for _ in clean)
            rows=conn.execute(f"SELECT id FROM recipients WHERE user_id=? AND channel=? AND id IN ({marks})",[g.user_id,channel,*clean]).fetchall()
            conn.executemany("INSERT OR IGNORE INTO recipient_list_items(list_id,recipient_id) VALUES(?,?)",[(list_id,r["id"]) for r in rows])
    conn.commit()
    return get_recipient_list(list_id)

@app.delete("/api/recipient-lists/<int:list_id>")
@auth_required
def delete_recipient_list(list_id):
    conn=db(); cur=conn.execute("DELETE FROM recipient_lists WHERE id=? AND user_id=?",(list_id,g.user_id)); conn.commit()
    if not cur.rowcount: return jsonify(error="لیست پیدا نشد"),404
    return jsonify(ok=True)

@app.get("/api/payment-settings")
@auth_required
def payment_settings():
    row = db().execute("SELECT card_number,account_holder,monthly_price,yearly_price,trial_days,support_phone FROM payment_settings WHERE id=1").fetchone()
    return jsonify(settings=dict(row) if row else None)

@app.post("/api/discount-codes/validate")
@auth_required
def validate_discount_code():
    data=request.get_json(silent=True) or {}
    plan=str(data.get("plan_type","")).lower(); code=str(data.get("coupon_code","")).strip().upper()
    if plan not in {"monthly","yearly"} or not code: return jsonify(error="پلن و کد تخفیف الزامی است"),400
    conn=db(); settings=conn.execute("SELECT monthly_price,yearly_price FROM payment_settings WHERE id=1").fetchone(); coupon=conn.execute("SELECT * FROM discount_codes WHERE code=?",(code,)).fetchone()
    if not settings or not coupon or not coupon["active"]: return jsonify(error="کد تخفیف نامعتبر یا غیرفعال است"),400
    now=datetime.now(timezone.utc)
    if coupon["expires_at"]:
        try:
            if datetime.fromisoformat(coupon["expires_at"]) < now: return jsonify(error="کد تخفیف منقضی شده است"),400
        except ValueError: return jsonify(error="تاریخ کد تخفیف نامعتبر است"),400
    if coupon["max_uses"]>0 and coupon["used_count"]>=coupon["max_uses"]: return jsonify(error="ظرفیت استفاده از این کد تمام شده است"),400
    original=int(settings["monthly_price"] if plan=="monthly" else settings["yearly_price"]); discount=min(original,round(original*coupon["percent"]/100)); return jsonify(ok=True,coupon_code=code,percent=coupon["percent"],original_amount=original,discount_amount=discount,final_amount=original-discount)

@app.post("/api/payments/submit")
@auth_required
def submit_payment():
    data = request.get_json(silent=True) or {}
    plan = str(data.get("plan_type", "")).lower()
    reference = str(data.get("payer_reference", "")).strip()
    receipt_name = str(data.get("receipt_name", "")).strip() or None
    if plan not in {"monthly", "yearly"} or not reference:
        return jsonify(error="پلن و شماره پیگیری پرداخت الزامی است"), 400
    settings = db().execute("SELECT * FROM payment_settings WHERE id=1").fetchone()
    if not settings:
        return jsonify(error="تنظیمات پرداخت ثبت نشده است"), 500
    original_amount = int(settings["monthly_price"] if plan == "monthly" else settings["yearly_price"])
    if original_amount <= 0 or not settings["card_number"]:
        return jsonify(error="تنظیمات کارت و قیمت توسط مدیر تکمیل نشده است"), 503
    coupon_code = str(data.get("coupon_code", "")).strip().upper()
    discount_amount = 0
    conn = db()
    if coupon_code:
        coupon = conn.execute("SELECT * FROM discount_codes WHERE code=?", (coupon_code,)).fetchone()
        if not coupon or not coupon["active"]:
            return jsonify(error="کد تخفیف نامعتبر یا غیرفعال است"), 400
        now = datetime.now(timezone.utc)
        if coupon["expires_at"]:
            try:
                if datetime.fromisoformat(coupon["expires_at"]) < now: return jsonify(error="کد تخفیف منقضی شده است"), 400
            except ValueError: return jsonify(error="تاریخ کد تخفیف نامعتبر است"), 400
        if coupon["max_uses"] > 0 and coupon["used_count"] >= coupon["max_uses"]:
            return jsonify(error="ظرفیت استفاده از این کد تخفیف تمام شده است"), 400
        discount_amount = min(original_amount, round(original_amount * coupon["percent"] / 100))
    amount = max(0, original_amount - discount_amount)
    cur = conn.execute(
        "INSERT INTO payments(user_id,plan_type,amount,destination_card,account_holder,payer_reference,receipt_name,status,coupon_code,original_amount,discount_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (g.user_id, plan, amount, settings["card_number"], settings["account_holder"], reference, receipt_name, "pending", coupon_code or None, original_amount, discount_amount)
    )
    if coupon_code:
        conn.execute("UPDATE discount_codes SET used_count=used_count+1 WHERE code=?", (coupon_code,))
    conn.commit()
    return jsonify(payment_id=cur.lastrowid, status="pending", amount=amount, message="پرداخت ثبت شد و در انتظار تأیید مدیر است."), 201

@app.get("/api/payments")
@auth_required
def my_payments():
    rows = db().execute("SELECT id,plan_type,amount,payer_reference,receipt_name,status,admin_note,created_at,reviewed_at FROM payments WHERE user_id=? ORDER BY id DESC", (g.user_id,)).fetchall()
    return jsonify(items=[dict(r) for r in rows])


@app.get("/api/support/thread")
@auth_required
def support_thread():
    conn=db()
    thread=conn.execute("SELECT * FROM support_threads WHERE user_id=? AND status='open' ORDER BY id DESC LIMIT 1",(g.user_id,)).fetchone()
    if not thread:
        cur=conn.execute("INSERT INTO support_threads(user_id,status) VALUES(?, 'open')",(g.user_id,)); conn.commit()
        thread=conn.execute("SELECT * FROM support_threads WHERE id=?",(cur.lastrowid,)).fetchone()
    msgs=conn.execute("SELECT id,sender_role,content,created_at FROM support_messages WHERE thread_id=? ORDER BY id",(thread["id"],)).fetchall()
    return jsonify(thread=dict(thread),items=[dict(x) for x in msgs])

@app.post("/api/support/messages")
@auth_required
def support_send_message():
    retry = rate_limited("support")
    if retry:
        return jsonify(error="ارسال پیام پشتیبانی بیش از حد مجاز است؛ کمی بعد دوباره تلاش کنید", retry_after=retry), 429
    data=request.get_json(silent=True) or {}; content=str(data.get("content","")).strip()
    if not content: return jsonify(error="متن پیام خالی است"),400
    if len(content)>4000: return jsonify(error="پیام بیش از حد طولانی است"),400
    conn=db(); thread=conn.execute("SELECT id FROM support_threads WHERE user_id=? AND status='open' ORDER BY id DESC LIMIT 1",(g.user_id,)).fetchone()
    if not thread:
        cur=conn.execute("INSERT INTO support_threads(user_id,status) VALUES(?, 'open')",(g.user_id,)); tid=cur.lastrowid
    else: tid=thread["id"]
    cur=conn.execute("INSERT INTO support_messages(thread_id,sender_role,content) VALUES(?,?,?)",(tid,"user",content))
    conn.execute("UPDATE support_threads SET updated_at=CURRENT_TIMESTAMP WHERE id=?",(tid,)); conn.commit()
    row=conn.execute("SELECT id,sender_role,content,created_at FROM support_messages WHERE id=?",(cur.lastrowid,)).fetchone()
    return jsonify(item=dict(row)),201

@app.get("/api/admin/devices")
@auth_required
def admin_devices():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"),403
    rows=db().execute("SELECT d.id,d.user_id,u.mobile,u.name,d.install_id,d.app_version,d.created_at,d.last_seen_at FROM user_devices d JOIN users u ON u.id=d.user_id ORDER BY d.last_seen_at DESC").fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.delete("/api/admin/devices/<int:device_id>")
@auth_required
def admin_delete_device(device_id):
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"),403
    cur=db().execute("DELETE FROM user_devices WHERE id=?",(device_id,)); db().commit()
    if cur.rowcount == 0: return jsonify(error="دستگاه پیدا نشد"),404
    return jsonify(ok=True)

@app.get("/api/admin/support")
@auth_required
def admin_support_threads():
    conn=db()
    me=conn.execute("SELECT role FROM users WHERE id=?",(g.user_id,)).fetchone()
    if not me or me["role"]!="admin": return jsonify(error="دسترسی غیرمجاز"),403
    rows=conn.execute("""SELECT t.id,t.user_id,t.status,t.updated_at,u.name user_name,u.mobile
                         FROM support_threads t JOIN users u ON u.id=t.user_id
                         ORDER BY t.updated_at DESC,t.id DESC""").fetchall()
    out=[]
    for t in rows:
        msgs=conn.execute("SELECT id,sender_role,content,created_at FROM support_messages WHERE thread_id=? ORDER BY id",(t["id"],)).fetchall()
        out.append({**dict(t),"messages":[dict(x) for x in msgs]})
    return jsonify(items=out)

@app.post("/api/admin/support/<int:thread_id>/messages")
@auth_required
def admin_support_send(thread_id):
    conn=db(); me=conn.execute("SELECT role FROM users WHERE id=?",(g.user_id,)).fetchone()
    if not me or me["role"]!="admin": return jsonify(error="دسترسی غیرمجاز"),403
    data=request.get_json(silent=True) or {}; content=str(data.get("content","")).strip()
    if not content: return jsonify(error="متن پیام خالی است"),400
    thread=conn.execute("SELECT id FROM support_threads WHERE id=?",(thread_id,)).fetchone()
    if not thread: return jsonify(error="گفتگو پیدا نشد"),404
    cur=conn.execute("INSERT INTO support_messages(thread_id,sender_role,content) VALUES(?,?,?)",(thread_id,"admin",content))
    conn.execute("UPDATE support_threads SET updated_at=CURRENT_TIMESTAMP WHERE id=?",(thread_id,)); conn.commit()
    row=conn.execute("SELECT id,sender_role,content,created_at FROM support_messages WHERE id=?",(cur.lastrowid,)).fetchone()
    return jsonify(item=dict(row)),201

@app.post("/api/admin/support/<int:thread_id>/close")
@auth_required
def admin_support_close(thread_id):
    conn=db(); me=conn.execute("SELECT role FROM users WHERE id=?",(g.user_id,)).fetchone()
    if not me or me["role"]!="admin": return jsonify(error="دسترسی غیرمجاز"),403
    cur=conn.execute("UPDATE support_threads SET status='closed',updated_at=CURRENT_TIMESTAMP WHERE id=?",(thread_id,)); conn.commit()
    if not cur.rowcount: return jsonify(error="گفتگو پیدا نشد"),404
    return jsonify(ok=True)

@app.get("/api/admin/payments")
@auth_required
def admin_payments():
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    status = str(request.args.get("status", "pending"))
    if status not in {"pending", "approved", "rejected", "all"}:
        return jsonify(error="وضعیت نامعتبر است"), 400
    q = """SELECT p.*, u.name AS user_name, u.mobile AS user_mobile FROM payments p JOIN users u ON u.id=p.user_id"""
    params = []
    if status != "all":
        q += " WHERE p.status=?"
        params.append(status)
    q += " ORDER BY p.id DESC"
    rows = db().execute(q, params).fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.post("/api/admin/payments/<int:payment_id>/approve")
@auth_required
def approve_payment(payment_id):
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    data = request.get_json(silent=True) or {}
    conn = db()
    payment = conn.execute("SELECT * FROM payments WHERE id=?", (payment_id,)).fetchone()
    if not payment:
        return jsonify(error="پرداخت پیدا نشد"), 404
    if payment["status"] != "pending":
        return jsonify(error="این پرداخت قبلاً بررسی شده است"), 409
    now = datetime.now(timezone.utc)
    days = 365 if payment["plan_type"] == "yearly" else 30
    current = conn.execute("SELECT end_date FROM subscriptions WHERE user_id=? AND status='active' ORDER BY end_date DESC LIMIT 1", (payment["user_id"],)).fetchone()
    start = now
    if current:
        try:
            existing_end = datetime.fromisoformat(current["end_date"])
            if existing_end > now:
                start = existing_end
        except ValueError:
            pass
    end = start + timedelta(days=days)
    conn.execute("UPDATE payments SET status='approved',admin_note=?,reviewed_at=?,paid_at=? WHERE id=?", (str(data.get("admin_note", "")).strip() or None, now.isoformat(), now.isoformat(), payment_id))
    conn.execute("INSERT INTO subscriptions(user_id,plan_type,start_date,end_date,status) VALUES(?,?,?,?,?)", (payment["user_id"], payment["plan_type"], start.isoformat(), end.isoformat(), "active"))
    conn.commit()
    return jsonify(ok=True, subscription_end=end.isoformat())

@app.post("/api/admin/payments/<int:payment_id>/reject")
@auth_required
def reject_payment(payment_id):
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    data = request.get_json(silent=True) or {}
    conn = db()
    payment = conn.execute("SELECT status FROM payments WHERE id=?", (payment_id,)).fetchone()
    if not payment:
        return jsonify(error="پرداخت پیدا نشد"), 404
    if payment["status"] != "pending":
        return jsonify(error="این پرداخت قبلاً بررسی شده است"), 409
    now = datetime.now(timezone.utc)
    conn.execute("UPDATE payments SET status='rejected',admin_note=?,reviewed_at=? WHERE id=?", (str(data.get("admin_note", "")).strip() or None, now.isoformat(), payment_id))
    conn.commit()
    return jsonify(ok=True)

@app.put("/api/admin/payment-settings")
@auth_required
def update_payment_settings():
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    data = request.get_json(silent=True) or {}
    card = str(data.get("card_number", "")).strip()
    holder = str(data.get("account_holder", "")).strip()
    try:
        monthly = int(data.get("monthly_price", 0))
        yearly = int(data.get("yearly_price", 0))
        trial = int(data.get("trial_days", 7))
        support_phone = str(data.get("support_phone", "09372544666")).strip()
    except (TypeError, ValueError):
        return jsonify(error="مقادیر قیمت نامعتبر است"), 400
    if monthly < 0 or yearly < 0 or trial < 0:
        return jsonify(error="قیمت و مدت آزمایشی نمی‌تواند منفی باشد"), 400
    digits = "".join(ch for ch in support_phone if ch.isdigit())
    if digits.startswith("98") and len(digits) == 12: digits = "0" + digits[2:]
    if len(digits) != 11 or not digits.startswith("09"):
        return jsonify(error="شماره پشتیبانی نامعتبر است"), 400
    support_phone = digits
    conn = db()
    conn.execute("UPDATE payment_settings SET card_number=?,account_holder=?,monthly_price=?,yearly_price=?,trial_days=?,support_phone=?,updated_at=? WHERE id=1", (card, holder, monthly, yearly, trial, support_phone, datetime.now(timezone.utc).isoformat()))
    conn.commit()
    return jsonify(ok=True)

@app.get("/api/admin/discount-codes")
@auth_required
def admin_discount_codes():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"), 403
    rows = db().execute("SELECT id,code,percent,max_uses,used_count,expires_at,active,created_at FROM discount_codes ORDER BY id DESC").fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.post("/api/admin/discount-codes")
@auth_required
def create_discount_code():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"), 403
    data=request.get_json(silent=True) or {}
    code=str(data.get("code","")).strip().upper()
    try: percent=int(data.get("percent",0)); max_uses=int(data.get("max_uses",0))
    except (TypeError,ValueError): return jsonify(error="مقدار تخفیف نامعتبر است"),400
    expires=str(data.get("expires_at","")).strip() or None
    if not code or len(code)>64 or percent<1 or percent>100 or max_uses<0: return jsonify(error="کد یا درصد تخفیف نامعتبر است"),400
    conn=db()
    try:
        cur=conn.execute("INSERT INTO discount_codes(code,percent,max_uses,used_count,expires_at,active) VALUES(?,?,?,?,?,1)",(code,percent,max_uses,0,expires))
        conn.commit(); return jsonify(id=cur.lastrowid,ok=True),201
    except sqlite3.IntegrityError: return jsonify(error="این کد قبلاً ثبت شده است"),409

@app.post("/api/admin/discount-codes/<int:code_id>/toggle")
@auth_required
def toggle_discount_code(code_id):
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"),403
    conn=db(); row=conn.execute("SELECT active FROM discount_codes WHERE id=?",(code_id,)).fetchone()
    if not row: return jsonify(error="کد پیدا نشد"),404
    conn.execute("UPDATE discount_codes SET active=? WHERE id=?",(0 if row["active"] else 1,code_id)); conn.commit(); return jsonify(ok=True)

@app.delete("/api/admin/discount-codes/<int:code_id>")
@auth_required
def delete_discount_code(code_id):
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"),403
    conn=db(); cur=conn.execute("DELETE FROM discount_codes WHERE id=?",(code_id,)); conn.commit()
    if not cur.rowcount: return jsonify(error="کد پیدا نشد"),404
    return jsonify(ok=True)

@app.get("/api/group-limits")
@auth_required
def user_group_limits():
    rows=db().execute("SELECT channel,group_name,max_slots_per_day FROM group_limits WHERE user_id=? ORDER BY channel,group_name", (g.user_id,)).fetchall()
    return jsonify(default=3, items=[dict(r) for r in rows])

@app.get("/api/admin/group-limits")
@auth_required
def admin_group_limits():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"), 403
    rows=db().execute("SELECT gl.id,gl.user_id,gl.channel,gl.group_name,gl.max_slots_per_day,u.name user_name,u.mobile user_mobile FROM group_limits gl JOIN users u ON u.id=gl.user_id ORDER BY gl.id DESC LIMIT 1000").fetchall()
    return jsonify(default=3, items=[dict(r) for r in rows])

@app.put("/api/admin/group-limits")
@auth_required
def admin_update_group_limit():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"), 403
    data=request.get_json(silent=True) or {}
    try: user_id=int(data.get("user_id")); limit=int(data.get("max_slots_per_day"))
    except (TypeError,ValueError): return jsonify(error="کاربر و سقف نامعتبر است"),400
    channel=str(data.get("channel","")).lower().strip(); group=str(data.get("group_name","")).strip()
    if channel not in {"whatsapp","bale","telegram"} or not group: return jsonify(error="پیام‌رسان و گروه معتبر لازم است"),400
    if limit < 1 or limit > 100: return jsonify(error="سقف باید بین ۱ تا ۱۰۰ باشد"),400
    conn=db(); user=conn.execute("SELECT id FROM users WHERE id=?",(user_id,)).fetchone()
    if not user: return jsonify(error="کاربر پیدا نشد"),404
    conn.execute("INSERT INTO group_limits(user_id,channel,group_name,max_slots_per_day,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id,channel,group_name) DO UPDATE SET max_slots_per_day=excluded.max_slots_per_day,updated_at=excluded.updated_at",(user_id,channel,group,limit,datetime.now(timezone.utc).isoformat()))
    conn.commit(); return jsonify(ok=True)

@app.get("/api/admin/plan-limits")
@auth_required
def admin_plan_limits():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"), 403
    rows=db().execute("SELECT plan_type,max_slots_per_day,updated_at FROM plan_limits ORDER BY id").fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.put("/api/admin/plan-limits")
@auth_required
def admin_update_plan_limit():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"), 403
    data=request.get_json(silent=True) or {}
    plan=str(data.get("plan_type","")).lower().strip()
    try: limit=max(1,min(100,int(data.get("max_slots_per_day"))))
    except (TypeError,ValueError): return jsonify(error="سقف روزانه نامعتبر است"),400
    if plan not in {"trial","monthly","yearly"}: return jsonify(error="پلن نامعتبر است"),400
    conn=db(); conn.execute("UPDATE plan_limits SET max_slots_per_day=?,updated_at=? WHERE plan_type=?",(limit,datetime.now(timezone.utc).isoformat(),plan)); conn.commit()
    return jsonify(ok=True,plan_type=plan,max_slots_per_day=limit)



@app.post("/api/reports/send-logs")
@auth_required
def upload_send_logs():
    data=request.get_json(silent=True) or {}
    items=data.get("items")
    if not isinstance(items,list) or not items:
        return jsonify(error="گزارشی برای ثبت ارسال نشده است"),400
    conn=db(); saved=0; skipped=0
    for item in items[:500]:
        try:
            message_id=item.get("message_id")
            message_id=int(message_id) if message_id is not None and int(message_id)>0 else None
            channel=str(item.get("channel","")).lower().strip()
            target=str(item.get("target","")).strip()
            content=str(item.get("content","")).strip()
            status="success" if bool(item.get("success")) else "failed"
            detail=str(item.get("detail","")).strip()
            sent_at=str(item.get("sent_at","")).strip() or datetime.now(timezone.utc).isoformat()
            if channel not in {"whatsapp","bale","telegram"} or not target or not content:
                skipped+=1; continue
            local_id=str(item.get("local_id","")).strip()
            if local_id and len(local_id) > 128:
                skipped += 1; continue
            # Never allow a user to attach a report to another user's message.
            if message_id is not None:
                owned = conn.execute("SELECT 1 FROM messages WHERE id=? AND user_id=?", (message_id, g.user_id)).fetchone()
                if not owned:
                    skipped += 1; continue
            detail_out=detail
            try:
                conn.execute("INSERT INTO send_logs(local_id,message_id,user_id,channel,group_name,content,status,error_message,sent_at) VALUES(?,?,?,?,?,?,?,?,?)",(local_id or None,message_id,g.user_id,channel,target,content,status,detail_out,sent_at))
            except sqlite3.IntegrityError:
                skipped += 1
                continue
            saved+=1
        except (TypeError,ValueError):
            skipped+=1
    conn.commit()
    return jsonify(ok=True,saved=saved,skipped=skipped),201

@app.get("/api/reports/send-logs")
@auth_required
def user_send_logs():
    conn=db()
    limit=max(1,min(500,int(request.args.get("limit",500) or 500)))
    rows=conn.execute("""SELECT id,message_id,channel,group_name target,content,status,error_message,sent_at
        FROM send_logs WHERE user_id=? ORDER BY id DESC LIMIT ?""",(g.user_id,limit)).fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.delete("/api/reports/send-logs")
@auth_required
def clear_user_send_logs():
    cur=db().execute("DELETE FROM send_logs WHERE user_id=?",(g.user_id,)); db().commit()
    return jsonify(ok=True,deleted=cur.rowcount)

@app.get("/api/admin/reports/send-logs")
@auth_required
def admin_send_logs():
    if g.role != "admin": return jsonify(error="دسترسی مدیر لازم است"),403
    conn=db(); limit=max(1,min(1000,int(request.args.get("limit",500) or 500)))
    rows=conn.execute("""SELECT l.id,l.user_id,l.message_id,l.channel,l.group_name target,l.content,l.status,l.error_message,l.sent_at,
        u.name user_name,u.mobile user_mobile FROM send_logs l JOIN users u ON u.id=l.user_id
        ORDER BY l.id DESC LIMIT ?""",(limit,)).fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.get("/api/admin/stats")
@auth_required
def admin_stats():
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    conn = db()
    users = conn.execute("SELECT COUNT(*) FROM users WHERE role='user'").fetchone()[0]
    active_users = conn.execute("SELECT COUNT(*) FROM users WHERE role='user' AND status='active'").fetchone()[0]
    pending = conn.execute("SELECT COUNT(*) FROM payments WHERE status='pending'").fetchone()[0]
    batches = conn.execute("SELECT COUNT(*) FROM message_batches").fetchone()[0]
    active_batches = conn.execute("SELECT COUNT(*) FROM message_batches WHERE status='active'").fetchone()[0]
    messages = conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    sent = conn.execute("SELECT COUNT(*) FROM send_logs WHERE status='success'").fetchone()[0]
    failed = conn.execute("SELECT COUNT(*) FROM send_logs WHERE status!='success'").fetchone()[0]
    return jsonify(users=users, active_users=active_users, pending_payments=pending, batches=batches, active_batches=active_batches, messages=messages, sent=sent, failed=failed)

@app.get("/api/admin/batches")
@auth_required
def admin_batches():
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    rows = db().execute("""SELECT b.id,b.user_id,b.channel,b.schedule_type,b.schedule_time,b.interval_seconds,b.status,b.created_at,u.name user_name,u.mobile user_mobile,
        (SELECT COUNT(*) FROM messages m WHERE m.batch_id=b.id) message_count
        FROM message_batches b JOIN users u ON u.id=b.user_id ORDER BY b.id DESC LIMIT 500""").fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.get("/api/admin/users")
@auth_required
def admin_users():
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    rows = db().execute("""SELECT u.id,u.name,u.mobile,u.role,u.status,u.created_at,
        (SELECT end_date FROM subscriptions s WHERE s.user_id=u.id ORDER BY s.end_date DESC LIMIT 1) AS subscription_end,
        (SELECT plan_type FROM subscriptions s WHERE s.user_id=u.id ORDER BY s.end_date DESC LIMIT 1) AS plan_type
        FROM users u ORDER BY u.id DESC""").fetchall()
    return jsonify(items=[dict(r) for r in rows])

@app.post("/api/admin/users/<int:user_id>/status")
@auth_required
def admin_user_status(user_id):
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    data = request.get_json(silent=True) or {}
    status = str(data.get("status", "")).lower()
    if status not in {"active", "disabled"}:
        return jsonify(error="وضعیت نامعتبر است"), 400
    if user_id == g.user_id and status != "active":
        return jsonify(error="مدیر نمی‌تواند حساب خودش را غیرفعال کند"), 400
    cur = db().execute("UPDATE users SET status=? WHERE id=?", (status, user_id))
    db().commit()
    if cur.rowcount == 0:
        return jsonify(error="کاربر پیدا نشد"), 404
    return jsonify(ok=True)

@app.post("/api/admin/users/<int:user_id>/subscription")
@auth_required
def admin_subscription(user_id):
    if g.role != "admin":
        return jsonify(error="دسترسی مدیر لازم است"), 403
    data = request.get_json(silent=True) or {}
    plan = str(data.get("plan_type", "monthly"))
    days = 365 if plan == "yearly" else 30
    now = datetime.now(timezone.utc)
    conn = db()
    current = conn.execute("SELECT end_date FROM subscriptions WHERE user_id=? AND status='active' ORDER BY end_date DESC LIMIT 1", (user_id,)).fetchone()
    start = now
    if current:
        try:
            existing_end = datetime.fromisoformat(current["end_date"])
            if existing_end > now:
                start = existing_end
        except ValueError:
            pass
    conn.execute(
        "INSERT INTO subscriptions(user_id,plan_type,start_date,end_date,status) VALUES(?,?,?,?,?)",
        (user_id, plan, start.isoformat(), (start + timedelta(days=days)).isoformat(), "active"),
    )
    conn.commit()
    return jsonify(ok=True)

if __name__ == "__main__":
    with app.app_context():
        init_db()
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")), debug=False)
