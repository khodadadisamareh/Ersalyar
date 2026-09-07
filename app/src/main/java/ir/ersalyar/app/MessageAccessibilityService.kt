package ir.ersalyar.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.ArrayDeque

class MessageAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_RUN_SCHEDULE = "ir.ersalyar.app.RUN_SCHEDULE"
        const val ACTION_SCAN_GROUPS = "ir.ersalyar.app.SCAN_GROUPS"
        const val ACTION_GROUPS_SCANNED = "ir.ersalyar.app.GROUPS_SCANNED"
        const val ACTION_SCAN_CONTACTS = "ir.ersalyar.app.SCAN_CONTACTS"
        const val ACTION_CONTACTS_SCANNED = "ir.ersalyar.app.CONTACTS_SCANNED"
        const val ACTION_SEND_DIRECT = "ir.ersalyar.app.SEND_DIRECT"
        const val ACTION_QUEUE_PAUSE = "ir.ersalyar.app.QUEUE_PAUSE"
        const val ACTION_QUEUE_RESUME = "ir.ersalyar.app.QUEUE_RESUME"
        const val ACTION_QUEUE_CLEAR = "ir.ersalyar.app.QUEUE_CLEAR"
        private var instance: MessageAccessibilityService? = null
        fun sendCommand(context: Context, intent: Intent) {
            instance?.handleCommand(intent) ?: context.sendBroadcast(intent.setPackage(context.packageName))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent != null) handleCommand(intent) }
    }
    private val queue = ArrayDeque<Pending>()
    private var sending = false
    private var paused = false
    private data class Pending(val scheduleId: Int, val channel: String, val target: String, val content: String, val kind: String = "group", val imageUri: String? = null, val imageUris: List<String> = emptyList(), var attempts: Int = 0)
    private var scanChannel: String? = null
    private var scanContacts = false

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        registerReceiver(receiver, IntentFilter().apply { addAction(ACTION_RUN_SCHEDULE); addAction(ACTION_SEND_DIRECT); addAction(ACTION_QUEUE_PAUSE); addAction(ACTION_QUEUE_RESUME); addAction(ACTION_QUEUE_CLEAR) }, RECEIVER_NOT_EXPORTED)
    }

    private fun handleCommand(intent: Intent) {
        when (intent.action) {
            ACTION_QUEUE_PAUSE -> { paused = true; return }
            ACTION_QUEUE_RESUME -> { paused = false; processQueue(); return }
            ACTION_QUEUE_CLEAR -> { queue.clear(); sending = false; return }
        }
        if (intent.action == ACTION_SCAN_GROUPS) {
            scanContacts = false
            scanChannel = intent.getStringExtra("channel")?.lowercase()
            launchMessenger(scanChannel ?: return)
            handler.postDelayed({ scanCurrentWindow() }, 1800)
            return
        }
        if (intent.action == ACTION_SCAN_CONTACTS) {
            scanContacts = true
            scanChannel = intent.getStringExtra("channel")?.lowercase()
            launchMessenger(scanChannel ?: return)
            handler.postDelayed({ scanCurrentWindow() }, 1800)
            return
        }
        if (intent.action == ACTION_SEND_DIRECT) {
            val channel = intent.getStringExtra("channel") ?: return
            val content = intent.getStringExtra("content") ?: return
            val recipients = intent.getStringArrayListExtra("recipients") ?: arrayListOf()
            val imageUri = intent.getStringExtra("image_uri")
            recipients.forEach { name -> queue.addLast(Pending(-1, channel, name, content, "recipient", imageUri, if (imageUri.isNullOrBlank()) emptyList() else listOf(imageUri))) }
            processQueue()
            return
        }
        if (intent.action != ACTION_RUN_SCHEDULE) return
        val id = intent.getIntExtra("schedule_id", -1)
        if (id < 0) return
        // The schedule data is synced into local storage by MainActivity.
        val p = ScheduleStore.load(this, id) ?: return
        val groups = if (p.groupNames.isEmpty()) listOf(p.groupName) else p.groupNames
        groups.forEach { group -> queue.addLast(Pending(id, p.channel, group, p.content, "group", p.imageUri, p.imageUris)) }
        p.recipientNames.forEach { recipient -> queue.addLast(Pending(id, p.channel, recipient, p.content, "recipient", p.imageUri, p.imageUris)) }
        processQueue()
    }

    private fun launchMessenger(channel: String) {
        val pkg = when (channel) {
            "whatsapp" -> "com.whatsapp"
            "telegram" -> "org.telegram.messenger"
            "bale" -> "ir.nasim"
            else -> return
        }
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        handler.postDelayed({ trySend() }, 1800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (sending) handler.postDelayed({ trySend() }, 500)
        if (scanChannel != null && event?.packageName != packageName) handler.postDelayed({ scanCurrentWindow() }, 350)
    }

    private fun processQueue() {
        if (paused || sending || queue.isEmpty()) return
        sending = true
        val p = queue.peekFirst()
        launchMessenger(p.channel)
    }

    private fun finishCurrent(success: Boolean, detail: String) {
        val p = if (queue.isEmpty()) null else queue.removeFirst()
        p?.let { SendLogStore.add(this, it.scheduleId, it.channel, it.target, it.content, success, detail) }
        sending = false
        handler.postDelayed({ processQueue() }, 450)
    }

    private fun scanCurrentWindow() {
        val channel = scanChannel ?: return
        val root = rootInActiveWindow ?: return
        val packageNameNow = root.packageName?.toString() ?: return
        val expected = when (channel) {
            "whatsapp" -> "com.whatsapp"
            "telegram" -> "org.telegram.messenger"
            "bale" -> "ir.nasim"
            else -> ""
        }
        if (packageNameNow != expected) return
        val candidates = linkedSetOf<String>()
        collectCandidateTexts(root, candidates)
        val cleaned = candidates.toList().take(200)
        val prefs = getSharedPreferences("group_scan", MODE_PRIVATE)
        prefs.edit().putStringSet(channel, cleaned.toSet()).apply()
        val action = if (scanContacts) ACTION_CONTACTS_SCANNED else ACTION_GROUPS_SCANNED
        sendBroadcast(Intent(action).setPackage(packageName).putExtra("channel", channel).putStringArrayListExtra(if (scanContacts) "contacts" else "groups", ArrayList(cleaned)))
        scanChannel = null
        scanContacts = false
    }

    private fun collectCandidateTexts(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        val text = (node.text ?: node.contentDescription)?.toString()?.trim() ?: ""
        val generic = setOf("Chats", "Chat", "Contacts", "Settings", "Calls", "New chat", "Search", "ارسال", "تنظیمات", "مخاطبین", "تماس‌ها", "گفتگوها", "جستجو", "خانه")
        if (node.isVisibleToUser && node.isClickable && text.length in 2..80 && text !in generic && !text.startsWith("+") && !text.matches(Regex("^[0-9 .,:\-]+$"))) out.add(text)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectCandidateTexts(it, out) }
    }

    private fun trySend() {
        if (!sending || queue.isEmpty()) return
        val p = queue.peekFirst()
        val root = rootInActiveWindow ?: return
        val groupNode = findText(root, p.target) ?: run {
            p.attempts++
            if (p.attempts >= 8) { finishCurrent(false, "مخاطب/گروه پیدا نشد پس از چند تلاش") ; return }
            handler.postDelayed({ trySend() }, 1200); return
        }
        groupNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            val r = rootInActiveWindow ?: run { handler.postDelayed({ trySend() }, 1200); return@postDelayed }
            val input = findEditable(r)
            if (input == null) { handler.postDelayed({ trySend() }, 1200); return@postDelayed }
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.content) }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            handler.postDelayed({
                val rr = rootInActiveWindow ?: run { handler.postDelayed({ trySend() }, 1000); return@postDelayed }
                val send = findSendButton(rr)
                if (send != null) {
                    if (p.imageUris.isNotEmpty() || !p.imageUri.isNullOrBlank()) {
                        runCatching {
                            val uris = if (p.imageUris.isNotEmpty()) p.imageUris.map { Uri.parse(it) } else listOf(Uri.parse(p.imageUri))
                            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_TEXT, p.content)
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                setPackage(when(p.channel){"whatsapp"->"com.whatsapp";"telegram"->"org.telegram.messenger";else->"ir.nasim"})
                            }
                            startActivity(share)
                        }
                        finishCurrent(true, "متن و ${p.imageUris.size.coerceAtLeast(1)} عکس آماده ارسال شد؛ ممکن است تأیید نهایی لازم باشد")
                    } else {
                        send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        finishCurrent(true, "ارسال با موفقیت انجام شد")
                    }
                } else {
                    handler.postDelayed({ trySend() }, 1000)
                }
            }, 500)
        }, 1000)
    }

    private fun findText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val exact = node.findAccessibilityNodeInfosByText(text)
        if (exact.isNotEmpty()) return exact.first()
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> findText(child, text)?.let { return it } }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> findEditable(child)?.let { return it } }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val names = listOf("ارسال", "Send", "ارسال پیام", "send")
        for (name in names) node.findAccessibilityNodeInfosByText(name).firstOrNull()?.let { return it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> findSendButton(child)?.let { return it } }
        return null
    }

    override fun onInterrupt() {}
    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; instance = null; super.onDestroy() }
}
