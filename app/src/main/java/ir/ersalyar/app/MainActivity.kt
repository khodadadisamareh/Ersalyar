package ir.ersalyar.app

import android.app.*
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

class MainActivity : AppCompatActivity() {
    private var selectedImageUri: Uri? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
        if (uri != null) toast("عکس انتخاب شد؛ همراه متن ارسال می‌شود.")
    }
    private var imagePickerTargetIndex = -1
    private val multiImagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (imagePickerTargetIndex >= 0) {
            val idx = imagePickerTargetIndex
            if (idx < pendingImagesPerText.size) {
                pendingImagesPerText[idx].clear()
                pendingImagesPerText[idx].addAll(uris.take(10).map(Uri::toString))
                pendingImageLabels.getOrNull(idx)?.text = if (uris.isEmpty()) "📷 بدون عکس" else "📷 ${uris.size} عکس انتخاب شد"
            }
        }
        imagePickerTargetIndex = -1
    }
    private val pendingImagesPerText = mutableListOf<MutableList<String>>()
    private val pendingImageLabels = mutableListOf<TextView>()
    private lateinit var session: Session
    private val api get() = ApiClient.api
    private val groupScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != MessageAccessibilityService.ACTION_GROUPS_SCANNED) return
            val channel = intent.getStringExtra("channel") ?: return
            val groups = intent.getStringArrayListExtra("groups") ?: arrayListOf()
            scannedGroups[channel] = groups
            toast("${groups.size} مورد از ${channelLabel(channel)} پیدا شد؛ موارد دلخواه را ذخیره کنید.")
        }
    }
    private var supportPollingJob: Job? = null
    private val scannedGroups = mutableMapOf<String, ArrayList<String>>()
    private val scannedContacts = mutableMapOf<String, ArrayList<String>>()
    private val contactScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != MessageAccessibilityService.ACTION_CONTACTS_SCANNED) return
            val channel = intent.getStringExtra("channel") ?: return
            val contacts = intent.getStringArrayListExtra("contacts") ?: arrayListOf()
            scannedContacts[channel] = contacts
            toast("${contacts.size} مورد از ${channelLabel(channel)} شناسایی شد؛ افراد موردنظر را ذخیره کنید.")
        }
    }
    private fun channelLabel(c:String)=when(c){"whatsapp"->"واتساپ";"bale"->"بله";else->"تلگرام"}

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceSecurity.init(applicationContext)
        if (!DeviceSecurity.releaseIntegrityOk()) {
            Toast.makeText(this, "نسخه برنامه معتبر نیست یا دستکاری شده است.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        registerReceiver(groupScanReceiver, IntentFilter(MessageAccessibilityService.ACTION_GROUPS_SCANNED), RECEIVER_NOT_EXPORTED)
        registerReceiver(contactScanReceiver, IntentFilter(MessageAccessibilityService.ACTION_CONTACTS_SCANNED), RECEIVER_NOT_EXPORTED)
        session = Session(this)
        if (session.token == null) showLogin() else showDashboard()
    }

    override fun onDestroy() { supportPollingJob?.cancel(); runCatching { unregisterReceiver(groupScanReceiver) }; runCatching { unregisterReceiver(contactScanReceiver) }; super.onDestroy() }

    private fun layout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        setPadding(24, 24, 24, 24)
        layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 10, 0, 24)
    }

    private fun input(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 17f
        if (password) inputType = 0x81
        setPadding(12, 12, 12, 12)
    }

    private fun btn(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun showLogin() {
        val root = layout()
        root.addView(header("ارسال‌یار 👋"))
        root.addView(TextView(this).apply { text = "ورود به حساب کاربری"; textSize = 18f })

        val mobile = input("شماره موبایل")
        val password = input("رمز عبور", true)
        root.addView(mobile); root.addView(password)

        root.addView(btn("ورود") {
            lifecycleScope.launch {
                try {
                    val r = api.login(AuthRequest(mobile.text.toString(), password.text.toString()))
                    session.token = r.token
                    showDashboard()
                } catch (e: Exception) {
                    toast("ورود ناموفق: ${e.message ?: "خطای ارتباط با سرور"}")
                }
            }
        })
        root.addView(btn("ثبت‌نام") { showRegister() })
        root.addView(TextView(this).apply { text = "پشتیبانی: ${session.supportPhone}\nطراحی توسط گروه A.P"; gravity = Gravity.CENTER; setPadding(0, 20, 0, 8) })
        setContentView(root)
    }

    private fun showRegister() {
        val root = layout()
        root.addView(header("ساخت حساب"))
        val name = input("نام و نام خانوادگی")
        val mobile = input("شماره موبایل")
        val password = input("رمز عبور حداقل ۶ کاراکتر", true)
        root.addView(name); root.addView(mobile); root.addView(password)
        root.addView(btn("ثبت‌نام و شروع ۷ روز آزمایشی") {
            lifecycleScope.launch {
                try {
                    val r = api.register(AuthRequest(mobile.text.toString(), password.text.toString(), name.text.toString()))
                    session.token = r.token
                    showDashboard()
                } catch (e: Exception) {
                    toast("ثبت‌نام ناموفق: ${e.message ?: "خطای ارتباط با سرور"}")
                }
            }
        })
        root.addView(btn("بازگشت") { showLogin() })
        setContentView(root)
    }

    private fun auth() = "Bearer ${session.token}"

    private fun showDashboard() {
        supportPollingJob?.cancel(); supportPollingJob = null
        val root = layout()
        root.addView(header("ارسال‌یار"))
        val info = TextView(this).apply { text = "در حال دریافت اطلاعات..."; textSize = 17f }
        root.addView(info)

        lifecycleScope.launch {
            try {
                val me = api.me(auth())
                try { session.supportPhone = api.paymentSettings(auth()).settings?.supportPhone ?: session.supportPhone; supportCallBtn.text = "☎ تماس با پشتیبانی: ${session.supportPhone}" } catch (_: Exception) {}
                val sub = me.subscription
                session.subscriptionEnd = sub?.endDate
                session.subscriptionActive = me.subscriptionActive
                val state = if (me.subscriptionActive) "فعال" else "منقضی / غیرفعال"
                info.text = "سلام ${me.user?.name ?: ""} 👋\nاشتراک: ${sub?.planType ?: "نامشخص"} ($state)\nپایان: ${sub?.endDate ?: "-"}\nنوبت‌های فعال: ${me.dailySlotsUsed} از ${me.planDailySlots}\nباقی‌مانده: ${me.dailySlotsRemaining}"
                if (!me.subscriptionActive) {
                    try {
                        api.schedules(auth()).items.forEach { LocalScheduler.cancel(this@MainActivity, it.id) }
                        api.messageBatches(auth()).items.forEach { it.contents.forEach { c -> LocalScheduler.cancel(this@MainActivity, c.id) } }
                        ScheduleStore.clearAll(this@MainActivity)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                info.text = "ارتباط با سرور برقرار نشد."
            }
        }

        root.addView(btn("📚 دفترچه گروه‌ها") { showGroupBook() })
        root.addView(btn("👤 دفترچه مخاطبین") { showRecipientBook() })
        root.addView(btn("📋 لیست‌های آماده مخاطبین") { showRecipientLists() })
        root.addView(btn("💬 ارسال پیام به مخاطبین") { showDirectMessage() })
        root.addView(btn("＋ افزودن پیام جدید") { showNewSchedule() })
        root.addView(btn("پیام‌های زمان‌بندی‌شده") { showSchedules() })
        root.addView(btn("📦 مدیریت مجموعه پیام‌ها") { showBatches() })
        root.addView(btn("📊 گزارش ارسال‌ها") { showSendLogs() })
        root.addView(btn("⏯ مدیریت صف ارسال") { showSendQueue() })
        root.addView(btn("⚙ فعال‌سازی ارسال خودکار") {
            startActivity(android.content.Intent("android.settings.ACCESSIBILITY_SETTINGS"))
        })
        root.addView(btn("خرید / تمدید اشتراک") { showSubscription() })
        root.addView(btn("💬 چت آنلاین با پشتیبانی") { showSupportChat() })
        val supportCallBtn = btn("☎ تماس با پشتیبانی: ${session.supportPhone}") {
            startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${session.supportPhone}")))
        }
        root.addView(supportCallBtn)
        root.addView(TextView(this).apply {
            text = "طراحی توسط گروه A.P"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 8)
        })
        root.addView(btn("خروج از حساب") {
            session.clear()
            showLogin()
        })
        setContentView(root)
    }


    private fun showSupportChat() {
        val root = layout()
        root.addView(header("پشتیبانی آنلاین 💬"))
        root.addView(TextView(this).apply {
            text = "پیام خود را ارسال کنید؛ پاسخ پشتیبانی در همین گفتگو نمایش داده می‌شود."
            textSize = 15f
        })
        val messagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(messagesBox) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val message = input("پیام شما..."); message.minLines = 2
        root.addView(message)
        val status = TextView(this).apply { textSize = 13f }
        root.addView(status)

        fun render(items: List<SupportMessage>) {
            messagesBox.removeAllViews()
            items.forEach { m ->
                val title = if (m.senderRole == "admin") "پشتیبانی" else "شما"
                messagesBox.addView(TextView(this).apply {
                    text = "$title — ${m.createdAt}\n${m.content}"
                    textSize = 16f
                    setPadding(14, 14, 14, 14)
                })
            }
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        fun refresh() {
            lifecycleScope.launch {
                try { render(api.supportThread(auth()).items); status.text = "آنلاین — آخرین به‌روزرسانی انجام شد" }
                catch (e: Exception) { status.text = "ارتباط با پشتیبانی برقرار نشد." }
            }
        }
        root.addView(btn("✉️ ارسال پیام") {
            val text = message.text.toString().trim()
            if (text.isEmpty()) { toast("پیام را وارد کنید."); return@btn }
            lifecycleScope.launch {
                try {
                    api.supportMessage(auth(), SupportMessageRequest(text))
                    message.text.clear(); refresh()
                } catch (e: Exception) { toast("ارسال پیام ناموفق: ${e.message ?: "خطای سرور"}") }
            }
        })
        root.addView(btn("🔄 به‌روزرسانی") { refresh() })
        val supportCallBtn = btn("☎ تماس با پشتیبانی: ${session.supportPhone}") {
            startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${session.supportPhone}")))
        }
        root.addView(supportCallBtn)
        root.addView(TextView(this).apply { text = "طراحی توسط گروه A.P"; gravity = Gravity.CENTER; setPadding(0, 12, 0, 12) })
        root.addView(btn("بازگشت") { showDashboard() })
        setContentView(root)
        refresh()
        supportPollingJob?.cancel()
        supportPollingJob = lifecycleScope.launch {
            while (true) {
                delay(5000)
                if (!isFinishing) refresh()
            }
        }
    }

    private fun showGroupBook() {
        val root=layout(); root.addView(header("دفترچه گروه‌ها 📚"))
        val channelCodes=arrayOf("whatsapp","bale","telegram")
        val labels=arrayOf("واتساپ","بله","تلگرام")
        val spinner=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels) }
        val name=input("نام گروه")
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(spinner); root.addView(name)
        root.addView(btn("🔎 شناسایی خودکار گروه‌ها") {
            val channel = channelCodes[spinner.selectedItemPosition]
            try {
                MessageAccessibilityService.sendCommand(this, Intent(MessageAccessibilityService.ACTION_SCAN_GROUPS).setPackage(packageName).putExtra("channel", channel))
                toast("پیام‌رسان باز شد؛ صفحه فهرست گفتگوها را باز نگه دارید تا گروه‌ها شناسایی شوند.")
            } catch (e: Exception) { toast("برای شناسایی خودکار، ابتدا دسترسی ارسال خودکار را فعال کنید.") }
        })
        root.addView(btn("📥 نمایش گروه‌های شناسایی‌شده") {
            val channel = channelCodes[spinner.selectedItemPosition]
            val items = scannedGroups[channel].orEmpty()
            if (items.isEmpty()) toast("هنوز گروهی شناسایی نشده است.") else showScannedGroups(channel, items)
        })
        root.addView(btn("＋ ذخیره گروه") {
            val n=name.text.toString().trim(); if(n.isEmpty()){toast("نام گروه را وارد کنید.");return@btn}
            lifecycleScope.launch { try { api.addGroup(auth(),GroupRequest(n,channelCodes[spinner.selectedItemPosition])); name.text.clear(); loadGroups(list) } catch(e:Exception){toast("ثبت گروه ناموفق: ${e.message ?: "خطای سرور"}")} }
        })
        root.addView(list)
        root.addView(TextView(this).apply{text="تعداد گروه‌ها محدود نیست؛ می‌توانید هر تعداد گروه که نیاز دارید ذخیره کنید."; textSize=14f; setPadding(0,12,0,12)})
        root.addView(btn("بازگشت") { showDashboard() })
        setContentView(root); loadGroups(list)
    }

    private fun showScannedGroups(channel: String, items: List<String>) {
        val root = layout(); root.addView(header("گروه‌های شناسایی‌شده — ${channelLabel(channel)}"))
        root.addView(TextView(this).apply { text = "موارد را بررسی کنید؛ سپس فقط گروه‌های موردنظر را به دفترچه اضافه کنید."; textSize = 15f })
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = mutableListOf<Pair<String, CheckBox>>()
        items.distinct().forEach { name ->
            val cb = CheckBox(this).apply { text = name; isChecked = true; textSize = 16f }
            checks.add(name to cb); container.addView(cb)
        }
        root.addView(container)
        root.addView(btn("💾 ذخیره موارد انتخاب‌شده") {
            lifecycleScope.launch {
                var saved = 0
                checks.filter { it.second.isChecked }.forEach { (name, _) ->
                    runCatching { api.addGroup(auth(), GroupRequest(name, channel)); saved++ }
                }
                toast("$saved گروه به دفترچه اضافه شد.")
                showGroupBook()
            }
        })
        root.addView(btn("بازگشت") { showGroupBook() })
        setContentView(root)
    }

    private fun loadGroups(list: LinearLayout) {
        lifecycleScope.launch { try { val items=api.groups(auth()).items; list.removeAllViews(); if(items.isEmpty()){list.addView(TextView(this@MainActivity).apply{text="هنوز گروهی ذخیره نشده است."})}; items.forEach { g -> val row=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL}; row.addView(TextView(this@MainActivity).apply{text="${g.name} — ${when(g.channel){"whatsapp"->"واتساپ";"bale"->"بله";else->"تلگرام"}}"; textSize=16f; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}); row.addView(btn("حذف"){ lifecycleScope.launch{try{api.deleteGroup(auth(),g.id);loadGroups(list)}catch(e:Exception){toast("حذف ناموفق")}}}); list.addView(row)}} catch(e:Exception){list.addView(TextView(this@MainActivity).apply{text="خطا در دریافت دفترچه گروه‌ها."})} }
    }

    private fun showRecipientBook() {
        val root=layout(); root.addView(header("دفترچه مخاطبین 👤"))
        root.addView(TextView(this).apply { text="افراد را یک‌بار ذخیره کنید؛ بعداً برای هر پیام فقط از همین فهرست انتخاب می‌کنید. تعداد مخاطبین محدود نیست."; textSize=15f })
        val channels=arrayOf("whatsapp","bale","telegram"); val labels=arrayOf("واتساپ","بله","تلگرام")
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)}
        val name=input("نام مخاطب دقیقاً مثل حساب پیام‌رسان")
        root.addView(spinner); root.addView(name)
        root.addView(btn("🔎 شناسایی مخاطبین از اکانت") {
            val channel=channels[spinner.selectedItemPosition]
            try { MessageAccessibilityService.sendCommand(this, Intent(MessageAccessibilityService.ACTION_SCAN_CONTACTS).setPackage(packageName).putExtra("channel",channel)); toast("صفحه مخاطبین/گفتگوها را باز نگه دارید تا موارد شناسایی شوند.") }
            catch(e:Exception){toast("ابتدا دسترسی ارسال خودکار را فعال کنید.")}
        })
        root.addView(btn("📥 نمایش موارد شناسایی‌شده") {
            val channel=channels[spinner.selectedItemPosition]; val items=scannedContacts[channel].orEmpty()
            if(items.isEmpty()) toast("هنوز موردی شناسایی نشده است.") else showScannedContacts(channel,items)
        })
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; root.addView(list)
        root.addView(btn("＋ ذخیره مخاطب") {
            val n=name.text.toString().trim(); if(n.isEmpty()){toast("نام مخاطب را وارد کنید.");return@btn}
            lifecycleScope.launch{try{api.addRecipient(auth(),RecipientRequest(n,channels[spinner.selectedItemPosition]));name.text.clear();loadRecipients(list)}catch(e:Exception){toast("ثبت مخاطب ناموفق: ${e.message ?: "خطای سرور"}")}}
        })
        root.addView(btn("بازگشت"){showDashboard()}); setContentView(root); loadRecipients(list)
    }

    private fun showScannedContacts(channel:String, items:List<String>) {
        val root=layout(); root.addView(header("موارد شناسایی‌شده — ${channelLabel(channel)}"))
        root.addView(TextView(this).apply{text="توجه: این نسخه از نام‌های قابل‌مشاهده در صفحه پیام‌رسان استفاده می‌کند؛ قبل از ذخیره، موارد را بررسی کنید.";textSize=14f})
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; val checks=mutableListOf<Pair<String,CheckBox>>()
        items.distinct().forEach{name->val cb=CheckBox(this).apply{text=name;isChecked=true;textSize=16f};checks.add(name to cb);box.addView(cb)}
        root.addView(box)
        root.addView(btn("💾 ذخیره انتخاب‌شده‌ها"){lifecycleScope.launch{var saved=0;checks.filter{it.second.isChecked}.forEach{(n,_)->runCatching{api.addRecipient(auth(),RecipientRequest(n,channel));saved++}};toast("$saved مخاطب ذخیره شد.");showRecipientBook()}})
        root.addView(btn("بازگشت"){showRecipientBook()});setContentView(root)
    }

    private fun loadRecipients(list:LinearLayout) {
        lifecycleScope.launch{try{val items=api.recipients(auth()).items;list.removeAllViews();if(items.isEmpty())list.addView(TextView(this@MainActivity).apply{text="هنوز مخاطبی ذخیره نشده است."});items.forEach{r->val row=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};row.addView(TextView(this@MainActivity).apply{text="${r.name} — ${channelLabel(r.channel)}";textSize=16f;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)});row.addView(btn("حذف"){lifecycleScope.launch{try{api.deleteRecipient(auth(),r.id);loadRecipients(list)}catch(e:Exception){toast("حذف ناموفق")}}});list.addView(row)}}catch(e:Exception){list.addView(TextView(this@MainActivity).apply{text="خطا در دریافت دفترچه مخاطبین."})}}
    }

    private fun savedRecipientIds(channel:String): MutableSet<Int> {
        val raw=getSharedPreferences("recipient_selection", MODE_PRIVATE).getStringSet(channel, emptySet()).orEmpty()
        return raw.mapNotNull{it.toIntOrNull()}.toMutableSet()
    }
    private fun saveRecipientIds(channel:String, ids:Set<Int>) {
        getSharedPreferences("recipient_selection", MODE_PRIVATE).edit().putStringSet(channel, ids.map{it.toString()}.toSet()).apply()
    }

    private fun showRecipientLists() {
        val root=layout(); root.addView(header("لیست‌های آماده مخاطبین 📋"))
        root.addView(TextView(this).apply{text="مثلاً «مشتری‌ها»، «همکاران» یا «لیست تبلیغات». هر لیست را یک‌بار می‌سازی و بعد برای هر پیام با یک انتخاب استفاده می‌کنی.";textSize=15f})
        val channels=arrayOf("whatsapp","bale","telegram"); val labels=arrayOf("واتساپ","بله","تلگرام")
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)}; root.addView(spinner)
        val name=input("نام لیست، مثلاً مشتری‌ها"); root.addView(name)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; root.addView(list)
        fun load(){ lifecycleScope.launch{ try{ val items=api.recipientLists(auth()).items.filter{it.channel==channels[spinner.selectedItemPosition]}; list.removeAllViews(); if(items.isEmpty()) list.addView(TextView(this@MainActivity).apply{text="برای این پیام‌رسان هنوز لیستی ساخته نشده است.";textSize=15f}); items.forEach{l-> val row=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}; row.addView(TextView(this@MainActivity).apply{text="${l.name} — ${l.recipientCount} نفر";textSize=16f;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}); row.addView(btn("ویرایش"){showEditRecipientList(l.id)}); row.addView(btn("حذف"){AlertDialog.Builder(this@MainActivity).setTitle("حذف لیست").setMessage("فقط خود لیست حذف می‌شود؛ مخاطبین دفترچه باقی می‌مانند.").setNegativeButton("انصراف",null).setPositiveButton("حذف"){_,_->lifecycleScope.launch{runCatching{api.deleteRecipientList(auth(),l.id);load()}.onFailure{toast("حذف ناموفق")}}}.show()}); list.addView(row)} }catch(e:Exception){list.removeAllViews();list.addView(TextView(this@MainActivity).apply{text="خطا در دریافت لیست‌ها."})}} }
        root.addView(btn("＋ ساخت لیست جدید"){ val n=name.text.toString().trim(); if(n.isEmpty()){toast("نام لیست را وارد کنید.");return@btn}; lifecycleScope.launch{try{val r=api.addRecipientList(auth(),RecipientListRequest(n,channels[spinner.selectedItemPosition]));toast("لیست «${r.name}» ساخته شد. حالا اعضای آن را انتخاب کنید.");showEditRecipientList(r.id)}catch(e:Exception){toast("ساخت لیست ناموفق: ${e.message?:"خطای سرور"}")}}})
        root.addView(btn("بازگشت"){showDashboard()}); setContentView(root); load()
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){ };override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){load()}}
    }

    private fun showEditRecipientList(listId:Int){
        lifecycleScope.launch{ try{ val detail=api.recipientList(auth(),listId); val all=api.recipients(auth()).items.filter{it.channel==detail.list.channel}; val selected=detail.recipients.map{it.id}.toMutableSet(); val root=layout();root.addView(header("اعضای لیست: ${detail.list.name}"));root.addView(TextView(this@MainActivity).apply{text="اعضای این لیست را هر زمان خواستی اضافه یا حذف کن.";textSize=15f});val box=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL};all.forEach{r->box.addView(CheckBox(this@MainActivity).apply{text=r.name;textSize=16f;isChecked=selected.contains(r.id);setOnCheckedChangeListener{_,checked->if(checked)selected.add(r.id)else selected.remove(r.id)}})};root.addView(box);root.addView(btn("💾 ذخیره اعضای لیست"){lifecycleScope.launch{try{api.updateRecipientList(auth(),listId,RecipientListRequest(detail.list.name,detail.list.channel,selected.toList()));toast("لیست به‌روزرسانی شد.");showRecipientLists()}catch(e:Exception){toast("ذخیره ناموفق")}}});root.addView(btn("بازگشت"){showRecipientLists()});setContentView(root)}catch(e:Exception){toast("دریافت لیست ناموفق")}}
    }

    private fun showDirectMessage() {
        val root=layout(); root.addView(header("ارسال پیام به مخاطبین 💬"))
        root.addView(TextView(this).apply{text="مخاطبین را یک‌بار از دفترچه انتخاب کنید؛ انتخاب شما برای دفعات بعد ذخیره می‌شود و هر زمان خواستید می‌توانید اضافه/حذف کنید.";textSize=15f})
        val channels=arrayOf("whatsapp","bale","telegram"); val labels=arrayOf("واتساپ","بله","تلگرام")
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)};root.addView(spinner)
        root.addView(btn("📋 انتخاب یک لیست آماده"){lifecycleScope.launch{try{val lists=api.recipientLists(auth()).items.filter{it.channel==channels[spinner.selectedItemPosition]};if(lists.isEmpty()){toast("برای این پیام‌رسان لیستی ندارید.");return@launch};val names=lists.map{"${it.name} — ${it.recipientCount} نفر"}.toTypedArray();AlertDialog.Builder(this@MainActivity).setTitle("انتخاب لیست").setItems(names){_,which->lifecycleScope.launch{try{val d=api.recipientList(auth(),lists[which].id);val ids=d.recipients.map{it.id}.toMutableSet();saveRecipientIds(channels[spinner.selectedItemPosition],ids);toast("لیست «${d.list.name}» انتخاب شد.");showDirectMessage()}catch(e:Exception){toast("انتخاب لیست ناموفق")}}}.show()}catch(e:Exception){toast("دریافت لیست‌ها ناموفق")}}})
        val content=input("متن پیام");content.minLines=5;root.addView(content)
        val imageInfo = TextView(this).apply { text = "📷 بدون عکس"; textSize = 15f }; root.addView(imageInfo)
        root.addView(btn("📷 انتخاب عکس") { imagePicker.launch("image/*") })
        root.addView(btn("✖ حذف عکس") { selectedImageUri = null; imageInfo.text = "📷 بدون عکس" })
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val selected=mutableListOf<Int>();val count=TextView(this).apply{text="مخاطبین انتخاب‌شده: ۰";textSize=16f};root.addView(count);root.addView(box)
        fun render(items:List<RecipientItem>){box.removeAllViews();val valid=items.map{it.id}.toSet();selected.retainAll(valid);if(selected.isEmpty())selected.addAll(savedRecipientIds(channels[spinner.selectedItemPosition]).filter{valid.contains(it)});items.forEach{r->val cb=CheckBox(this).apply{text=r.name;textSize=16f;isChecked=selected.contains(r.id);setOnCheckedChangeListener{_,checked->if(checked&&!selected.contains(r.id))selected.add(r.id);if(!checked)selected.remove(r.id);saveRecipientIds(channels[spinner.selectedItemPosition],selected.toSet());count.text="مخاطبین انتخاب‌شده: ${selected.size}"}};box.addView(cb)};saveRecipientIds(channels[spinner.selectedItemPosition],selected.toSet());count.text="مخاطبین انتخاب‌شده: ${selected.size}"}
        lifecycleScope.launch{try{render(api.recipients(auth()).items.filter{it.channel==channels[spinner.selectedItemPosition]})}catch(_ :Exception){box.addView(TextView(this@MainActivity).apply{text="خطا در دریافت مخاطبین."})}}
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){ };override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){lifecycleScope.launch{runCatching{render(api.recipients(auth()).items.filter{it.channel==channels[pos]})}}}}
        root.addView(btn("✉️ ارسال اکنون"){val text=content.text.toString().trim();if(text.isEmpty()){toast("متن پیام را وارد کنید.");return@btn};if(selected.isEmpty()){toast("حداقل یک مخاطب انتخاب کنید.");return@btn};lifecycleScope.launch{try{val r=api.directMessage(auth(),DirectMessageRequest(text,channels[spinner.selectedItemPosition],selected.toList()));val names=ArrayList(r.recipients.map{it.name});MessageAccessibilityService.sendCommand(this@MainActivity,Intent(MessageAccessibilityService.ACTION_SEND_DIRECT).setPackage(packageName).putExtra("channel",r.channel).putExtra("content",r.content).putExtra("image_uri",selectedImageUri?.toString()).putStringArrayListExtra("recipients",names));toast("پیام برای ${names.size} مخاطب در صف ارسال قرار گرفت.")}catch(e:Exception){toast("ارسال ناموفق: ${e.message ?: "خطای سرور"}")}}})
        root.addView(btn("بازگشت"){showDashboard()});setContentView(root)
    }

    private fun showNewSchedule() {
        val root = layout()
        root.addView(header("ساخت مجموعه پیام"))
        root.addView(TextView(this).apply {
            text = "چند متن را یکجا بسازید و برای همه گروه‌های انتخاب‌شده ارسال کنید."
            textSize = 16f
        })

        val texts = mutableListOf<EditText>()
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pendingImagesPerText.clear(); pendingImageLabels.clear()
        fun addTextField() {
            val index = texts.size
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
            val e = input("متن پیام ${index + 1}...")
            e.minLines = 3
            texts.add(e)
            pendingImagesPerText.add(mutableListOf())
            val label = TextView(this).apply { text = "📷 بدون عکس"; textSize = 14f }
            pendingImageLabels.add(label)
            row.addView(e)
            row.addView(label)
            row.addView(btn("📷 انتخاب عکس برای متن ${index + 1}") { imagePickerTargetIndex = index; multiImagePicker.launch("image/*") })
            row.addView(btn("✖ حذف عکس‌های متن ${index + 1}") { pendingImagesPerText[index].clear(); label.text = "📷 بدون عکس" })
            textBox.addView(row)
        }
        addTextField()
        addTextField()
        root.addView(textBox)
        root.addView(btn("＋ افزودن متن دیگر") { addTextField() })

        val channels = arrayOf("whatsapp", "bale", "telegram")
        val labels = arrayOf("واتساپ", "بله", "تلگرام")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        }
        root.addView(spinner)

        val groupsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selectedGroups = mutableListOf<String>()
        val groupCount = TextView(this).apply { text = "گروه‌های انتخاب‌شده: ۰"; textSize = 16f }
        val savedGroups = mutableListOf<GroupItem>()
        root.addView(TextView(this).apply { text = "گروه‌ها را از دفترچه انتخاب کنید:"; textSize = 17f; setPadding(0, 12, 0, 6) })
        root.addView(groupCount)
        root.addView(groupsContainer)

        lifecycleScope.launch {
            try {
                val result = api.groups(auth()).items
                savedGroups.clear(); savedGroups.addAll(result)
                renderGroupChoices(groupsContainer, groupCount, selectedGroups, savedGroups, channels[spinner.selectedItemPosition])
            } catch (_: Exception) {
                groupsContainer.addView(TextView(this@MainActivity).apply { text = "خطا در دریافت دفترچه گروه‌ها." })
            }
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                renderGroupChoices(groupsContainer, groupCount, selectedGroups, savedGroups, channels[position])
            }
        }

        val time = input("ساعت شروع (مثلاً 10:30)")
        val repeat = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("یک‌بار", "روزانه", "هفتگی"))
        }
        root.addView(TextView(this).apply {
            text = "📷 برای هر متن می‌توانید تا ۱۰ عکس جداگانه انتخاب کنید. اگر عکس ندهید، پیام فقط متنی ارسال می‌شود."
            textSize = 14f
            setPadding(0, 8, 0, 8)
        })
        val interval = input("فاصله بین متن‌ها به ثانیه (۰ = همزمان)")
        interval.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        interval.setText("0")
        root.addView(time); root.addView(repeat); root.addView(interval)
        root.addView(TextView(this).apply {
            text = "مثال: متن‌های ۱ تا ۴ + فاصله ۰ ثانیه = همه متن‌ها در یک زمان برای همه گروه‌های انتخاب‌شده."
            textSize = 14f
            setPadding(0, 8, 0, 12)
        })

        root.addView(btn("ذخیره مجموعه زمان‌بندی") {
            val contents = texts.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
            val selected = selectedGroups.toList()
            val seconds = interval.text.toString().trim().toIntOrNull() ?: 0
            if (contents.isEmpty()) { toast("حداقل یک متن وارد کنید."); return@btn }
            if (selected.isEmpty()) { toast("حداقل یک گروه انتخاب کنید."); return@btn }
            if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time.text.toString().trim())) { toast("ساعت را به شکل HH:MM وارد کنید."); return@btn }
            lifecycleScope.launch {
                try {
                    val r = api.createBatch(auth(), BatchRequest(contents, channels[spinner.selectedItemPosition], selected, repeat.selectedItem.toString(), time.text.toString().trim(), seconds, imageUrisPerContent = pendingImagesPerText.map { it.toList() }))
                    r.ids.forEachIndexed { index, id ->
                        val content = contents[index]
                        val scheduleTime = java.time.LocalTime.parse(time.text.toString().trim()).plusSeconds(seconds.toLong() * index).toString().take(5)
                        val local = Schedule(id, content, pendingImagesPerText.getOrNull(index)?.firstOrNull(), pendingImagesPerText.getOrNull(index)?.toList() ?: emptyList(), channels[spinner.selectedItemPosition], selected.firstOrNull() ?: "", selected, selected.size, emptyList(), 0, repeat.selectedItem.toString(), scheduleTime, "", "active")
                        ScheduleStore.save(this@MainActivity, local)
                        LocalScheduler.schedule(this@MainActivity, id, scheduleTime)
                    }
                    toast("${r.messageCount} متن برای ${r.groupCount} گروه ذخیره شد.")
                    showDashboard()
                } catch (e: Exception) { toast("ذخیره ناموفق: ${e.message ?: "خطای سرور"}") }
            }
        })
        root.addView(btn("بازگشت") { showDashboard() })
        setContentView(root)
    }

    private fun renderGroupChoices(container: LinearLayout, count: TextView, selected: MutableList<String>, groups: List<GroupItem>, channel: String) {
        container.removeAllViews()
        val matching = groups.filter { it.channel == channel }
        if (matching.isEmpty()) {
            container.addView(TextView(this).apply { text = "برای این پیام‌رسان گروهی در دفترچه ثبت نشده است." })
            count.text = "گروه‌های انتخاب‌شده: ۰"
            return
        }
        matching.forEach { g ->
            val cb = CheckBox(this).apply {
                text = g.name
                textSize = 16f
                isChecked = selected.contains(g.name)
                setOnCheckedChangeListener { _, checked ->
                    if (checked && !selected.contains(g.name)) selected.add(g.name)
                    if (!checked) selected.remove(g.name)
                    count.text = "گروه‌های انتخاب‌شده: ${selected.size}"
                }
            }
            container.addView(cb)
        }
        count.text = "گروه‌های انتخاب‌شده: ${selected.size}"
    }

    private fun refreshGroups(container: LinearLayout, count: TextView, selected: MutableList<String>) {
        container.removeAllViews()
        count.text = "گروه‌های انتخاب‌شده: ${selected.size}"
        selected.forEachIndexed { index, name ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = "${index + 1}. $name"; textSize = 16f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(btn("حذف") { selected.remove(name); refreshGroups(container, count, selected) })
            container.addView(row)
        }
    }

    private fun showBatches() {
        val root = layout(); root.addView(header("مدیریت مجموعه پیام‌ها 📦"))
        root.addView(TextView(this).apply { text = "هر مجموعه شامل چند متن، چند گروه و یک برنامه زمانی مشترک است."; textSize = 15f })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        lifecycleScope.launch {
            try {
                val result=api.messageBatches(auth()); list.removeAllViews()
                result.items.forEach { b ->
                    val card=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(8,14,8,18)}
                    val status=if(b.status=="paused")"⏸ متوقف" else "▶ فعال"
                    card.addView(TextView(this@MainActivity).apply{text="📦 مجموعه #${b.id} | ${b.contents.size} متن | ${b.groupCount} گروه\n⏰ ${b.scheduleTime} | ${channelLabel(b.channel)} | $status\nفاصله متن‌ها: ${b.intervalSeconds} ثانیه\n${b.contents.joinToString("\n") { i -> "${i.batchOrder+1}. ${i.content}" }}";textSize=16f})
                    val actions=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL}
                    actions.addView(btn("✏ ویرایش"){showEditBatch(b)})
                    actions.addView(btn(if(b.status=="paused")"▶ فعال‌سازی" else "⏸ توقف"){ lifecycleScope.launch{try{val next=if(b.status=="paused")"active" else "paused";api.setBatchStatus(auth(),b.id,mapOf("status" to next)); b.contents.forEach{LocalScheduler.cancel(this@MainActivity,it.id)}; if(next=="active") b.contents.forEach{LocalScheduler.schedule(this@MainActivity,it.id,it.scheduleTime)};toast("مجموعه ${if(next=="active")"فعال" else "متوقف"} شد.");showBatches()}catch(e:Exception){toast("تغییر وضعیت ناموفق")}}})
                    actions.addView(btn("🗑 حذف"){AlertDialog.Builder(this@MainActivity).setTitle("حذف مجموعه").setMessage("همه متن‌های این مجموعه حذف می‌شوند. ادامه می‌دهید؟").setNegativeButton("انصراف",null).setPositiveButton("حذف"){_,_->lifecycleScope.launch{try{api.deleteBatch(auth(),b.id);b.contents.forEach{LocalScheduler.cancel(this@MainActivity,it.id);ScheduleStore.remove(this@MainActivity,it.id)};toast("مجموعه حذف شد.");showBatches()}catch(e:Exception){toast("حذف ناموفق")}}}.show()})
                    card.addView(actions);list.addView(card);list.addView(Space(this@MainActivity).apply{minimumHeight=10})
                }
                if(result.items.isEmpty()) list.addView(TextView(this@MainActivity).apply{text="هنوز مجموعه‌ای ثبت نشده است.";textSize=16f})
            } catch(e:Exception){list.addView(TextView(this@MainActivity).apply{text="خطا در دریافت مجموعه‌ها."})}
        }
        root.addView(btn("＋ مجموعه جدید"){showNewSchedule()}); root.addView(btn("بازگشت"){showDashboard()}); setContentView(root)
    }

    private fun showEditBatch(batch: MessageBatch) {
        val root=layout();root.addView(header("ویرایش مجموعه #${batch.id}"))
        val texts=mutableListOf<EditText>();val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(box)
        fun addField(value:String=""){val e=input("متن پیام ${texts.size+1}...");e.setText(value);e.minLines=3;texts.add(e);box.addView(e)}
        batch.contents.forEach{addField(it.content)}
        root.addView(btn("＋ افزودن متن"){addField()})
        val channels=arrayOf("whatsapp","bale","telegram");val labels=arrayOf("واتساپ","بله","تلگرام")
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels);setSelection(channels.indexOf(batch.channel).coerceAtLeast(0))};root.addView(spinner)
        val selected=batch.groupNames.toMutableList();val groups=mutableListOf<GroupItem>();val gc=TextView(this).apply{text="گروه‌های انتخاب‌شده: ${selected.size}"};val gbox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(gc);root.addView(gbox)
        fun render(){renderGroupChoices(gbox,gc,selected,groups,channels[spinner.selectedItemPosition])}
        lifecycleScope.launch{try{groups.addAll(api.groups(auth()).items);render()}catch(_:Exception){}}
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){ };override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){selected.retainAll{n->groups.any{it.channel==channels[pos]&&it.name==n}};render()}}
        val time=input("ساعت شروع (HH:MM)");time.setText(batch.scheduleTime);root.addView(time)
        val types=arrayOf("یک‌بار","روزانه","هفتگی");val repeat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,types);setSelection(types.indexOf(batch.scheduleType).coerceAtLeast(0))};root.addView(repeat)
        val interval=input("فاصله بین متن‌ها به ثانیه");interval.inputType=android.text.InputType.TYPE_CLASS_NUMBER;interval.setText(batch.intervalSeconds.toString());root.addView(interval)
        root.addView(btn("💾 ذخیره مجموعه"){val contents=texts.map{it.text.toString().trim()}.filter{it.isNotEmpty()};val t=time.text.toString().trim();val sec=interval.text.toString().toIntOrNull()?:0;if(contents.isEmpty()){toast("حداقل یک متن لازم است");return@btn};if(selected.isEmpty()){toast("حداقل یک گروه انتخاب کنید");return@btn};if(!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(t)){toast("ساعت نامعتبر است");return@btn};lifecycleScope.launch{try{val r=api.updateBatch(auth(),batch.id,BatchRequest(contents,channels[spinner.selectedItemPosition],selected,repeat.selectedItem.toString(),t,sec));batch.contents.forEach{LocalScheduler.cancel(this@MainActivity,it.id);ScheduleStore.remove(this@MainActivity,it.id)};val base=java.time.LocalTime.parse(t);r["ids"]?.let{idsObj->val ids=(idsObj as? List<*>)?.mapNotNull{(it as? Number)?.toInt()}?:emptyList();ids.forEachIndexed{idx,id->val st=base.plusSeconds(sec.toLong()*idx).toString().take(5);val local=Schedule(id,contents[idx],channels[spinner.selectedItemPosition],selected.firstOrNull() ?: "",selected,selected.size,emptyList(),0,repeat.selectedItem.toString(),st,"",batch.status);ScheduleStore.save(this@MainActivity,local);if(batch.status=="active")LocalScheduler.schedule(this@MainActivity,id,st)}};toast("مجموعه ویرایش شد.");showBatches()}catch(e:Exception){toast("ویرایش ناموفق: ${e.message?:"خطای سرور"}")}}})
        root.addView(btn("انصراف"){showBatches()});setContentView(root)
    }

    private fun showSchedules() {
        val root = layout()
        root.addView(header("مدیریت پیام‌های زمان‌بندی‌شده"))
        root.addView(TextView(this).apply {
            text = "از این بخش می‌توانید زمان‌بندی‌ها را ویرایش، متوقف، فعال یا حذف کنید."
            textSize = 15f
            setPadding(0, 0, 0, 12)
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        lifecycleScope.launch {
            try {
                val result = api.schedules(auth())
                list.removeAllViews()
                result.items.forEach { schedule ->
                    ScheduleStore.save(this@MainActivity, schedule)
                    if (schedule.status == "active") LocalScheduler.schedule(this@MainActivity, schedule.id, schedule.scheduleTime)
                    else LocalScheduler.cancel(this@MainActivity, schedule.id)
                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(8, 14, 8, 18)
                    }
                    val statusText = if (schedule.status == "paused") "⏸ متوقف" else "▶ فعال"
                    card.addView(TextView(this@MainActivity).apply {
                        text = "⏰ ${schedule.scheduleTime} | ${channelLabel(schedule.channel)} | $statusText\n${schedule.groupCount} گروه: ${schedule.groupNames.joinToString("، ")}\n\n${schedule.content}"
                        textSize = 16f
                    })
                    val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    actions.addView(btn("✏ ویرایش") { showEditSchedule(schedule) })
                    actions.addView(btn(if (schedule.status == "paused") "▶ فعال‌سازی" else "⏸ توقف") {
                        lifecycleScope.launch {
                            try {
                                val next = if (schedule.status == "paused") "active" else "paused"
                                api.setScheduleStatus(auth(), schedule.id, mapOf("status" to next))
                                if (next == "active") {
                                    ScheduleStore.save(this@MainActivity, schedule.copy(status = "active"))
                                    LocalScheduler.schedule(this@MainActivity, schedule.id, schedule.scheduleTime)
                                } else {
                                    ScheduleStore.save(this@MainActivity, schedule.copy(status = "paused"))
                                    LocalScheduler.cancel(this@MainActivity, schedule.id)
                                }
                                toast(if (next == "active") "زمان‌بندی فعال شد." else "زمان‌بندی متوقف شد.")
                                showSchedules()
                            } catch (e: Exception) { toast("تغییر وضعیت ناموفق: ${e.message ?: "خطای سرور"}") }
                        }
                    })
                    actions.addView(btn("🗑 حذف") {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("حذف زمان‌بندی")
                            .setMessage("این زمان‌بندی و تنظیم ارسال آن حذف می‌شود. ادامه می‌دهید؟")
                            .setNegativeButton("انصراف", null)
                            .setPositiveButton("حذف") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        api.deleteSchedule(auth(), schedule.id)
                                        LocalScheduler.cancel(this@MainActivity, schedule.id)
                                        ScheduleStore.remove(this@MainActivity, schedule.id)
                                        toast("زمان‌بندی حذف شد.")
                                        showSchedules()
                                    } catch (e: Exception) { toast("حذف ناموفق: ${e.message ?: "خطای سرور"}") }
                                }
                            }.show()
                    })
                    card.addView(actions)
                    list.addView(card)
                    list.addView(Space(this@MainActivity).apply { minimumHeight = 10 })
                }
                if (result.items.isEmpty()) list.addView(TextView(this@MainActivity).apply { text = "هنوز پیام زمان‌بندی‌شده‌ای ثبت نشده است."; textSize = 16f })
            } catch (e: Exception) {
                list.addView(TextView(this@MainActivity).apply { text = "خطا در دریافت زمان‌بندی‌ها." })
            }
        }
        root.addView(btn("＋ زمان‌بندی جدید") { showNewSchedule() })
        root.addView(btn("بازگشت") { showDashboard() })
        setContentView(root)
    }

    private fun showEditSchedule(schedule: Schedule) {
        val root = layout()
        root.addView(header("ویرایش زمان‌بندی #${schedule.id}"))
        val content = input("متن پیام")
        content.setText(schedule.content)
        content.minLines = 4
        root.addView(content)

        val channels = arrayOf("whatsapp", "bale", "telegram")
        val labels = arrayOf("واتساپ", "بله", "تلگرام")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(channels.indexOf(schedule.channel).coerceAtLeast(0))
        }
        root.addView(TextView(this).apply { text = "پیام‌رسان"; textSize = 16f; setPadding(0, 10, 0, 4) })
        root.addView(spinner)

        val groupsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selectedGroups = schedule.groupNames.toMutableList()
        val groupCount = TextView(this).apply { text = "گروه‌های انتخاب‌شده: ${selectedGroups.size}"; textSize = 16f; setPadding(0, 10, 0, 8) }
        val savedGroups = mutableListOf<GroupItem>()
        root.addView(groupCount)
        root.addView(groupsContainer)

        fun render() = renderGroupChoices(groupsContainer, groupCount, selectedGroups, savedGroups, channels[spinner.selectedItemPosition])
        lifecycleScope.launch {
            try { savedGroups.addAll(api.groups(auth()).items); render() }
            catch (_: Exception) { groupsContainer.addView(TextView(this@MainActivity).apply { text = "خطا در دریافت دفترچه گروه‌ها." }) }
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (savedGroups.isNotEmpty()) {
                    selectedGroups.retainAll { name -> savedGroups.any { it.channel == channels[position] && it.name == name } }
                }
                render()
            }
        }

        val time = input("ساعت (HH:MM)")
        time.setText(schedule.scheduleTime)
        val types = arrayOf("یک‌بار", "روزانه", "هفتگی")
        val repeat = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(schedule.scheduleType).coerceAtLeast(0))
        }
        root.addView(time); root.addView(repeat)
        root.addView(btn("💾 ذخیره تغییرات") {
            val text = content.text.toString().trim()
            val t = time.text.toString().trim()
            if (text.isEmpty()) { toast("متن پیام را وارد کنید."); return@btn }
            if (selectedGroups.isEmpty()) { toast("حداقل یک گروه انتخاب کنید."); return@btn }
            if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(t)) { toast("ساعت را به شکل HH:MM وارد کنید."); return@btn }
            lifecycleScope.launch {
                try {
                    val updated = ScheduleRequest(text, channels[spinner.selectedItemPosition], selectedGroups.toList(), repeat.selectedItem.toString(), t, schedule.weekdays, schedule.status)
                    api.updateSchedule(auth(), schedule.id, updated)
                    val local = schedule.copy(content = text, channel = channels[spinner.selectedItemPosition], groupName = selectedGroups.firstOrNull() ?: schedule.groupName, groupNames = selectedGroups.toList(), groupCount = selectedGroups.size, scheduleType = repeat.selectedItem.toString(), scheduleTime = t, status = schedule.status)
                    ScheduleStore.save(this@MainActivity, local)
                    LocalScheduler.cancel(this@MainActivity, schedule.id)
                    LocalScheduler.schedule(this@MainActivity, schedule.id, t)
                    toast("تغییرات ذخیره شد.")
                    showSchedules()
                } catch (e: Exception) { toast("ویرایش ناموفق: ${e.message ?: "خطای سرور"}") }
            }
        })
        root.addView(btn("انصراف") { showSchedules() })
        setContentView(root)
    }


    private fun showSendLogs() {
        val root = layout()
        root.addView(header("گزارش ارسال‌ها 📊"))
        val info = TextView(this).apply { text = "گزارش‌ها روی حساب شما هم ذخیره می‌شوند؛ بنابراین سابقه ارسال‌ها فقط به این گوشی وابسته نیست."; textSize = 14f }
        root.addView(info)
        val total = TextView(this).apply { textSize = 16f }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val channelSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("همه پیام‌رسان‌ها", "واتساپ", "بله", "تلگرام")) }
        val statusSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("همه وضعیت‌ها", "موفق", "ناموفق")) }
        filters.addView(channelSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filters.addView(statusSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(filters); root.addView(total)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        var serverLogs: List<ServerSendLog> = emptyList()
        fun render() {
            val channel = channelSpinner.selectedItemPosition; val status = statusSpinner.selectedItemPosition
            val channelCode = when(channel){1->"whatsapp";2->"bale";3->"telegram";else->null}
            val filtered = serverLogs.filter { (channelCode==null || it.channel==channelCode) && (status==0 || (status==1 && it.status=="success") || (status==2 && it.status!="success")) }
            val ok=filtered.count{it.status=="success"}; total.text="تعداد: ${filtered.size}  |  موفق: $ok  |  ناموفق: ${filtered.size-ok}"
            list.removeAllViews()
            filtered.forEach { log ->
                val kind=if(log.messageId==null) "ارسال مستقیم" else "زمان‌بندی #${log.messageId}"
                list.addView(TextView(this).apply { text="${if(log.status=="success")"✅ موفق" else "❌ ناموفق"} | ${channelLabel(log.channel)} | $kind\nمقصد: ${log.target}\nزمان: ${log.sentAt ?: "-"}\nمتن: ${log.content}\nنتیجه: ${log.errorMessage ?: "-"}"; textSize=15f; setPadding(10,14,10,14); setBackgroundResource(android.R.drawable.dialog_holo_light_frame) })
            }
            if(filtered.isEmpty()) list.addView(TextView(this).apply{text="هنوز گزارشی ثبت نشده است.";textSize=16f})
        }
        channelSpinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){ } override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){render()}}
        statusSpinner.onItemSelectedListener=channelSpinner.onItemSelectedListener
        root.addView(btn("🔄 همگام‌سازی گزارش‌ها") {
            lifecycleScope.launch {
                try {
                    val local=SendLogStore.list(this@MainActivity)
                    val fmt=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).apply { timeZone=java.util.TimeZone.getDefault() }
                    val payload=local.map{UploadSendLogItem(it.id, if(it.scheduleId>0) it.scheduleId else null, it.channel, it.group, it.content, it.success, it.detail, fmt.format(java.util.Date(it.time)))}
                    if(payload.isNotEmpty()) api.uploadSendLogs(auth(),UploadSendLogsRequest(payload))
                    serverLogs=api.serverSendLogs(auth()).items; render(); toast("گزارش‌ها همگام شدند.")
                } catch(e:Exception){ toast("همگام‌سازی ناموفق: ${e.message ?: "خطای سرور"}") }
            }
        })
        root.addView(btn("📤 اشتراک‌گذاری گزارش") {
            val filtered=serverLogs; val text=buildString{appendLine("گزارش ارسال‌یار");appendLine("تعداد: ${filtered.size}");appendLine("موفق: ${filtered.count{it.status=="success"}}");appendLine("ناموفق: ${filtered.count{it.status!="success"}}\n");filtered.forEach{appendLine("${if(it.status=="success")"موفق" else "ناموفق"} | ${channelLabel(it.channel)} | ${it.target} | ${it.sentAt ?: "-"}");appendLine("${it.content} | ${it.errorMessage ?: "-"}\n")}}
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"اشتراک‌گذاری گزارش"))
        })
        root.addView(btn("🗑 پاک کردن گزارش حساب") {
            AlertDialog.Builder(this).setTitle("پاک کردن گزارش‌ها").setMessage("گزارش‌های ذخیره‌شده روی حساب پاک شوند؟").setNegativeButton("انصراف",null).setPositiveButton("پاک کن"){_,_->lifecycleScope.launch{try{api.clearServerSendLogs(auth());SendLogStore.clear(this@MainActivity);serverLogs=emptyList();render();toast("گزارش‌ها پاک شدند.")}catch(e:Exception){toast("پاک کردن ناموفق")}}}.show()
        })
        root.addView(btn("بازگشت") { showDashboard() }); setContentView(root); render()
        lifecycleScope.launch { runCatching { serverLogs=api.serverSendLogs(auth()).items; render() } }
    }

    private fun showSendQueue() {
        val root = layout()
        root.addView(header("مدیریت صف ارسال ⏯"))
        root.addView(TextView(this).apply {
            text = "صف ارسال برای زمانی است که چند گروه یا مخاطب پشت سر هم باید پیام دریافت کنند. می‌توانید ارسال را موقتاً متوقف یا دوباره فعال کنید."
            textSize = 15f
        })
        root.addView(btn("⏸ توقف موقت صف") {
            MessageAccessibilityService.sendCommand(this, Intent(MessageAccessibilityService.ACTION_QUEUE_PAUSE).setPackage(packageName))
            toast("صف ارسال متوقف شد. موارد باقی‌مانده حذف نمی‌شوند.")
        })
        root.addView(btn("▶ ادامه ارسال") {
            MessageAccessibilityService.sendCommand(this, Intent(MessageAccessibilityService.ACTION_QUEUE_RESUME).setPackage(packageName))
            toast("ارسال صف ادامه پیدا کرد.")
        })
        root.addView(btn("🗑 پاک کردن موارد باقی‌مانده") {
            AlertDialog.Builder(this).setTitle("پاک کردن صف").setMessage("فقط مواردی که هنوز ارسال نشده‌اند حذف می‌شوند.")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("پاک کردن") { _, _ ->
                    MessageAccessibilityService.sendCommand(this, Intent(MessageAccessibilityService.ACTION_QUEUE_CLEAR).setPackage(packageName))
                    toast("صف ارسال پاک شد.")
                }.show()
        })
        root.addView(btn("📊 مشاهده گزارش") { showSendLogs() })
        root.addView(btn("بازگشت") { showDashboard() })
        setContentView(root)
    }

    private fun showSubscription() {
        val root = layout()
        root.addView(header("خرید / تمدید اشتراک"))
        val info = TextView(this).apply { text = "در حال دریافت اطلاعات پرداخت..."; textSize = 17f }
        root.addView(info)
        lifecycleScope.launch {
            try {
                val settings = api.paymentSettings(auth()).settings
                if (settings == null) {
                    info.text = "تنظیمات پرداخت هنوز توسط مدیر تکمیل نشده است."
                } else {
                    session.supportPhone = settings.supportPhone
                    info.text = "💳 کارت مقصد:\n${settings.cardNumber}\n\nبه نام: ${settings.accountHolder}\n\nماهانه: ${settings.monthlyPrice} تومان\nسالانه: ${settings.yearlyPrice} تومان\n\nپس از واریز، گزینه ثبت پرداخت را بزنید."
                    root.addView(btn("ثبت پرداخت ماهانه") { showPaymentForm("monthly", settings.monthlyPrice) }, root.indexOfChild(info) + 1)
                    root.addView(btn("ثبت پرداخت سالانه") { showPaymentForm("yearly", settings.yearlyPrice) }, root.indexOfChild(info) + 2)
                    root.addView(btn("سوابق پرداخت") { showPayments() }, root.indexOfChild(info) + 3)
                }
            } catch (e: Exception) {
                info.text = "خطا در دریافت اطلاعات پرداخت."
            }
        }
        root.addView(btn("بازگشت") { showDashboard() })
        setContentView(root)
    }

    private fun showPaymentForm(plan: String, amount: Long) {
        val root = layout()
        root.addView(header("ثبت پرداخت کارت‌به‌کارت"))
        val amountInfo = TextView(this).apply { text = "مبلغ: $amount تومان\n\nشماره پیگیری / کد رهگیری واریز را وارد کنید:"; textSize = 17f }
        root.addView(amountInfo)
        val reference = input("شماره پیگیری واریز")
        val coupon = input("کد تخفیف (اختیاری)")
        root.addView(coupon)
        root.addView(btn("اعمال کد تخفیف") {
            val code=coupon.text.toString().trim()
            if(code.isEmpty()){ toast("کد تخفیف را وارد کنید."); return@btn }
            lifecycleScope.launch { try { val d=api.validateDiscount(auth(), DiscountValidationRequest(plan,code)); amountInfo.text="مبلغ اصلی: $amount تومان\nتخفیف: ${d.discountAmount} تومان (${d.percent}%)\nمبلغ نهایی: ${d.finalAmount} تومان\n\nشماره پیگیری / کد رهگیری واریز را وارد کنید:"; toast("کد تخفیف با موفقیت اعمال شد.") } catch(e:Exception){ toast(e.message ?: "کد تخفیف معتبر نیست") } }
        })
        val receipt = input("نام فایل رسید (اختیاری)")
        root.addView(reference); root.addView(receipt)
        root.addView(TextView(this).apply { text = "فعلاً تصویر رسید در نسخه بعدی به‌صورت فایل ارسال می‌شود؛ نام فایل را می‌توانید ثبت کنید."; textSize = 14f })
        root.addView(btn("ثبت پرداخت") {
            if (reference.text.toString().trim().isEmpty()) { toast("شماره پیگیری را وارد کنید."); return@btn }
            lifecycleScope.launch {
                try {
                    val r = api.submitPayment(auth(), PaymentSubmitRequest(plan, reference.text.toString().trim(), receipt.text.toString().trim().ifEmpty { null }, coupon.text.toString().trim().ifEmpty { null }))
                    toast("پرداخت #${r.paymentId} ثبت شد و در انتظار تأیید مدیر است.")
                    showPayments()
                } catch (e: Exception) {
                    toast("ثبت پرداخت ناموفق است: ${e.message ?: "خطای سرور"}")
                }
            }
        })
        root.addView(btn("بازگشت") { showSubscription() })
        setContentView(root)
    }

    private fun showPayments() {
        val root = layout()
        root.addView(header("سوابق پرداخت"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        lifecycleScope.launch {
            try {
                val result = api.payments(auth())
                result.items.forEach { payment ->
                    val status = when (payment.status) { "pending" -> "در انتظار تأیید"; "approved" -> "تأیید شده"; "rejected" -> "رد شده"; else -> payment.status }
                    list.addView(TextView(this@MainActivity).apply {
                        text = "#${payment.id} | ${payment.planType} | ${payment.amount} تومان\nپیگیری: ${payment.payerReference}\nوضعیت: $status\n"
                        textSize = 16f; setPadding(8, 16, 8, 16)
                    })
                }
                if (result.items.isEmpty()) list.addView(TextView(this@MainActivity).apply { text = "هنوز پرداختی ثبت نشده است." })
            } catch (e: Exception) { list.addView(TextView(this@MainActivity).apply { text = "خطا در دریافت سوابق پرداخت." }) }
        }
        root.addView(btn("بازگشت") { showSubscription() })
        setContentView(root)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
