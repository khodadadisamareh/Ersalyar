PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
 id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT '', mobile TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'user', status TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, batch_id INTEGER, batch_order INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS subscriptions (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, plan_type TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS messages (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, content TEXT NOT NULL, image_uris TEXT NOT NULL DEFAULT '[]', channel TEXT NOT NULL, group_name TEXT NOT NULL, schedule_type TEXT NOT NULL, schedule_time TEXT NOT NULL, weekdays TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, batch_id INTEGER, batch_order INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS message_batches (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 channel TEXT NOT NULL,
 schedule_type TEXT NOT NULL,
 schedule_time TEXT NOT NULL,
 weekdays TEXT NOT NULL DEFAULT '',
 interval_seconds INTEGER NOT NULL DEFAULT 0,
 status TEXT NOT NULL DEFAULT 'active',
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_batches_user ON message_batches(user_id);
CREATE TABLE IF NOT EXISTS payment_settings (
 id INTEGER PRIMARY KEY CHECK (id=1), card_number TEXT NOT NULL DEFAULT '', account_holder TEXT NOT NULL DEFAULT '', monthly_price INTEGER NOT NULL DEFAULT 0, yearly_price INTEGER NOT NULL DEFAULT 0, trial_days INTEGER NOT NULL DEFAULT 7, support_phone TEXT NOT NULL DEFAULT '09372544666', updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT OR IGNORE INTO payment_settings(id) VALUES(1);
CREATE TABLE IF NOT EXISTS discount_codes (
 id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, percent INTEGER NOT NULL, max_uses INTEGER NOT NULL DEFAULT 0, used_count INTEGER NOT NULL DEFAULT 0, expires_at TEXT, active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS payments (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, plan_type TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, destination_card TEXT NOT NULL DEFAULT '', account_holder TEXT NOT NULL DEFAULT '', payer_reference TEXT NOT NULL DEFAULT '', receipt_name TEXT, status TEXT NOT NULL DEFAULT 'pending', admin_note TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TEXT, paid_at TEXT, coupon_code TEXT, original_amount INTEGER NOT NULL DEFAULT 0, discount_amount INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS send_logs (
 id INTEGER PRIMARY KEY AUTOINCREMENT, local_id TEXT, message_id INTEGER REFERENCES messages(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, channel TEXT NOT NULL, group_name TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, error_message TEXT, sent_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_messages_user ON messages(user_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_user ON subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_send_logs_user ON send_logs(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_send_logs_user_local_id ON send_logs(user_id,local_id) WHERE local_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payments_user ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);

CREATE TABLE IF NOT EXISTS message_groups (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 message_id INTEGER REFERENCES messages(id) ON DELETE CASCADE,
 group_name TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_message_groups_message ON message_groups(message_id);
CREATE INDEX IF NOT EXISTS idx_message_groups_name ON message_groups(group_name);

CREATE TABLE IF NOT EXISTS groups (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name TEXT NOT NULL,
 channel TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,name,channel)
);
CREATE INDEX IF NOT EXISTS idx_groups_user ON groups(user_id);

CREATE INDEX IF NOT EXISTS idx_messages_batch ON messages(batch_id);

CREATE TABLE IF NOT EXISTS group_limits (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 channel TEXT NOT NULL,
 group_name TEXT NOT NULL,
 max_slots_per_day INTEGER NOT NULL DEFAULT 3,
 updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,channel,group_name)
);
CREATE INDEX IF NOT EXISTS idx_group_limits_user ON group_limits(user_id);

CREATE TABLE IF NOT EXISTS plan_limits (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 plan_type TEXT NOT NULL UNIQUE,
 max_slots_per_day INTEGER NOT NULL DEFAULT 3,
 updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT OR IGNORE INTO plan_limits(plan_type,max_slots_per_day) VALUES ('trial',2),('monthly',5),('yearly',10);
CREATE INDEX IF NOT EXISTS idx_plan_limits_type ON plan_limits(plan_type);


-- v1.7: persistent direct-message recipients. A recipient is selected once and
-- can be reused for many messages; users may add/remove recipients at any time.
CREATE TABLE IF NOT EXISTS recipients (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 channel TEXT NOT NULL,
 name TEXT NOT NULL,
 target_key TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,channel,name)
);
CREATE INDEX IF NOT EXISTS idx_recipients_user ON recipients(user_id);
CREATE INDEX IF NOT EXISTS idx_recipients_channel ON recipients(user_id,channel);

CREATE TABLE IF NOT EXISTS message_recipients (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 message_id INTEGER REFERENCES messages(id) ON DELETE CASCADE,
 recipient_name TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_message_recipients_message ON message_recipients(message_id);

CREATE TABLE IF NOT EXISTS daily_slot_usage (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 usage_date TEXT NOT NULL,
 direct_sends INTEGER NOT NULL DEFAULT 0,
 UNIQUE(user_id,usage_date)
);
CREATE INDEX IF NOT EXISTS idx_daily_slot_usage_user_date ON daily_slot_usage(user_id,usage_date);


-- v1.8: reusable recipient lists. Lists are user-owned and can be reused for many messages.
CREATE TABLE IF NOT EXISTS recipient_lists (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name TEXT NOT NULL,
 channel TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,name,channel)
);
CREATE INDEX IF NOT EXISTS idx_recipient_lists_user ON recipient_lists(user_id);

CREATE TABLE IF NOT EXISTS recipient_list_items (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 list_id INTEGER NOT NULL REFERENCES recipient_lists(id) ON DELETE CASCADE,
 recipient_id INTEGER NOT NULL REFERENCES recipients(id) ON DELETE CASCADE,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(list_id,recipient_id)
);
CREATE INDEX IF NOT EXISTS idx_recipient_list_items_list ON recipient_list_items(list_id);


-- v2.1: cloud/server-side send reports. Direct messages may have no message row.
CREATE INDEX IF NOT EXISTS idx_send_logs_sent_at ON send_logs(sent_at);


-- v2.2: support chat
CREATE TABLE IF NOT EXISTS support_threads (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 status TEXT NOT NULL DEFAULT 'open',
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_support_thread_user_open ON support_threads(user_id,status);
CREATE TABLE IF NOT EXISTS support_messages (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 thread_id INTEGER NOT NULL REFERENCES support_threads(id) ON DELETE CASCADE,
 sender_role TEXT NOT NULL,
 content TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_support_messages_thread ON support_messages(thread_id,id);

-- v2.6: media messages with multiple images per message.
-- image_uris stores a JSON array of local content URIs/identifiers; actual media stays on device.

-- v2.5: per-user installation binding. This does not replace Play Integrity.
CREATE TABLE IF NOT EXISTS user_devices (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 install_id TEXT NOT NULL,
 app_version TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id, install_id)
);
CREATE INDEX IF NOT EXISTS idx_user_devices_user ON user_devices(user_id);
