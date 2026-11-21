package com.fakepod.remote

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V0.5.6: browse NAS on Android; physically authorize encrypted Wi-Fi transfer.
 * Credentials use the provisioning channel and are remembered with device-local encryption.
 */
internal class NasWebDavSettingsDialog(
    private val activity: Activity,
    private val deviceIp: () -> String?
) {
    private val prefs = activity.getSharedPreferences("fakepod_nas_ui", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val logins = NasCredentialStore(activity.applicationContext)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private lateinit var dialog: Dialog
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var deviceCodeField: EditText
    private lateinit var selectedText: TextView
    private lateinit var feedbackText: TextView
    private var selectedPath = prefs.getString("path", "/") ?: "/"
    private var navigatingPath = "/"
    private var validated = false
    private lateinit var serverSection: NasFormSection
    private lateinit var directorySection: NasFormSection
    private lateinit var catalogSection: NasFormSection
    private var catalogSummary = "未检查"

    fun show(onClosed: () -> Unit = {}) {
        dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(Color.rgb(20, 23, 30))
        }
        root.addView(label("NAS 音乐服务器", 19f, true))
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        serverSection = NasFormSection(activity, "服务器", true) {
            if (!::hostField.isInitialized || !::portField.isInitialized || hostField.text.isNullOrBlank()) "未填写" else "${hostField.text}:${portField.text}"
        }
        serverSection.body.addView(label("地址（不含 http://）", 12f))
        hostField = edit("192.168.1.105", prefs.getString("host", "").orEmpty())
        serverSection.body.addView(hostField)
        serverSection.body.addView(label("WebDAV 端口", 12f))
        portField = edit("5005", prefs.getInt("port", 5005).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        serverSection.body.addView(portField)
        serverSection.body.addView(label("账号", 12f))
        usernameField = edit("NAS 账号", prefs.getString("username", "").orEmpty())
        serverSection.body.addView(usernameField)
        serverSection.body.addView(label("密码", 12f))
        val savedLogin = logins.load("webdav", hostField.text.toString(), portField.text.toString().toIntOrNull() ?: 5005)
        savedLogin?.let { usernameField.setText(it.username) }
        passwordField = edit("密码", savedLogin?.password.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        serverSection.body.addView(passwordField)
        val endpointWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val saved = logins.load("webdav", hostField.text.toString(), portField.text.toString().toIntOrNull() ?: 0)
                saved?.let { usernameField.setText(it.username) }
                passwordField.setText(saved?.password.orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        hostField.addTextChangedListener(endpointWatcher); portField.addTextChangedListener(endpointWatcher)
        content.addView(serverSection, verticalParams(0, 8))
        directorySection = NasFormSection(activity, "① 音乐目录", true) { displayPath(selectedPath) }
        selectedText = label(displayPath(selectedPath), 12f).apply { setTextColor(Color.rgb(181, 217, 255)) }
        directorySection.body.addView(selectedText)
        directorySection.body.addView(button("浏览目录") {
            try {
                connectionInfo()
                navigatingPath = "/"
                openFolderBrowser()
            } catch (e: Exception) {
                serverSection.expanded = true
                feedback(e.message ?: "地址不正确")
            }
        })
        content.addView(directorySection, verticalParams(0, 8))
        catalogSection = NasFormSection(activity, "② NAS 曲库", false) { catalogSummary }
        catalogSection.body.addView(button("检查曲库") { validateAndSave() })
        catalogSection.body.addView(button("扫描并更新") {
            try {
                val info = connectionInfo()
                val path = selectedPath.trimEnd('/')
                require(path.isNotBlank()) { "请先选择音乐目录" }
                NasCatalogUpdateDialog(activity, info.host, info.username, path,
                    info.origin, info.password).show()
            } catch (e: Exception) { feedback(e.message ?: "请先填写 NAS 设置") }
        }, verticalParams(6, 0))
        content.addView(catalogSection, verticalParams(0, 6))
        val scroll = ScrollView(activity).apply { addView(content); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("设备：设置 → 连接 → NAS服务器配对", 11f))
        deviceCodeField = edit("6位配对码", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        root.addView(deviceCodeField)
        feedbackText = label("请选择音乐目录", 11f).apply { setTextColor(Color.rgb(114, 191, 255)) }
        root.addView(ScrollView(activity).apply { addView(feedbackText) },
            LinearLayout.LayoutParams(-1, dp(56)))
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(button("③ 发送到 FakePod") { verifyAndSend() }, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(button("关闭") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(66), -2).apply { leftMargin = dp(6) })
        root.addView(footer)
        // Section summaries are first evaluated after their fields have been attached.
        serverSection.refreshSummary()
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener { worker.shutdownNow(); onClosed() }
        dialog.show()
        dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - dp(28),
            (activity.resources.displayMetrics.heightPixels * .88f).toInt())
    }

    private data class Connection(val host: String, val port: Int, val username: String, val password: String) {
        val origin: String get() = "http://$host:$port"
    }

    private fun connectionInfo(): Connection {
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("端口不是数字")
        val username = usernameField.text.toString().trim()
        val password = passwordField.text.toString()
        if (!Regex("[A-Za-z0-9][A-Za-z0-9.\\-]{0,252}").matches(host) || ".." in host)
            throw IllegalArgumentException("请填写有效的 NAS IPv4 地址或主机名")
        if (port !in 1..65535) throw IllegalArgumentException("端口应在 1–65535")
        if (username.isEmpty() || password.isEmpty()) throw IllegalArgumentException("请先输入 NAS 用户名和密码")
        logins.save("webdav", host, port, username, password)
        prefs.edit().putString("host", host).putInt("port", port).putString("username", username).apply()
        return Connection(host, port, username, password)
    }

    private fun request(info: Connection, encodedPath: String, method: String): ByteArray {
        require(encodedPath.startsWith("/") && "?" !in encodedPath && "#" !in encodedPath)
        val url = info.origin + encodedPath
        val request = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(info.username, info.password, StandardCharsets.UTF_8))
            .header("Depth", if (method == "PROPFIND" && !encodedPath.endsWith("/_FakePod/track/")) "1" else "0")
            .method(method, null)
            .build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            if (method == "PROPFIND" && code != 207) throw IllegalStateException("目录读取失败，HTTP $code")
            if (method == "GET" && code != 200) throw IllegalStateException("索引验证失败，HTTP $code")
            val bytes = response.body?.bytes() ?: throw IllegalStateException("服务器返回空内容")
            if (bytes.size > 8 * 1024 * 1024) throw IllegalStateException("服务器目录响应太大")
            return bytes
        }
    }

    private data class Folder(val displayName: String, val encodedPath: String)

    private fun parseFolders(xml: ByteArray, current: String, host: String, port: Int): List<Folder> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val folders = LinkedHashMap<String, Folder>()
        var insideResponse = false
        var href = ""
        var isFolder = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name?.lowercase()) {
                    "response" -> { insideResponse = true; href = ""; isFolder = false }
                    "href" -> if (insideResponse) href = parser.nextText().trim()
                    "collection" -> if (insideResponse) isFolder = true
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("response", true)) {
                if (insideResponse && isFolder && href.isNotEmpty()) {
                    val uri = try { URI(href) } catch (_: Exception) { null }
                    if (uri != null && (uri.host == null || (uri.host.equals(host, true) && (uri.port == -1 || uri.port == port)))) {
                        val path = uri.rawPath ?: ""
                        if (path.startsWith("/") && path.trimEnd('/') != current.trimEnd('/')) {
                            val name = displayPath(path).trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) folders[path] = Folder(name, path)
                        }
                    }
                }
                insideResponse = false
            }
            event = parser.next()
        }
        return folders.values.sortedBy { it.displayName.lowercase() }
    }

    private fun openFolderBrowser() {
        val info = connectionInfo()
        val chooser = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(Color.rgb(24, 28, 36))
        }
        root.addView(label("选择音乐文件夹", 18f, true))
        val pathLabel = label("/", 12f).apply {
            setTextColor(Color.rgb(187, 222, 255))
            setPadding(dp(11), dp(9), dp(11), dp(9))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            background = shape(Color.rgb(34, 43, 56))
        }
        root.addView(pathLabel, verticalParams(0, 9))
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val back = button("↑ 上一级") {
            val before = navigatingPath.trimEnd('/')
            navigatingPath = before.substringBeforeLast('/', "").ifEmpty { "/" }
            loadIntoBrowser(info, chooser, list, pathLabel)
        }
        val select = button("选择当前目录") {
            selectedPath = navigatingPath.trimEnd('/').ifEmpty { "/" }
            selectedText.text = displayPath(selectedPath)
            directorySection.refreshSummary()
            catalogSummary = "未检查"
            catalogSection.refreshSummary()
            validated = false
            feedback("目录已选择，请检查曲库")
            chooser.dismiss()
        }
        root.addView(back, verticalParams(0, 8))
        val scroll = ScrollView(activity).apply { setFillViewport(true) }
        scroll.addView(list)
        // Keep the file list scrollable instead of allowing its buttons to
        // overlap the dialog footer on small-screen / large-font devices.
        val maxListHeight = (activity.resources.displayMetrics.heightPixels * .43f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(-1, minOf(dp(350), maxListHeight)))
        actions.addView(select, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("取消") { chooser.dismiss() }, LinearLayout.LayoutParams(dp(76), -2).apply { leftMargin = dp(7) })
        root.addView(actions, verticalParams(9, 0))
        chooser.setContentView(root)
        chooser.window?.setBackgroundDrawableResource(android.R.color.transparent)
        chooser.show()
        chooser.window?.setLayout(activity.resources.displayMetrics.widthPixels - dp(24), -2)
        loadIntoBrowser(info, chooser, list, pathLabel)
    }

    private fun folderRow(folder: Folder, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(12), dp(10))
            minimumHeight = dp(58)
            background = shape(Color.rgb(39, 47, 60))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val title = label("📁  ${folder.displayName}", 14f, true).apply {
            setPadding(0, 0, dp(9), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 24f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(162, 184, 209))
            setPadding(dp(4), 0, dp(3), 0)
        })
        return row
    }

    // Requests run on a single worker, with an extra stale-path guard for rapid browsing.
    private fun loadIntoBrowser(info: Connection, chooser: Dialog, list: LinearLayout, pathLabel: TextView) {
        val path = navigatingPath
        pathLabel.text = "正在读取：${displayPath(path)}"
        list.removeAllViews()
        list.addView(label("正在连接 NAS…", 12f))
        worker.execute {
            try {
                val folders = parseFolders(request(info, path, "PROPFIND"), path, info.host, info.port)
                activity.runOnUiThread {
                    if (!chooser.isShowing || path != navigatingPath) return@runOnUiThread
                    pathLabel.text = displayPath(path)
                    serverSection.expanded = false
                    directorySection.expanded = true
                    list.removeAllViews()
                    if (folders.isEmpty()) list.addView(label("没有子目录", 12f))
                    for (folder in folders) {
                        val item = folderRow(folder) {
                            navigatingPath = folder.encodedPath
                            loadIntoBrowser(info, chooser, list, pathLabel)
                        }
                        list.addView(item, verticalParams(0, 8))
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (chooser.isShowing && path == navigatingPath) {
                        pathLabel.text = "读取失败：${displayPath(path)}"
                        list.removeAllViews()
                        list.addView(label(e.message ?: "网络错误", 12f))
                    }
                }
            }
        }
    }

    private fun validateAndSave() {
        val info = try { connectionInfo() } catch (e: Exception) { feedback(e.message ?: "配置错误"); return }
        val path = selectedPath.trimEnd('/')
        if (path.isEmpty()) { feedback("请选择 Music 根目录，不要选择 NAS 根目录"); return }
        catalogSection.expanded = true
        catalogSummary = "正在检查…"
        catalogSection.refreshSummary()
        feedback("正在检查曲库…")
        worker.execute {
            try {
                val meta = request(info, "$path/_FakePod/music-index/fakepod_catalog.meta", "GET")
                if (!String(meta, StandardCharsets.UTF_8).startsWith("FAKEPOD_NAS_V1\n"))
                    throw IllegalStateException("目录没有有效的 V2 meta")
                // Depth 1 on track can include thousands of entries.  Checking the
                // directory itself with a GET is not portable, so validate via
                // PROPFIND and only accept a successful 207 response.
                request(info, "$path/_FakePod/track/", "PROPFIND")
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    prefs.edit().putString("host", info.host).putInt("port", info.port)
                        .putString("username", info.username).putString("path", path).apply()
                    validated = true
                    val tracks = Regex("(?m)^tracks=([0-9]+)").find(String(meta, StandardCharsets.UTF_8))?.groupValues?.get(1)
                    catalogSummary = if (tracks != null) "$tracks 首 · 已就绪" else "已就绪"
                    catalogSection.refreshSummary()
                    serverSection.expanded = false
                    feedback("检查通过，设置已保存；尚未发送")
                }
            } catch (e: Exception) {
                activity.runOnUiThread { if (dialog.isShowing) {
                    catalogSummary = "检查失败"; catalogSection.refreshSummary()
                    feedback(e.message ?: "检查失败")
                } }
            }
        }
    }

    private fun verifyAndSend() {
        val info = try { connectionInfo() } catch (e: Exception) {
            feedback(e.message ?: "连接参数错误"); return
        }
        val path = selectedPath.trimEnd('/')
        if (path.isBlank()) { feedback("请选择 Music 根目录"); return }
        val ip = deviceIp()
        if (ip.isNullOrBlank()) { feedback("请先连接 FakePod Wi-Fi 遥控"); return }
        val code = deviceCodeField.text.toString()
        feedback("正在检查并发送…")
        worker.execute {
            try {
                val meta = request(info, "$path/_FakePod/music-index/fakepod_catalog.meta", "GET")
                if (!String(meta, StandardCharsets.UTF_8).startsWith("FAKEPOD_NAS_V1\n"))
                    throw IllegalStateException("所选目录没有有效 V2 索引")
                request(info, "$path/_FakePod/track/", "PROPFIND")
                NasWifiProvisioningClient.send(activity.applicationContext, ip, code,
                    info.origin + path, info.username, info.password)
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    prefs.edit().putString("host", info.host).putInt("port", info.port)
                        .putString("username", info.username).putString("path", path).apply()
                    deviceCodeField.text?.clear()
                    feedback("设置已发送，等待设备校验并加载曲库")
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (dialog.isShowing) feedback("发送失败：${e.message ?: "网络或授权码错误"}；旧配置不变")
                }
            }
        }
    }

    private fun verticalParams(top: Int, bottom: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun feedback(message: String) { feedbackText.text = message }
    private fun displayPath(encoded: String): String = try {
        URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) { encoded }
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(dp(4), dp(8), dp(4), dp(4))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun edit(hint: String, initial: String) = EditText(activity).apply {
        this.hint = hint; setText(initial); setSingleLine(true); textSize = 13f
        setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        setPadding(dp(10), dp(8), dp(10), dp(8)); background = shape(Color.rgb(34, 39, 48))
    }
    private fun button(text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text; isAllCaps = false; textSize = 12f
        setTextColor(Color.WHITE)
        background = shape(Color.rgb(43, 51, 64))
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        maxLines = 2
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }
}
