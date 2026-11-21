package com.fakepod.remote

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import java.net.URLDecoder
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scan and stage on the phone; review before modifying the published NAS catalogue. */
internal class NasCatalogUpdateDialog(
    private val activity: Activity,
    private val host: String,
    private val davUser: String,
    private val davPath: String,
    private val davOrigin: String,
    private val davPassword: String
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancel = AtomicBoolean(false)
    private val logins = NasCredentialStore(activity.applicationContext)
    private val prefs = activity.getSharedPreferences("fakepod_nas_scan_ui", Activity.MODE_PRIVATE)
    private lateinit var dialog: Dialog
    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var scan: Button
    private lateinit var publish: Button
    private lateinit var close: Button
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var root: EditText
    private lateinit var legacy: CheckBox
    private lateinit var rootToggle: CheckBox
    private lateinit var smbUserField: EditText
    private lateinit var smbPasswordField: EditText
    private lateinit var smbPathField: EditText
    private lateinit var smbToggle: CheckBox
    private var bridge: NasSshBridge? = null
    private var output: File? = null
    private var busy = false
    private var committing = false
    private var scope = ""
    private var physicalRoot = ""
    private lateinit var smbSection: NasFormSection
    private lateinit var sshSection: NasFormSection
    private lateinit var advancedSection: NasFormSection
    private val progressState = NasScanProgress()
    private val ticker = object : Runnable {
        override fun run() {
            if (!busy || !dialog.isShowing) return
            if (cancel.get() && !committing) status.text = "正在取消…"
            else renderProgress(progressState.snapshot())
            status.postDelayed(this, 1000)
        }
    }

    fun show() {
        dialog = Dialog(activity)
        dialog.setCancelable(false)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.rgb(20, 23, 30)); cornerRadius = dp(14).toFloat() }
        }
        layout.addView(label("更新 NAS 曲库", 18f))
        layout.addView(label("$host · ${URLDecoder.decode(davPath.replace("+", "%2B"), "UTF-8")}", 12f))
        layout.addView(label("请暂停设备播放，保持 Wi-Fi 连接", 11f))
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        smbSection = NasFormSection(activity, "SMB 登录", true) {
            if (::smbUserField.isInitialized) smbUserField.text.toString() else ""
        }
        smbSection.body.addView(label("账号", 12f))
        val smbLogin = logins.load("smb", host, 445)
        smbUserField = edit("共享账号", smbLogin?.username ?: prefs.getString("smbUser:$host", davUser).orEmpty())
        smbSection.body.addView(smbUserField)
        smbSection.body.addView(label("密码", 12f))
        smbPasswordField = edit("共享密码", smbLogin?.password ?: davPassword).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        smbSection.body.addView(smbPasswordField)
        content.addView(smbSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        sshSection = NasFormSection(activity, "SSH 登录", true) {
            if (::user.isInitialized && ::port.isInitialized) "${user.text} · 端口 ${port.text}" else ""
        }
        sshSection.body.addView(label("端口", 12f))
        port = edit("22", prefs.getInt("port", 22).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        sshSection.body.addView(port)
        sshSection.body.addView(label("账号", 12f))
        val sshLogin = logins.load("ssh", host, port.text.toString().toIntOrNull() ?: 22)
        user = edit("SSH 账号", sshLogin?.username ?: prefs.getString("user", davUser).orEmpty())
        sshSection.body.addView(user)
        sshSection.body.addView(label("密码", 12f))
        password = edit("SSH 密码", sshLogin?.password.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        sshSection.body.addView(password)
        port.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val saved = logins.load("ssh", host, port.text.toString().toIntOrNull() ?: 0)
                saved?.let { user.setText(it.username) }
                password.setText(saved?.password.orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        content.addView(sshSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        advancedSection = NasFormSection(activity, "高级设置", false) { "路径、兼容选项" }
        val advanced = advancedSection.body
        val initialSmbPath = prefs.getString("smbPath:$host:$davPath", "").orEmpty().ifBlank {
            URLDecoder.decode(davPath.replace("+", "%2B"), "UTF-8").trimEnd('/')
        }
        val smbPreview = label("SMB 路径：$initialSmbPath", 12f)
        advanced.addView(smbPreview)
        smbPathField = edit("/共享名/音乐目录", initialSmbPath).apply { visibility = View.GONE }
        smbToggle = CheckBox(activity).apply {
            text = "修改 SMB 路径"; textSize = 12f; setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked ->
                smbPathField.visibility = if (checked) View.VISIBLE else View.GONE
                smbPreview.text = "SMB 路径：${smbPathField.text}"
                smbPreview.visibility = if (checked) View.GONE else View.VISIBLE
            }
        }
        advanced.addView(smbToggle); advanced.addView(smbPathField)
        val savedRoot = prefs.getString("root:$host:$davPath", "").orEmpty()
        val initialRoot = savedRoot.ifBlank { suggestedSshRoot(davPath) }
        val rootPreview = label("SSH 路径：$initialRoot", 12f).apply { setTextColor(Color.rgb(114, 191, 255)) }
        advanced.addView(rootPreview)
        root = edit("NAS 真实路径", initialRoot).apply { visibility = View.GONE }
        rootToggle = CheckBox(activity).apply {
            text = "修改 SSH 路径"; textSize = 12f; setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked ->
                root.visibility = if (checked) View.VISIBLE else View.GONE
                rootPreview.visibility = if (checked) View.GONE else View.VISIBLE
                rootPreview.text = "SSH 路径：${root.text}"
            }
        }
        advanced.addView(rootToggle); advanced.addView(root)
        legacy = CheckBox(activity).apply {
            text = "旧 NAS 兼容模式"; textSize = 12f; setTextColor(Color.LTGRAY)
        }
        advanced.addView(legacy)
        content.addView(advancedSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        smbSection.refreshSummary(); sshSection.refreshSummary()
        status = label("扫描后确认更新", 12f).apply { setTextColor(Color.rgb(114, 191, 255)); minLines = 4 }
        content.addView(status)
        val scroll = ScrollView(activity).apply { addView(content) }
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; isIndeterminate = false
        }
        layout.addView(progressBar, LinearLayout.LayoutParams(-1, dp(12)))
        progressText = label("等待扫描", 12f).apply { setTextColor(Color.rgb(114, 191, 255)) }
        layout.addView(progressText)
        scan = button("开始扫描") { startScan() }
        publish = button("确认更新") { startPublish() }.apply { isEnabled = false }
        close = button("关闭") {
            if (busy) {
                if (!committing) {
                    cancel.set(true)
                    status.text = "正在取消…"
                }
            } else dialog.dismiss()
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(scan, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(publish, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(actions); layout.addView(close)
        dialog.setContentView(layout)
        dialog.setOnDismissListener {
            cancel.set(true)
            password.text?.clear()
            smbPasswordField.text?.clear()
            worker.execute { bridge?.close(); bridge = null; output?.deleteRecursively() }
            worker.shutdown()
        }
        dialog.show()
        dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - dp(24),
            (activity.resources.displayMetrics.heightPixels * .88f).toInt())
    }

    private fun setBusy(value: Boolean, publishing: Boolean = false) {
        busy = value
        committing = publishing && value
        scan.isEnabled = !value && output == null
        publish.isEnabled = !value && output != null && bridge != null
        listOf(port, user, password, root, rootToggle, legacy, smbUserField, smbPasswordField, smbPathField, smbToggle).forEach { it.isEnabled = !value && output == null }
        close.text = if (value) { if (publishing) "正在发布，请稍候…" else "取消扫描" } else "关闭"
        close.isEnabled = !committing
        if (value) {
            progressBar.isIndeterminate = true
            progressText.text = if (publishing) "正在复核和上传…" else "正在连接 NAS…"
        } else if (progressBar.isIndeterminate) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            progressText.text = "操作已停止"
        }
        if (value) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 525)
            try { NasScanService.start(activity.applicationContext) { cancel.set(true) } }
            catch (e: Exception) { busy = false; committing = false; throw e }
            smbSection.expanded = false; sshSection.expanded = false; advancedSection.expanded = false
            progressState.reset(if (publishing) "正在复核和上传…" else "正在连接 NAS…",
                android.os.SystemClock.elapsedRealtime())
            status.postDelayed(ticker, 1000)
        } else {
            status.removeCallbacks(ticker)
            NasScanService.stop(activity.applicationContext)
        }
    }

    private fun startScan() {
        if (busy) return
        val number = port.text.toString().toIntOrNull()
        val account = user.text.toString().trim()
        val secret = password.text.toString()
        val directory = root.text.toString().trim().trimEnd('/')
        if (number == null || number !in 1..65535 || account.isBlank() || secret.isEmpty() ||
            !directory.startsWith("/") || directory.isBlank() ||
            directory.split('/').any { it == "." || it == ".." || it == "_FakePod" } ||
            directory.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            sshSection.expanded = true
            status.text = "请检查 SSH 账号、密码、端口和路径"; return
        }
        val smbAccount = smbUserField.text.toString().trim()
        val smbSecret = smbPasswordField.text.toString()
        val smbDirectory = smbPathField.text.toString().trim().trimEnd('/')
        try {
            require(smbAccount.isNotBlank() && smbSecret.isNotEmpty()) { "请填写 SMB 账号和密码" }
            NasCatalogSmb.parseRoot(smbDirectory)
        } catch (e: Exception) {
            smbSection.expanded = true
            status.text = e.message; return
        }
        try {
            logins.save("smb", host, 445, smbAccount, smbSecret)
            logins.save("ssh", host, number, account, secret)
            prefs.edit().putInt("port", number).putString("user", account).putString("smbUser:$host", smbAccount).apply()
        } catch (e: Exception) { status.text = friendly(e); return }
        val oldMode = legacy.isChecked
        cancel.set(false)
        try { setBusy(true) } catch (e: Exception) { status.text = friendly(e); setBusy(false); return }
        status.text = "正在连接 NAS SMB 和 SSH…"
        worker.execute {
            try {
                NasScanService.awaitReady()
                bridge?.close()
                bridge = null
                output?.deleteRecursively()
                output = null
                physicalRoot = directory
                scope = "$host:$number:$directory:smb:$smbDirectory"
                val connection = NasSshBridge(activity.applicationContext, host, number, account, secret, oldMode,
                    directory, davOrigin, davPath, davUser, davPassword, smbDirectory, smbAccount, smbSecret, { confirmHost(it) }, { update(it) }, { cancel.get() })
                bridge = connection
                update("正在核对音乐目录…")
                connection.verifyMapping()
                prefs.edit().putInt("port", number).putString("user", account)
                    .putString("root:$host:$davPath", directory)
                    .putString("smbUser:$host", smbAccount).putString("smbPath:$host:$davPath", smbDirectory).apply()
                synchronized(Python::class.java) {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(activity.applicationContext))
                }
                val local = File.createTempFile("fakepod-nas-", "", activity.cacheDir).apply { delete(); mkdir() }
                output = local
                val result = Python.getInstance().getModule("mobile_nas_catalog")
                    .callAttr("scan", connection, directory, scope, local.absolutePath,
                        File(activity.filesDir, "nas-scan-checkpoint").absolutePath).toString()
                val summary = JSONObject(result)
                val text = "扫描完成：共 ${summary.getInt("total")} 首\n" +
                    "新增 ${summary.getInt("added")} · 修改 ${summary.getInt("modified")} · 删除 ${summary.getInt("removed")}\n" +
                    "复用 ${summary.getInt("reused")}\n" +
                    (if (summary.optBoolean("baseline")) "首次扫描，新增按整库统计\n" else "") +
                    "尚未更新，请确认"
                update(text, true)
            } catch (e: Exception) {
                bridge?.close(); bridge = null
                output?.deleteRecursively(); output = null
                update(if (cancel.get()) "已取消，尚未更新" else "扫描失败：${friendly(e)}\n可重扫并复用已完成歌曲", true)
            } finally {
                activity.runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun startPublish() {
        val connection = bridge ?: return
        val local = output ?: return
        if (busy) return
        cancel.set(false)
        try { setBusy(true, true) } catch (e: Exception) { status.text = friendly(e); setBusy(false); return }
        status.text = "正在复核和上传，请保持 Wi-Fi 连接…"
        worker.execute {
            try {
                NasScanService.awaitReady()
                Python.getInstance().getModule("mobile_nas_catalog")
                    .callAttr("publish", connection, physicalRoot, scope, local.absolutePath)
                update("更新完成，请在设备更新 NAS 曲库", true)
            } catch (e: Exception) {
                update("更新未确认完成：${friendly(e)}\n发布中断时，请先检查 NAS 的 .app-stage 与 COMMITTED 标记", true)
            } finally {
                connection.close(); bridge = null
                local.deleteRecursively(); output = null
                activity.runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun confirmHost(fingerprint: String): Boolean {
        val latch = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        activity.runOnUiThread {
            if (activity.isFinishing || !dialog.isShowing) { latch.countDown(); return@runOnUiThread }
            AlertDialog.Builder(activity).setTitle("首次连接此 NAS")
                .setMessage("请核对 NAS 的 SSH 主机指纹。确认后会记住；指纹变化将停止连接。\n\n$fingerprint")
                .setPositiveButton("确认并连接") { _, _ -> accepted.set(true); latch.countDown() }
                .setNegativeButton("取消") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }.show()
        }
        return latch.await(120, TimeUnit.SECONDS) && accepted.get()
    }

    private fun update(message: String, immediate: Boolean = false) {
        progressState.offer(message, android.os.SystemClock.elapsedRealtime())
        // Ordinary messages only replace the snapshot. One main-thread tick renders the latest.
        if (immediate) activity.runOnUiThread {
            if (dialog.isShowing) renderProgress(progressState.snapshot(), true)
        }
    }

    private fun renderProgress(snapshot: NasScanProgress.Snapshot, immediate: Boolean = false) {
        val message = snapshot.message
        val seconds = ((android.os.SystemClock.elapsedRealtime() - snapshot.since) / 1000).coerceAtLeast(0)
        val counts = Regex("^读取歌曲信息 [0-9]+/[0-9]+[ ·]*").find(message)
        val detail = if (counts != null) message.removeRange(counts.range) else message
        status.text = if (immediate) message else "$detail\n已等待 ${seconds} 秒"
        NasScanService.update(message, immediate)
        when {
            message.startsWith("扫描完成") || message.startsWith("更新完成") -> {
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                progressText.text = if (message.startsWith("扫描完成")) "扫描完成 · 100%" else "更新完成 · 100%"
            }
            immediate -> {
                progressBar.isIndeterminate = false
                progressText.text = if (cancel.get()) "已取消" else "操作未完成"
            }
            snapshot.done != null && snapshot.total != null -> {
                val total = snapshot.total.coerceAtLeast(1)
                val percent = (snapshot.done * 100 / total).toInt().coerceIn(0, 100)
                progressBar.isIndeterminate = false
                progressBar.progress = percent
                progressText.text = "${snapshot.done} / $total 首 · $percent%"
            }
            else -> {
                progressBar.isIndeterminate = true
                progressText.text = when {
                    message.startsWith("正在扫描目录") -> "正在读取目录…"
                    message.startsWith("上传并校验") -> "正在上传并校验…"
                    else -> "正在处理…"
                }
            }
        }
    }

    private fun friendly(error: Exception): String {
        val text = error.message.orEmpty()
        return when {
            "reject HostKey" in text || "HostKey has been changed" in text -> "NAS 主机指纹不匹配，连接已停止。"
            "Auth fail" in text -> "SSH 登录失败，请检查账号、密码和 NAS 的 SSH 登录权限。"
            "Algorithm negotiation fail" in text -> "SSH 算法不兼容，可尝试开启旧 NAS 兼容模式。"
            else -> text.takeLast(600).ifBlank { "请检查 NAS SMB、SSH 和 WebDAV 是否已开启，以及路径、权限和网络。" }
        }
    }

    private fun suggestedSshRoot(encodedPath: String): String {
        val decoded = URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8").trimEnd('/')
        require(decoded.startsWith("/") && decoded != "/") { "请先在上一页选择音乐目录" }
        return if (Regex("^/volume[0-9]+(?:/|$)").containsMatchIn(decoded)) decoded
            else "/volume1$decoded"
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density + .5f).toInt()
    private fun label(text: String, size: Float) = TextView(activity).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(dp(4), dp(9), dp(4), dp(5))
    }
    private fun edit(hint: String, initial: String) = EditText(activity).apply {
        this.hint = hint; setText(initial); setSingleLine(true); textSize = 13f
        setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
    }
    private fun button(text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text; isAllCaps = false; textSize = 12f
        setOnClickListener { action() }
    }
}
