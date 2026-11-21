package com.fakepod.remote

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.fakepod.remote.cover.FakePodCoverHub
import com.fakepod.remote.cover.FakePodMediaCoverListener

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
        private const val REQUEST_WIFI_SSID_PERMISSION = 1002
        private const val LOCAL_PROGRESS_TICK_MS = 1_000L

        private val COLOR_BACKGROUND = Color.rgb(10, 12, 16)
        private val COLOR_SURFACE = Color.rgb(20, 23, 30)
        private val COLOR_SURFACE_HIGH = Color.rgb(27, 31, 40)
        private val COLOR_BORDER = Color.rgb(45, 50, 62)
        private val COLOR_TEXT = Color.rgb(245, 247, 250)
        private val COLOR_TEXT_SECONDARY = Color.rgb(164, 171, 184)
        private val COLOR_TEXT_MUTED = Color.rgb(105, 112, 126)
        private val COLOR_ACCENT = Color.rgb(126, 167, 255)
        private val COLOR_SUCCESS = Color.rgb(87, 211, 140)
        private val COLOR_WARNING = Color.rgb(244, 184, 103)
        private val COLOR_ERROR = Color.rgb(255, 107, 116)
    }

    private lateinit var bleClient: FakePodBleClient
    private val uiHandler = Handler(Looper.getMainLooper())
    private var renderedStateColor: Int? = null
    private val progressTicker = object : Runnable {
        override fun run() {
            val current = bleClient.currentSnapshot()
            updateProgressUi(current, bleClient.estimatedPositionMs(current))
            uiHandler.postDelayed(this, LOCAL_PROGRESS_TICK_MS)
        }
    }

    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var playbackText: TextView
    private lateinit var progressBar: SeekBar
    private var musicLibraryDialog: MusicLibraryDialog? = null
    private val localSeek = LocalSeekController()
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var volumeText: TextView
    private lateinit var batteryText: TextView
    private lateinit var volumeSlider: SeekBar

    private lateinit var playPauseButton: Button
    private var reconnectButton: Button? = null
    private var settingsStatusText: TextView? = null
    private var settingsAddressText: TextView? = null
    private var settingsWifiSsidText: TextView? = null
    private var settingsWifiPassword: EditText? = null
    private var settingsWifiProvisionButton: Button? = null
    private var volumeDragging = false
    private var pendingVolumeTarget: Int? = null
    private val controlButtons = mutableListOf<Button>()
    private lateinit var viewDropdown: TextView
    private lateinit var loopDropdown: TextView
    private lateinit var screenDropdown: TextView
    private lateinit var cassetteControls: LinearLayout
    private lateinit var spectrumControls: LinearLayout
    private lateinit var spectrumStyleDropdown: TextView
    private val coverShiftButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND

        bleClient = (application as FakePodApplication).bleClient
        setContentView(buildUi())
        updateUi(bleClient.currentSnapshot())

        if (hasPermissions()) {
            bleClient.start()
        } else {
            requestBluetoothPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        bleClient.setRemoteForeground(true)
        if (FakePodCoverHub.isEnabled() && hasPermissions()) FakePodPhoneCoverService.start(this)
        bleClient.setListener { snapshot ->
            runOnUiThread { updateUi(snapshot) }
        }
        uiHandler.removeCallbacks(progressTicker)
        uiHandler.post(progressTicker)
    }

    override fun onResume() {
        super.onResume()
        if (::bleClient.isInitialized) {
            bleClient.recoverWifiConnection()
            FakePodMediaCoverListener.refreshIfRunning()
            updateSettingsUi(bleClient.currentSnapshot())
        }
    }

    override fun onStop() {
        musicLibraryDialog?.dismiss()
        musicLibraryDialog = null
        localSeek.cancel()
        uiHandler.removeCallbacks(progressTicker)
        bleClient.setListener(null)
        bleClient.setRemoteForeground(false)
        super.onStop()
    }

    private fun hasPermissions(): Boolean = bleClient.hasRequiredPermissions()

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, REQUEST_BLUETOOTH_PERMISSIONS)
    }

    private fun hasWifiSsidPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationServiceEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun requestWifiSsidPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_WIFI_SSID_PERMISSION
        )
    }

    private fun sanitizeSsid(raw: String?): String? {
        if (raw == null) return null
        return raw.removePrefix("\"").removeSuffix("\"")
            .takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun currentWifiSsid(): String? {
        if (!hasWifiSsidPermission() || !isLocationServiceEnabled()) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                if (connectivity != null) {
                    val active = connectivity.activeNetwork
                    if (active != null) {
                        val capabilities = connectivity.getNetworkCapabilities(active)
                        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                            sanitizeSsid((capabilities.transportInfo as? WifiInfo)?.ssid)?.let {
                                return it
                            }
                        }
                    }

                    // VPN / 双 Wi-Fi / 厂商网络栈可能让 activeNetwork 不是 Wi-Fi。
                    // 遍历当前网络，寻找实际的 Wi-Fi transport 作为第二条路径。
                    for (network in connectivity.allNetworks) {
                        val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                        sanitizeSsid((capabilities.transportInfo as? WifiInfo)?.ssid)?.let {
                            return it
                        }
                    }
                }
            } catch (_: SecurityException) {
                // 某些厂商系统会单独限制 NetworkCapabilities；继续走 WifiManager 兜底。
            }
        }

        return try {
            // 兼容兜底：Android 12+ 已弃用，但仍可返回主互联网 Wi-Fi。
            val manager = applicationContext.getSystemService(WifiManager::class.java)
            sanitizeSsid(manager?.connectionInfo?.ssid)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun wifiSsidStatusText(ssid: String?): String = when {
        !hasWifiSsidPermission() ->
            "当前网络 · 需要精确位置权限才能读取 SSID"
        !isLocationServiceEnabled() ->
            "当前网络 · 系统位置服务已关闭"
        ssid != null ->
            "当前网络 · $ssid"
        else ->
            "当前网络 · 未读取到 Wi-Fi，请确认手机已连接 Wi-Fi"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH_PERMISSIONS -> {
                if (bleClient.hasRequiredPermissions()) {
                    bleClient.start()
                    if (FakePodCoverHub.isEnabled()) FakePodPhoneCoverService.start(this)
                } else {
                    updateUi(
                        FakePodBleClient.Snapshot(
                            FakePodBleClient.State.ERROR,
                            "没有蓝牙权限，无法连接 FakePod"
                        )
                    )
                }
            }
            REQUEST_WIFI_SSID_PERMISSION -> {
                updateSettingsUi(bleClient.currentSnapshot())
            }
        }
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(30))
            setBackgroundColor(COLOR_BACKGROUND)
        }

        content.addView(buildHeader(), matchWrap())
        content.addView(space(14))
        content.addView(buildConnectionCard(), matchWrap())
        content.addView(space(14))
        content.addView(buildNowPlayingCard(), matchWrap())
        content.addView(space(14))
        content.addView(buildMetricsRow(), matchWrap())
        content.addView(space(16))
        content.addView(buildControlsCard(), matchWrap())
        content.addView(space(12))
        content.addView(Button(this).apply {
            text = "音乐曲库 · 浏览与选歌"
            setTextColor(COLOR_TEXT)
            background = roundedDrawable(COLOR_SURFACE, 16, COLOR_BORDER)
            setOnClickListener {
                musicLibraryDialog?.dismiss()
                musicLibraryDialog = MusicLibraryDialog(this@MainActivity, bleClient.musicLibrary).also { it.show() }
            }
        }, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(space(12))
        content.addView(buildRemoteDisplayCard(), matchWrap())
        content.addView(space(12))

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
            // The whole controller remains scrollable on short phone screens.
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val mark = TextView(this).apply {
            text = "F"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(COLOR_BACKGROUND)
            background = roundedDrawable(COLOR_TEXT, 12)
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(spaceHorizontal(11))

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleColumn.addView(TextView(this).apply {
            text = "FAKEPOD"
            textSize = 22f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleColumn.addView(TextView(this).apply {
            text = "REMOTE CONTROL"
            textSize = 9f
            setTextColor(COLOR_TEXT_MUTED)
            letterSpacing = 0.14f
        })
        row.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(COLOR_TEXT_SECONDARY)
            contentDescription = "设置"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedDrawable(COLOR_SURFACE, 12, COLOR_BORDER)
            stateListAnimator = null
            setOnClickListener { showSettingsDialog() }
        }
        row.addView(settingsButton, LinearLayout.LayoutParams(dp(40), dp(40)))

        return row
    }

    private fun buildConnectionCard(): View {
        statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedDrawable(
                withAlpha(COLOR_ACCENT, 24),
                13,
                withAlpha(COLOR_ACCENT, 64)
            )
        }

        statusDot = View(this).apply {
            background = circleDrawable(COLOR_ACCENT)
        }
        statusPill.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        statusPill.addView(spaceHorizontal(9))

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }
        statusPill.addView(
            statusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return statusPill
    }

    private fun buildNowPlayingCard(): View {
        val card = cardContainer(18)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val artwork = TextView(this).apply {
            text = "♪"
            gravity = Gravity.CENTER
            textSize = 34f
            setTextColor(COLOR_ACCENT)
            background = roundedDrawable(COLOR_SURFACE_HIGH, 18, COLOR_BORDER)
        }
        top.addView(artwork, LinearLayout.LayoutParams(dp(70), dp(70)))
        top.addView(spaceHorizontal(14))

        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleText = TextView(this).apply {
            textSize = 20f
            setTextColor(COLOR_TEXT)
            maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
        }
        metadata.addView(titleText, matchWrap())

        metadata.addView(space(4))
        artistText = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            maxLines = 2
        }
        metadata.addView(artistText, matchWrap())

        metadata.addView(space(7))
        playbackText = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
        }
        metadata.addView(playbackText, matchWrap())

        top.addView(
            metadata,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(top, matchWrap())

        card.addView(space(18))

        progressBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_BORDER)
            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    val current = bleClient.currentSnapshot()
                    localSeek.begin(bleClient.localSeekContext(current), bleClient.estimatedPositionMs(current))
                }
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    localSeek.move(value)
                    localSeek.previewMs?.let { positionText.text = formatTime(it) }
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val current = bleClient.currentSnapshot()
                    val request = localSeek.release(bleClient.localSeekContext(current))
                    if (request != null) {
                        if (bleClient.seekLocal(request)) localSeek.submitted(request, SystemClock.elapsedRealtime())
                        else Toast.makeText(this@MainActivity, "暂时无法定位，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                    updateProgressUi(current, bleClient.estimatedPositionMs(current))
                }
            })
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) localSeek.cancelDrag()
                false
            }
        }
        card.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))

        card.addView(space(8))
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionText = timeText(Gravity.START)
        durationText = timeText(Gravity.END)
        timeRow.addView(positionText, weightedWrap())
        timeRow.addView(durationText, weightedWrap())
        card.addView(timeRow, matchWrap())

        return card
    }

    private fun buildMetricsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        volumeText = metricValueText()
        batteryText = metricValueText()

        row.addView(metricCard(R.drawable.ic_volume, "音量", volumeText), weightedWrap())
        row.addView(spaceHorizontal(12))
        row.addView(metricCard(R.drawable.ic_battery, "电量", batteryText), weightedWrap())
        return row
    }

    private fun buildControlsCard(): View {
        val card = cardContainer(16)

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val previous = controlButton("⏮", FakePodBleClient.CMD_PREVIOUS, primary = false)
        playPauseButton = controlButton("▶", FakePodBleClient.CMD_PLAY_PAUSE, primary = true)
        val next = controlButton("⏭", FakePodBleClient.CMD_NEXT, primary = false)

        transport.addView(previous, LinearLayout.LayoutParams(dp(44), dp(44)))
        transport.addView(spaceHorizontal(26))
        transport.addView(playPauseButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        transport.addView(spaceHorizontal(26))
        transport.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(transport, matchWrap())

        card.addView(space(18))

        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        volumeRow.addView(
            iconView(R.drawable.ic_volume, 20, COLOR_TEXT_SECONDARY),
            LinearLayout.LayoutParams(dp(26), dp(26))
        )
        volumeRow.addView(spaceHorizontal(8))

        volumeSlider = SeekBar(this).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_BORDER)
            thumbTintList = ColorStateList.valueOf(COLOR_TEXT)
            splitTrack = false
            isEnabled = false
            // 0% / 100% 时给 thumb 留出安全区，避免圆点被卡片边缘裁切。
            setPadding(dp(10), 0, dp(10), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    volumeDragging = true
                }

                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) volumeText.text = "$progress%"
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    volumeDragging = false
                    val target = seekBar?.progress ?: return
                    if (bleClient.setVolumePercent(target)) {
                        pendingVolumeTarget = target
                        volumeText.text = "$target%"
                    } else {
                        pendingVolumeTarget = null
                        updateUi(bleClient.currentSnapshot())
                    }
                }
            })
        }
        volumeRow.addView(
            volumeSlider,
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )
        card.addView(volumeRow, matchWrap())

        return card
    }

    private fun buildRemoteDisplayCard(): View {
        val card = cardContainer(16)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        // Available width = screen - outer margins(40) - card padding(32) - gaps(16).
        // Cap each column: wide phones should not stretch a small selector.
        val columnWidth = minOf(dp(100), (resources.displayMetrics.widthPixels - dp(88)) / 3)

        val (viewColumn, viewValue) = remoteDropdown(
            "设备视图", listOf("封面", "磁带", "歌词", "频谱")
        ) { index -> bleClient.setMusicView(index) }
        viewDropdown = viewValue
        row.addView(viewColumn, LinearLayout.LayoutParams(columnWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(spaceHorizontal(8))

        val (loopColumn, loopValue) = remoteDropdown(
            "循环模式", listOf("顺序", "列表", "单曲", "随机")
        ) { index -> bleClient.setLoopMode(index) }
        loopDropdown = loopValue
        row.addView(loopColumn, LinearLayout.LayoutParams(columnWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(spaceHorizontal(8))

        val screenCommands = listOf(
            FakePodBleClient.CMD_WAKE_UNLOCK,
            FakePodBleClient.CMD_LOCK,
            FakePodBleClient.CMD_AOD,
            FakePodBleClient.CMD_SCREEN_OFF
        )
        val (screenColumn, screenValue) = remoteDropdown(
            "设备屏幕", listOf("唤醒", "锁屏", "AOD", "息屏")
        ) { index -> bleClient.sendCommand(screenCommands[index]) }
        screenDropdown = screenValue
        row.addView(screenColumn, LinearLayout.LayoutParams(columnWidth, LinearLayout.LayoutParams.WRAP_CONTENT))

        card.addView(row, matchWrap())
        cassetteControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        cassetteControls.addView(TextView(this).apply {
            text = "封面位置"; textSize = 12f; setTextColor(COLOR_TEXT_SECONDARY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        listOf("↑ 上移", "↓ 下移").forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false
                setTextColor(COLOR_TEXT)
                background = roundedDrawable(COLOR_SURFACE_HIGH, 12, COLOR_BORDER)
                contentDescription = "磁带封面${if (index == 0) "上移" else "下移"}"
                setOnClickListener { bleClient.adjustCassetteCover(index == 1) }
            }
            coverShiftButtons.add(button)
            cassetteControls.addView(button, LinearLayout.LayoutParams(dp(82), dp(40)).apply { leftMargin = dp(8) })
        }
        card.addView(cassetteControls, matchWrap())
        spectrumControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        val (styleColumn, styleValue) = remoteDropdown(
            "频谱模式", listOf("分段柱状", "横向镜像", "霓虹山脊")
        ) { index -> bleClient.setSpectrumStyle(index) }
        spectrumStyleDropdown = styleValue
        spectrumControls.addView(styleColumn, LinearLayout.LayoutParams(dp(160), -2))
        card.addView(spectrumControls, matchWrap())
        return card
    }

    // The popup is only opened by a tap. Device status refresh changes the label
    // without issuing commands; selection still waits for the device's reply.
    private fun remoteDropdown(
        title: String,
        options: List<String>,
        onSelect: (Int) -> Unit
    ): Pair<LinearLayout, TextView> {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_SECONDARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, matchWrap())
        column.addView(space(6))

        val value = TextView(this).apply {
            text = "选择  ▾"
            textSize = 12f
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(5), 0, dp(5), 0)
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedDrawable(COLOR_SURFACE_HIGH, 14, COLOR_BORDER)
            isClickable = true
            isFocusable = true
            contentDescription = "$title，点击选择"
        }
        value.setOnClickListener {
            if (!value.isEnabled) return@setOnClickListener
            val popupContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = roundedDrawable(COLOR_SURFACE_HIGH, 14, COLOR_BORDER)
            }
            // Clamp the popup to the visible window, including short screens
            // and the on-screen keyboard; the choices can scroll if necessary.
            val visibleFrame = Rect()
            value.getWindowVisibleDisplayFrame(visibleFrame)
            val location = IntArray(2)
            value.getLocationOnScreen(location)
            val below = visibleFrame.bottom - (location[1] + value.height) - dp(8)
            val above = location[1] - visibleFrame.top - dp(8)
            val fullHeight = dp(12 + options.size * 38 + (options.size - 1) * 2)
            val popupHeight = minOf(fullHeight, maxOf(below, above).coerceAtLeast(dp(48)))
            val popupScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = popupHeight < fullHeight
                addView(popupContent)
            }
            val popup = PopupWindow(
                popupScroll, dp(118), popupHeight, true
            ).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                elevation = dp(10).toFloat()
            }
            options.forEachIndexed { index, option ->
                val selected = value.text.toString().startsWith(option)
                val item = TextView(this).apply {
                    text = if (selected) "$option  ✓" else option
                    textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                    setTextColor(if (selected) COLOR_ACCENT else COLOR_TEXT)
                    background = roundedDrawable(
                        if (selected) withAlpha(COLOR_ACCENT, 28) else COLOR_SURFACE_HIGH, 9
                    )
                    isClickable = true
                    setOnClickListener {
                        if (value.isEnabled) onSelect(index)
                        popup.dismiss()
                    }
                }
                popupContent.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
                ))
                if (index != options.lastIndex) popupContent.addView(space(2))
            }
            // Align the last selector popup to its right edge on narrow phones.
            val overflow = location[0] + popup.width - visibleFrame.right + dp(12)
            val offset = if (overflow > 0) value.width - popup.width else 0
            popup.showAsDropDown(value, offset, dp(5))
        }
        column.addView(value, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
        ))
        return column to value
    }

    private fun showPhoneCoverDialog() {
        val component = ComponentName(this, FakePodMediaCoverListener::class.java)
        val allowed = if (Build.VERSION.SDK_INT >= 27) {
            getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component) == true
        } else {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.split(":")?.contains(component.flattenToString()) == true
        }
        val enabled = FakePodCoverHub.isEnabled()
        val message = "来源：QQ音乐 / Android MediaSession / 播放通知" +
            "\n通知访问权限：" + (if (allowed) "已授权" else "未授权") +
            "\n手机封面同步：" + (if (enabled) "已开启" else "未开启") +
            "\nBLE 上传状态：" + FakePodCoverHub.status() +
            "\n\n只有用户开启并授权后才会读取媒体封面。传输仅使用 FakePod 已配对的加密 BLE 控制连接，不访问 QQ 音乐账号。"
        AlertDialog.Builder(this)
            .setTitle("手机封面同步 · 诊断")
            .setMessage(message)
            .setPositiveButton(if (enabled) "关闭同步" else "开启同步") { _, _ ->
                FakePodCoverHub.setEnabled(this, !enabled)
                if (enabled) FakePodPhoneCoverService.stop(this)
                else if (hasPermissions()) FakePodPhoneCoverService.start(this)
                if (!enabled && !allowed) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } else if (!enabled) {
                    FakePodMediaCoverListener.refreshIfRunning()
                }
            }
            .setNeutralButton("通知访问设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = roundedDrawable(COLOR_SURFACE, 20, COLOR_BORDER)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "设置"
            textSize = 20f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_SECONDARY)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(header, matchWrap())

        panel.addView(space(12))

        val deviceBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(COLOR_SURFACE_HIGH, 15, COLOR_BORDER)
        }
        settingsStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        settingsAddressText = TextView(this).apply {
            textSize = 10f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, 0)
            letterSpacing = 0.03f
        }
        deviceBox.addView(settingsStatusText, matchWrap())
        deviceBox.addView(settingsAddressText, matchWrap())
        deviceBox.addView(TextView(this).apply {
            text = "蓝牙：首次连接设备和配网时使用，无需输入配对密码。"
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, 0)
        }, matchWrap())
        panel.addView(deviceBox, matchWrap())

        panel.addView(space(12))
        reconnectButton = Button(this).apply {
            text = "重新搜索设备"
            textSize = 13f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            stateListAnimator = null
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedDrawable(COLOR_SURFACE_HIGH, 13, COLOR_BORDER)
            setOnClickListener {
                if (hasPermissions()) {
                    bleClient.forgetAndRescan()
                } else {
                    requestBluetoothPermissions()
                }
            }
        }
        panel.addView(reconnectButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(space(8))
        panel.addView(Button(this).apply {
            text = "手机音乐封面同步 · QQ 音乐诊断（实验）"
            isAllCaps = false
            textSize = 13f
            setTextColor(COLOR_TEXT)
            background = roundedDrawable(COLOR_SURFACE_HIGH, 13, COLOR_BORDER)
            setOnClickListener { showPhoneCoverDialog() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        panel.addView(space(14))
        val wifiBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
            background = roundedDrawable(COLOR_SURFACE_HIGH, 15, COLOR_BORDER)
        }
        wifiBox.addView(TextView(this).apply {
            text = "Wi-Fi 配网"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, matchWrap())
        settingsWifiSsidText = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(6), 0, 0)
        }
        wifiBox.addView(settingsWifiSsidText, matchWrap())
        wifiBox.addView(TextView(this).apply {
            text = "将 FakePod 接入手机当前的 2.4GHz Wi-Fi，用于遥控和播放 NAS 音乐。"
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(5), 0, 0)
        }, matchWrap())
        val wifiPassword = EditText(this).apply {
            hint = "输入当前 Wi-Fi 密码（FakePod 保存一次）"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_MUTED)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedDrawable(COLOR_SURFACE, 11, COLOR_BORDER)
        }
        settingsWifiPassword = wifiPassword
        wifiBox.addView(space(10))
        wifiBox.addView(
            wifiPassword,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        )
        val wifiProvisionButton = Button(this).apply {
            text = "发送到 FakePod"
            textSize = 13f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            stateListAnimator = null
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedDrawable(COLOR_SURFACE, 12, COLOR_BORDER)
            setOnClickListener {
                if (!hasWifiSsidPermission()) {
                    settingsWifiSsidText?.text =
                        "请授权位置并选择“精确”，Android 才会提供当前 Wi-Fi 名称"
                    requestWifiSsidPermission()
                    return@setOnClickListener
                }
                if (!isLocationServiceEnabled()) {
                    settingsWifiSsidText?.text = "请开启系统位置服务后返回 APP"
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    return@setOnClickListener
                }

                val ssid = currentWifiSsid()
                val password = wifiPassword.text?.toString().orEmpty()
                val passwordBytes = password.toByteArray(Charsets.UTF_8).size
                when {
                    ssid.isNullOrBlank() -> {
                        settingsWifiSsidText?.text =
                            "仍未读取到当前 Wi-Fi，请确认手机已连接 Wi-Fi 后重试"
                    }
                    passwordBytes !in 8..63 -> {
                        settingsWifiSsidText?.text = "当前网络 · $ssid · 密码需为 8~63 字节"
                    }
                    !bleClient.provisionWifi(ssid, password) -> {
                        settingsWifiSsidText?.text = "当前网络 · $ssid · FakePod 尚未就绪或正在发送"
                    }
                    else -> {
                        isEnabled = false
                        alpha = 0.55f
                        text = "正在发送…"
                    }
                }
            }
        }
        settingsWifiProvisionButton = wifiProvisionButton
        wifiBox.addView(space(10))
        wifiBox.addView(
            wifiProvisionButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        )
        panel.addView(wifiBox, matchWrap())

        panel.addView(space(10))
        val nasButton = Button(this).apply {
            text = "NAS 音乐服务器 · 浏览目录"
            isAllCaps = false
            textSize = 13f
            setTextColor(COLOR_TEXT)
            background = roundedDrawable(COLOR_SURFACE_HIGH, 13, COLOR_BORDER)
            setOnClickListener { NasWebDavSettingsDialog(this@MainActivity) {
                bleClient.currentSnapshot().takeIf { it.wifiConnected }?.address
            }.show { bleClient.recoverWifiConnection() } }
        }
        panel.addView(nasButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        panel.addView(space(16))
        val divider = View(this).apply { setBackgroundColor(COLOR_BORDER) }
        panel.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        panel.addView(space(13))
        panel.addView(TextView(this).apply {
            text = "FAKEPOD"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, matchWrap())
        panel.addView(TextView(this).apply {
            text = "Version 0.5.45 Cover Art Test  ·  FAKEPOD"
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.58f }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            updateSettingsUi(bleClient.currentSnapshot())
        }
        dialog.setOnDismissListener {
            reconnectButton = null
            settingsStatusText = null
            settingsAddressText = null
            settingsWifiSsidText = null
            settingsWifiPassword = null
            settingsWifiProvisionButton = null
        }
        dialog.show()
    }

    private fun updateSettingsUi(snapshot: FakePodBleClient.Snapshot) {
        if (settingsStatusText == null) return // No hidden SSID/permission/network queries.
        settingsStatusText?.apply {
            text = snapshot.message
            setTextColor(stateColor(snapshot.state))
        }
        settingsAddressText?.text = snapshot.address?.let { "地址  ·  $it" } ?: "地址  ·  尚未绑定"
        val scanning = snapshot.state == FakePodBleClient.State.SCANNING
        reconnectButton?.apply {
            text = if (scanning) "正在搜索…" else "重新搜索设备"
            isEnabled = !scanning
            alpha = if (scanning) 0.55f else 1f
        }

        val ssid = currentWifiSsid()
        settingsWifiSsidText?.text = wifiSsidStatusText(ssid)
        settingsWifiProvisionButton?.apply {
            val sending = snapshot.state == FakePodBleClient.State.WIFI_HANDOFF ||
                snapshot.message.startsWith("正在发送 Wi-Fi")
            text = when {
                snapshot.wifiConnected -> "Wi-Fi 已接管"
                snapshot.state == FakePodBleClient.State.WIFI_HANDOFF -> "正在切换 Wi-Fi…"
                sending -> "正在发送…"
                !hasWifiSsidPermission() -> "授权读取当前网络"
                !isLocationServiceEnabled() -> "开启系统位置"
                ssid == null -> "重新读取当前网络"
                else -> "发送到 FakePod"
            }
            isEnabled = snapshot.state == FakePodBleClient.State.READY && !snapshot.wifiConnected && !sending
            alpha = if (isEnabled) 1f else 0.55f
        }
    }

    private fun metricCard(iconRes: Int, description: String, value: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedDrawable(COLOR_SURFACE, 16, COLOR_BORDER)
            addView(iconView(iconRes, 18, COLOR_TEXT_SECONDARY).apply {
                contentDescription = description
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(spaceHorizontal(10))
            addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun metricValueText() =
        TextView(this).apply {
            textSize = 17f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun controlButton(textValue: String, command: Int, primary: Boolean): Button =
        Button(this).apply {
            text = textValue
            textSize = if (primary) 21f else 17f
            isAllCaps = false
            isEnabled = false
            minHeight = 0
            minWidth = 0
            stateListAnimator = null
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
            background = if (primary) {
                circleDrawable(COLOR_TEXT)
            } else {
                circleDrawable(COLOR_SURFACE_HIGH, COLOR_BORDER)
            }
            setOnClickListener { bleClient.sendCommand(command) }
        }.also(controlButtons::add)

    private fun cardContainer(paddingDp: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            background = roundedDrawable(COLOR_SURFACE, 18, COLOR_BORDER)
            elevation = dp(1).toFloat()
        }

    private fun iconView(iconRes: Int, sizeDp: Int, tint: Int) =
        ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

    private fun timeText(gravityValue: Int) =
        TextView(this).apply {
            textSize = 10f
            setTextColor(COLOR_TEXT_MUTED)
            gravity = gravityValue
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

    private fun updateUi(snapshot: FakePodBleClient.Snapshot) {
        val stateColor = stateColor(snapshot.state)
        statusText.text = snapshot.message
        if (renderedStateColor != stateColor) {
            renderedStateColor = stateColor
            statusDot.background = circleDrawable(stateColor)
            statusPill.background = roundedDrawable(withAlpha(stateColor, 28), 12, withAlpha(stateColor, 76))
        }

        titleText.text = when {
            snapshot.title.isNotBlank() -> snapshot.title
            snapshot.nsfContext -> "正在读取电子音流信息…"
            snapshot.videoContext -> "正在读取视频信息…"
            snapshot.trackIndex != null -> "正在读取歌曲信息…"
            else -> "暂无歌曲"
        }
        artistText.text = when {
            snapshot.artist.isNotBlank() -> snapshot.artist
            snapshot.nsfContext -> "电子音流"
            snapshot.videoContext -> "视频"
            else -> "等待 FakePod 播放内容"
        }
        playbackText.text = if (snapshot.playbackState == 4) {
            when {
                snapshot.nsfContext -> "●  电子音流播放中"
                snapshot.videoContext -> "●  视频播放中"
                else -> "●  正在播放"
            }
        } else {
            val stateName = playbackStateName(snapshot.playbackState)
            when {
                snapshot.nsfContext -> "电子音流 · $stateName"
                snapshot.videoContext -> "视频 · $stateName"
                else -> stateName
            }
        }
        playbackText.setTextColor(
            if (snapshot.playbackState == 4) COLOR_SUCCESS else COLOR_TEXT_SECONDARY
        )

        updateProgressUi(snapshot, bleClient.estimatedPositionMs(snapshot))

        if (pendingVolumeTarget != null && snapshot.volumePercent == pendingVolumeTarget) {
            pendingVolumeTarget = null
        }
        if (snapshot.state != FakePodBleClient.State.READY || snapshot.message.contains("失败")) {
            pendingVolumeTarget = null
        }
        val displayedVolume = pendingVolumeTarget ?: snapshot.volumePercent
        if (!volumeDragging) {
            volumeText.text = displayedVolume?.let {
                "$it%${if (snapshot.muted) "  ·  静音" else ""}"
            } ?: "--"
            volumeSlider.progress = displayedVolume ?: 0
        }
        batteryText.text = snapshot.batteryPercent?.let { "$it%" } ?: "--"

        val ready = snapshot.state == FakePodBleClient.State.READY
        controlButtons.forEach {
            it.isEnabled = ready
            it.alpha = if (ready) 1f else 0.38f
        }
        // Only the device's Music APP foreground can operate these three controls.
        // NSF/Video/Ebook/Settings may still have background audio or a valid title.
        val musicControlsEnabled = ready && snapshot.musicForeground &&
            !snapshot.videoContext && !snapshot.nsfContext
        viewDropdown.isEnabled = musicControlsEnabled
        loopDropdown.isEnabled = musicControlsEnabled
        screenDropdown.isEnabled = musicControlsEnabled
        for (dropdown in listOf(viewDropdown, loopDropdown, screenDropdown)) {
            dropdown.alpha = if (dropdown.isEnabled) 1f else 0.38f
        }
        viewDropdown.text = listOf("封面", "磁带", "歌词", "频谱")
            .getOrNull(snapshot.musicView)?.let { "$it  ▾" } ?: "选择  ▾"
        loopDropdown.text = listOf("顺序", "列表", "单曲", "随机")
            .getOrNull(snapshot.loopMode)?.let { "$it  ▾" } ?: "选择  ▾"
        val screenIndex = when (snapshot.screenPower) {
            0 -> if (snapshot.screenLocked) 1 else 0
            1 -> 2
            2 -> 3
            else -> -1
        }
        screenDropdown.text = listOf("唤醒", "锁屏", "AOD", "息屏")
            .getOrNull(screenIndex)?.let { "$it  ▾" } ?: "选择  ▾"
        val visualEnabled = musicControlsEnabled && snapshot.screenPower == 0 && !snapshot.screenLocked
        cassetteControls.visibility = if (musicControlsEnabled && snapshot.musicView == 1 &&
            snapshot.visualControlFlags and 1 != 0) View.VISIBLE else View.GONE
        coverShiftButtons.forEach { it.isEnabled = visualEnabled; it.alpha = if (visualEnabled) 1f else 0.38f }
        spectrumControls.visibility = if (musicControlsEnabled && snapshot.musicView == 3 &&
            snapshot.visualControlFlags and 2 != 0) View.VISIBLE else View.GONE
        spectrumStyleDropdown.isEnabled = visualEnabled
        spectrumStyleDropdown.alpha = if (visualEnabled) 1f else 0.38f
        spectrumStyleDropdown.text = listOf("分段柱状", "横向镜像", "霓虹山脊")
            .getOrNull(snapshot.spectrumStyle)?.let { "$it  ▾" } ?: "选择  ▾"
        volumeSlider.isEnabled = ready
        volumeSlider.alpha = if (ready) 1f else 0.38f
        playPauseButton.text = if (snapshot.playbackState == 4) "Ⅱ" else "▶"
        updateSettingsUi(snapshot)
    }


    private fun updateProgressUi(
        snapshot: FakePodBleClient.Snapshot,
        positionMs: Long
    ) {
        val context = bleClient.localSeekContext(snapshot)
        if (localSeek.observe(context, SystemClock.elapsedRealtime())) {
            Toast.makeText(this, "定位未确认，已恢复设备进度", Toast.LENGTH_SHORT).show()
        }
        val displayed = localSeek.previewMs ?: positionMs
        positionText.text = formatTime(displayed) + if (localSeek.waiting) " · 定位中" else ""
        durationText.text = formatTime(snapshot.durationMs)
        progressBar.isEnabled = context.allowed && !localSeek.waiting
        progressBar.thumb?.alpha = if (context.allowed || localSeek.previewMs != null) 255 else 0
        progressBar.contentDescription = if (progressBar.isEnabled) "播放进度，可拖动定位" else "播放进度"
        if (!localSeek.dragging) {
            progressBar.progress = if (snapshot.durationMs > 0L) {
                ((displayed.coerceIn(0L, snapshot.durationMs) * 1000L) / snapshot.durationMs).toInt()
            } else 0
        }
    }


    private fun stateColor(state: FakePodBleClient.State): Int =
        when (state) {
            FakePodBleClient.State.READY -> COLOR_SUCCESS
            FakePodBleClient.State.WIFI_HANDOFF -> COLOR_ACCENT
            FakePodBleClient.State.ERROR -> COLOR_ERROR
            FakePodBleClient.State.DISCONNECTED -> COLOR_WARNING
            FakePodBleClient.State.IDLE -> COLOR_TEXT_MUTED
            else -> COLOR_ACCENT
        }

    private fun playbackStateName(state: Int): String =
        when (state) {
            0 -> "音频启动中"
            1 -> "就绪"
            2 -> "正在准备"
            3 -> "已准备"
            4 -> "正在播放"
            5 -> "正在定位"
            6 -> "已暂停"
            7 -> "播放完成"
            8 -> "已停止"
            9 -> "播放错误"
            else -> "等待播放状态"
        }

    private fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0L) return "00:00"
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun roundedDrawable(
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fillColor)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun circleDrawable(fillColor: Int, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun weightedWrap() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun space(heightDp: Int) =
        Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
        }

    private fun spaceHorizontal(widthDp: Int) =
        Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
