package ir.ersalyar.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
            instance?.handleCommand(intent)
                ?: context.sendBroadcast(intent.setPackage(context.packageName))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pending>()
    private var sending = false
    private var paused = false

    private data class Pending(
        val scheduleId: Int,
        val channel: String,
        val target: String,
        val content: String,
        val kind: String = "group",
        val imageUri: String? = null,
        val imageUris: List<String> = emptyList(),
        var attempts: Int = 0
    )

    private var scanChannel: String? = null
    private var scanContacts = false
    private var scanResults = linkedSetOf<String>()
    private var scanPass = 0
    private var scanToken = 0
    private var scanFoundRoot = false
    private var groupFilterActivated = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) handleCommand(intent)
        }
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ACTION_RUN_SCHEDULE)
                addAction(ACTION_SEND_DIRECT)
                addAction(ACTION_QUEUE_PAUSE)
                addAction(ACTION_QUEUE_RESUME)
                addAction(ACTION_QUEUE_CLEAR)
            },
            RECEIVER_NOT_EXPORTED
        )
    }

    private fun handleCommand(intent: Intent) {
        when (intent.action) {
            ACTION_QUEUE_PAUSE -> {
                paused = true
                return
            }
            ACTION_QUEUE_RESUME -> {
                paused = false
                processQueue()
                return
            }
            ACTION_QUEUE_CLEAR -> {
                queue.clear()
                sending = false
                return
            }
        }

        if (intent.action == ACTION_SCAN_GROUPS || intent.action == ACTION_SCAN_CONTACTS) {
            scanContacts = intent.action == ACTION_SCAN_CONTACTS
            scanChannel = intent.getStringExtra("channel")?.lowercase()
            if (scanChannel.isNullOrBlank()) return
            startScan(scanChannel!!)
            return
        }

        if (intent.action == ACTION_SEND_DIRECT) {
            val channel = intent.getStringExtra("channel") ?: return
            val content = intent.getStringExtra("content") ?: return
            val recipients =
                intent.getStringArrayListExtra("recipients") ?: arrayListOf()
            val imageUri = intent.getStringExtra("image_uri")

            recipients.forEach { name ->
                queue.addLast(
                    Pending(
                        -1, channel, name, content, "recipient",
                        imageUri,
                        if (imageUri.isNullOrBlank()) emptyList() else listOf(imageUri)
                    )
                )
            }
            processQueue()
            return
        }

        if (intent.action != ACTION_RUN_SCHEDULE) return

        val id = intent.getIntExtra("schedule_id", -1)
        if (id < 0) return
        val p = ScheduleStore.load(this, id) ?: return

        val groups = if (p.groupNames.isEmpty()) listOf(p.groupName) else p.groupNames
        groups.forEach { group ->
            queue.addLast(
                Pending(id, p.channel, group, p.content, "group", p.imageUri, p.imageUris)
            )
        }
        p.recipientNames.forEach { recipient ->
            queue.addLast(
                Pending(id, p.channel, recipient, p.content, "recipient", p.imageUri, p.imageUris)
            )
        }
        processQueue()
    }

    private fun packageFor(channel: String): String = when (channel) {
        "whatsapp" -> "com.whatsapp"
        "telegram" -> "org.telegram.messenger"
        "bale" -> "ir.nasim"
        else -> ""
    }

    private fun launchMessenger(channel: String) {
        val pkg = packageFor(channel)
        if (pkg.isBlank()) return
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        handler.postDelayed({ trySend() }, 1800)
    }

    private fun startScan(channel: String) {
        scanToken++
        val token = scanToken
        scanResults = linkedSetOf()
        scanPass = 0
        scanFoundRoot = false
        groupFilterActivated = false

        launchMessenger(channel)

        // The messenger list is populated asynchronously. Re-scan several times
        // while gently scrolling the conversation list.
        handler.postDelayed({ scanPass(token) }, 1800)
    }

    private fun scanPass(token: Int) {
        if (token != scanToken || scanChannel == null) return

        val root = rootInActiveWindow
        val channel = scanChannel ?: return
        if (root == null || root.packageName?.toString() != packageFor(channel)) {
            if (scanPass < 20) handler.postDelayed({ scanPass(token) }, 900)
            else finishScan()
            return
        }

        scanFoundRoot = true

        // For group scans, first try to activate the messenger's own
        // "Groups" filter. This is deliberately based on semantic labels
        // rather than screen coordinates, so it can work across phones,
        // resolutions, RTL/LTR layouts and different users.
        if (!scanContacts && !groupFilterActivated) {
            groupFilterActivated = activateGroupFilter(root, channel)
            if (groupFilterActivated) {
                // Give the messenger a moment to rebuild the filtered list.
                handler.postDelayed({ scanPass(token) }, 700)
                return
            }
        }

        collectConversationRows(root, scanResults)

        scanPass++
        if (scanPass < 20) {
            scrollConversationList(root)
            handler.postDelayed({ scanPass(token) }, 850)
        } else {
            finishScan()
        }
    }

    private fun finishScan() {
        val channel = scanChannel ?: return
        val cleaned = scanResults
            .map { it.trim() }
            .filter { isUsefulConversationName(it) }
            .distinct()
            .take(500)

        getSharedPreferences("group_scan", MODE_PRIVATE)
            .edit()
            .putStringSet(channel, cleaned.toSet())
            .apply()

        val action = if (scanContacts) ACTION_CONTACTS_SCANNED else ACTION_GROUPS_SCANNED
        val key = if (scanContacts) "contacts" else "groups"

        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .putExtra("channel", channel)
                .putStringArrayListExtra(key, ArrayList(cleaned))
        )

        scanChannel = null
        scanContacts = false
    }

    private fun activateGroupFilter(
        root: AccessibilityNodeInfo,
        channel: String
    ): Boolean {
        val labels = when (channel) {
            "whatsapp" -> listOf(
                "Groups", "گروه‌ها", "گروه ها", "Group", "گروه"
            )
            "telegram" -> listOf(
                "Groups", "گروه‌ها", "گروه ها"
            )
            "bale" -> listOf(
                "Groups", "گروه‌ها", "گروه ها", "گروه"
            )
            else -> emptyList()
        }

        // Prefer a node whose resource id suggests it is a filter/tab.
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()

            val exact = labels.any {
                text.equals(it, ignoreCase = true) ||
                    desc.equals(it, ignoreCase = true)
            }

            if (node.isVisibleToUser && exact) {
                candidates.add(node)
                if (id.contains("filter") || id.contains("tab") || id.contains("chip")) {
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    var parent = node.parent
                    repeat(4) {
                        if (parent != null) {
                            if (parent.isVisibleToUser && parent.isClickable &&
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            ) return true
                            parent = parent.parent
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }

        // Fallback: click an exact semantic "Groups" label or its nearest
        // clickable parent. Never use screen coordinates.
        for (node in candidates) {
            if (node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true

            var parent = node.parent
            repeat(5) {
                if (parent != null) {
                    if (parent.isVisibleToUser && parent.isClickable &&
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) return true
                    parent = parent.parent
                }
            }
        }

        return false
    }

    private fun collectConversationRows(
        root: AccessibilityNodeInfo,
        out: MutableSet<String>
    ) {
        // Messenger conversation rows are usually clickable containers whose
        // descendants contain the conversation title. We prefer those titles
        // instead of harvesting every visible text on the screen (filters,
        // buttons, notification labels, message previews, etc.).
        collectFromClickableRows(root, out, 0)

        // Fallback: some versions expose the title TextView without a
        // clickable parent. Keep useful visible text as a secondary source.
        if (out.size < 3) collectVisibleTextFallback(root, out)
    }

    private fun collectFromClickableRows(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        depth: Int
    ) {
        if (node.isVisibleToUser && node.isClickable) {
            val rowTexts = linkedSetOf<String>()
            collectDirectTextChildren(node, rowTexts, 0)
            val own = node.text?.toString()?.trim().orEmpty()
            val ownDesc = node.contentDescription?.toString()?.trim().orEmpty()
            if (isUsefulConversationName(own)) rowTexts.add(own)
            if (isUsefulConversationName(ownDesc)) rowTexts.add(ownDesc)

            // Prefer the first useful title-like text from the row.
            rowTexts.firstOrNull { isUsefulConversationName(it) }?.let {
                out.add(it)
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectFromClickableRows(child, out, depth + 1)
            }
        }
    }

    private fun collectDirectTextChildren(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 4) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (isUsefulConversationName(text)) out.add(text)
        if (isUsefulConversationName(desc)) out.add(desc)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectDirectTextChildren(child, out, depth + 1)
            }
        }
    }

    private fun collectVisibleTextFallback(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>
    ) {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (node.isVisibleToUser && isUsefulConversationName(text)) out.add(text)
        if (node.isVisibleToUser && isUsefulConversationName(desc)) out.add(desc)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectVisibleTextFallback(child, out)
            }
        }
    }

    private fun isUsefulConversationName(value: String): Boolean {
        val text = Regex("\\s+").replace(value, " ").trim()
        if (text.length !in 2..80) return false
        if (text.startsWith("+")) return false
        if (text.matches(Regex("^[0-9 .,:/()\\-+]+$"))) return false

        val generic = setOf(
            "Chats", "Chat", "Contacts", "Settings", "Calls", "New chat",
            "Search", "More options", "Archived", "Unread", "Favorites",
            "All", "Groups", "Communities", "Updates", "Status",
            "Camera", "Back", "Home", "Menu", "Messages",
            "ارسال", "تنظیمات", "مخاطبین", "تماس‌ها", "گفتگوها", "جستجو",
            "خانه", "گزینه‌های بیشتر", "بایگانی", "خوانده نشده", "علاقه‌مندی‌ها",
            "همه", "گروه‌ها", "دوربین", "بازگشت", "منو", "پیام‌ها",
            "Calls, 1 new notification", "Notifications on another account",
            "Add new list", "More options"
        )
        if (generic.any { text.equals(it, ignoreCase = true) }) return false

        val lower = text.lowercase()
        val blockedWords = listOf(
            "filter", "selected", "unselected", "notification",
            "new notification", "add new", "more options"
        )
        if (blockedWords.any { lower.contains(it) }) return false

        return true
    }

    private fun scrollConversationList(root: AccessibilityNodeInfo) {
        val scrollable = findScrollable(root) ?: return
        runCatching {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findScrollable(child)?.let { return it }
            }
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (sending) {
            handler.postDelayed({ trySend() }, 500)
        }

        if (scanChannel != null && event?.packageName?.toString() != packageName) {
            val token = scanToken
            handler.postDelayed({
                if (token == scanToken && scanChannel != null) scanPass(token)
            }, 300)
        }
    }

    private fun processQueue() {
        if (paused || sending || queue.isEmpty()) return
        sending = true
        launchMessenger(queue.peekFirst().channel)
    }

    private fun finishCurrent(success: Boolean, detail: String) {
        val p = if (queue.isEmpty()) null else queue.removeFirst()
        p?.let {
            SendLogStore.add(this, it.scheduleId, it.channel, it.target, it.content, success, detail)
        }
        sending = false
        handler.postDelayed({ processQueue() }, 450)
    }

    private fun trySend() {
        if (!sending || queue.isEmpty()) return
        val p = queue.peekFirst()
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ trySend() }, 1000)
            return
        }

        val groupNode = findText(root, p.target) ?: run {
            p.attempts++
            if (p.attempts >= 8) {
                finishCurrent(false, "مخاطب/گروه پیدا نشد پس از چند تلاش")
                return
            }
            handler.postDelayed({ trySend() }, 1200)
            return
        }

        groupNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        handler.postDelayed({
            val r = rootInActiveWindow ?: run {
                handler.postDelayed({ trySend() }, 1200)
                return@postDelayed
            }
            val input = findEditable(r) ?: run {
                handler.postDelayed({ trySend() }, 1200)
                return@postDelayed
            }

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    p.content
                )
            }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                val rr = rootInActiveWindow ?: run {
                    handler.postDelayed({ trySend() }, 1000)
                    return@postDelayed
                }

                val send = findSendButton(rr)
                if (send != null) {
                    if (p.imageUris.isNotEmpty() || !p.imageUri.isNullOrBlank()) {
                        runCatching {
                            val uris = if (p.imageUris.isNotEmpty()) {
                                p.imageUris.map { Uri.parse(it) }
                            } else {
                                listOf(Uri.parse(p.imageUri))
                            }

                            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_TEXT, p.content)
                                putParcelableArrayListExtra(
                                    Intent.EXTRA_STREAM,
                                    ArrayList(uris)
                                )
                                addFlags(
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                )
                                setPackage(packageFor(p.channel))
                            }
                            startActivity(share)
                        }
                        finishCurrent(
                            true,
                            "متن و ${p.imageUris.size.coerceAtLeast(1)} عکس آماده ارسال شد؛ ممکن است تأیید نهایی لازم باشد"
                        )
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
        node.findAccessibilityNodeInfosByText(text).firstOrNull()?.let { return it }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findText(child, text)?.let { return it }
            }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditable(child)?.let { return it }
            }
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val names = listOf("ارسال", "Send", "ارسال پیام", "send")
        for (name in names) {
            node.findAccessibilityNodeInfosByText(name).firstOrNull()?.let { return it }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findSendButton(child)?.let { return it }
            }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scanToken++
        instance = null
        super.onDestroy()
    }
}
