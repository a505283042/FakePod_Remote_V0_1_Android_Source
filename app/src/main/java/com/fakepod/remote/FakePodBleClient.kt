package com.fakepod.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

class FakePodBleClient(private val context: Context) {

    enum class State {
        IDLE,
        SCANNING,
        PAIRING,
        CONNECTING,
        DISCOVERING,
        READY,
        WIFI_HANDOFF,
        DISCONNECTED,
        ERROR
    }

    data class Snapshot(
        val state: State,
        val message: String,
        val address: String? = null,
        val playbackState: Int = -1,
        val trackIndex: Long? = null,
        val title: String = "",
        val artist: String = "",
        val volumePercent: Int? = null,
        val batteryPercent: Int? = null,
        val muted: Boolean = false,
        val videoContext: Boolean = false,
        val nsfContext: Boolean = false,
        val musicForeground: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val positionSyncElapsedMs: Long = 0L,
        val metadataSequence: Int = -1,
        val wifiConnected: Boolean = false,
        val loopMode: Int = -1,
        val musicView: Int = -1,
        val screenPower: Int = -1,
        val nasContext: Boolean = false,
        val localSeekEligible: Boolean = false,
        val seekRemoteSupported: Boolean = false,
        val seekFeedbackEligible: Boolean = false,
        val seekTrack: Long? = null,
        val seekPlaybackRevision: Long = 0L,
        val seekRevision: Long = 0L,
        val lastSeekTargetMs: Long = 0L,
        val statusSerial: Long = 0L,
        val seekFeedbackSerial: Long = -1L,
        val visualControlFlags: Int = 0,
        val spectrumStyle: Int = -1,
        val screenLocked: Boolean = false
    )

    fun interface Listener {
        fun onSnapshot(snapshot: Snapshot)
    }

    companion object {
        private const val TAG_BLE = "FakePodBle"
        val SERVICE_UUID: UUID =
            UUID.fromString("7d7a9c10-2b4f-4b68-8f3a-0e6d46506f64")
        val COMMAND_UUID: UUID =
            UUID.fromString("7d7a9c11-2b4f-4b68-8f3a-0e6d46506f64")
        val EVENT_UUID: UUID =
            UUID.fromString("7d7a9c12-2b4f-4b68-8f3a-0e6d46506f64")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val EVENT_PROTOCOL_VERSION = 1
        private const val EVENT_STATUS = 0x01
        private const val EVENT_METADATA = 0x02

        const val CMD_PLAY_PAUSE = 0x01
        const val CMD_NEXT = 0x02
        const val CMD_PREVIOUS = 0x03
        const val CMD_VOLUME_UP = 0x04
        const val CMD_VOLUME_DOWN = 0x05
        const val CMD_SET_VOLUME = 0x06
        const val CMD_SET_LOOP_MODE = 0x10
        const val CMD_SET_MUSIC_VIEW = 0x11
        const val CMD_WAKE_UNLOCK = 0x12
        const val CMD_LOCK = 0x13
        const val CMD_AOD = 0x14
        const val CMD_SCREEN_OFF = 0x15
        const val CMD_CASSETTE_COVER_SHIFT = 0x16
        const val CMD_SPECTRUM_STYLE = 0x17
        const val CMD_LOCAL_SEEK = 0x18
        const val CMD_WIFI_PROVISION = 0x20

        private const val WIFI_PROVISION_CHUNK_BYTES = 16
        private const val WIFI_PROVISION_MAX_BYTES = 96

        private const val PREFS = "fakepod_remote"
        private const val PREF_ADDRESS = "device_address"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val COMMAND_CONFIRMATION_MS = 2_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var listener: Listener? = null
    private var gatt: BluetoothGatt? = null
    private var gattAutoConnect = false
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var reconnectEnabled = true
    private var remoteActive = true
    private var bleTransferActive = false
    private var requestedBlePriority: Int? = null
    private val backgroundStop = Runnable {
        remoteActive = false
        reconnectEnabled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScanInternal()
        wifiTransport.stop()
        wifiReady = false
        wifiRecoveryPending = false
        wifiProvisionHandoffPending = false
        pendingDevice = null
        closeGatt()
        musicLibrary.onConnection(false)
        publishCurrentSnapshot(snapshot.copy(state = State.DISCONNECTED,
            message = "遥控已暂停，打开 APP 自动恢复", wifiConnected = false,
            musicForeground = false, visualControlFlags = 0, spectrumStyle = -1,
            seekRemoteSupported = false, seekTrack = null, seekPlaybackRevision = 0L))
    }

    fun setRemoteForeground(foreground: Boolean) {
        mainHandler.removeCallbacks(backgroundStop)
        if (foreground) {
            remoteActive = true
            start()
        } else mainHandler.postDelayed(backgroundStop, RemotePowerPolicy.BACKGROUND_GRACE_MS)
    }
    private var writeInFlight = false
    private var subscribeRetryAttempts = 0
    private var pendingDevice: BluetoothDevice? = null
    private var lastCommand: Int? = null
    private var lastVolumeTarget: Int? = null
    private var commandMessageGeneration = 0L
    private val wifiProvisionFrames = ArrayDeque<ByteArray>()
    private var wifiProvisionTransaction = 0
    private var wifiProvisionHandoffPending = false
    private var wifiProvisionSsid = ""
    @Volatile private var wifiReady = false
    @Volatile private var wifiRecoveryPending = false
    private fun wifiSessionConnected(): Boolean = wifiTransport.isConnected()
    private val wifiTransport: FakePodWifiTransport = FakePodWifiTransport(
        context,
        object : FakePodWifiTransport.Listener {
            override fun onWifiConnected(address: String) {
                mainHandler.post { if (remoteActive && wifiSessionConnected()) handleWifiConnected(address) }
            }

            override fun onWifiDisconnected(reason: String) {
                mainHandler.post { if (!wifiSessionConnected()) handleWifiDisconnected(reason) }
            }

            override fun onWifiEvent(payload: ByteArray) {
                mainHandler.post { if (wifiReady) handleEventPacket(payload) }
            }
        }
    )

    private val bleLibraryFrames = java.util.ArrayDeque<ByteArray>()
    private val bleLibraryAssembler = BleLibraryAssembler()
    private val bleLibraryPump = object: Runnable {
        override fun run() {
            if(wifiReady || snapshot.state!=State.READY){bleLibraryFrames.clear();return}
            val frame=bleLibraryFrames.peekFirst()?:return
            if(writeInFlight){mainHandler.postDelayed(this,20);return}
            if(!writeCommand(0x31,frame)) {
                bleLibraryFrames.clear();musicLibrary.onTransportFailure()
            }
        }
    }
    private fun sendBleLibrary(payload: ByteArray): Boolean {
        if(snapshot.state!=State.READY || wifiReady || bleLibraryFrames.size>2)return false
        BleLibraryAssembler.requestFragments(payload).forEach {bleLibraryFrames.addLast(it)}
        mainHandler.post(bleLibraryPump)
        return true
    }
    @SuppressLint("MissingPermission")
    private fun updateBlePriority() {
        if (wifiReady || !remoteActive || snapshot.state != State.READY) return
        val priority = if (bleTransferActive) BluetoothGatt.CONNECTION_PRIORITY_BALANCED else BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        if (requestedBlePriority != priority && gatt?.requestConnectionPriority(priority) == true)
            requestedBlePriority = priority
    }
    internal val musicLibrary = MusicLibraryClient(context, { active ->
        wifiTransport.setLibraryTransferActive(active && wifiReady)
        bleTransferActive = active
        updateBlePriority()
    }) { payload ->
        if(wifiReady)wifiTransport.send(payload) else sendBleLibrary(payload)
    }

    @SuppressLint("MissingPermission")
    private fun handleWifiConnected(address: String) {
        if (!remoteActive) { wifiTransport.stop(); return }
        wifiReady = true
        musicLibrary.onConnection(true)
        wifiRecoveryPending = false
        reconnectEnabled = true
        wifiProvisionHandoffPending = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(scanTimeout)
        stopScanInternal()
        // Wi-Fi owns the remote state; stale BLE callbacks must become irrelevant.
        closeGatt()
        publishCurrentSnapshot(snapshot.copy(
            state = State.READY,
            message = "FakePod Wi-Fi 已连接",
            address = address,
            wifiConnected = true,
            musicForeground = false,
            visualControlFlags = 0, spectrumStyle = -1,
            seekRemoteSupported = false, seekTrack = null, seekPlaybackRevision = 0L
        ))
    }

    private fun handleWifiDisconnected(reason: String) {
        if (!wifiReady) return
        wifiReady = false
        musicLibrary.onConnection(false)
        wifiRecoveryPending = true
        resetMetadataAssembly()
        publishCurrentSnapshot(snapshot.copy(
            state = State.DISCONNECTED,
            message = "Wi-Fi 控制断开：$reason；自动重连中…",
            wifiConnected = false,
            musicForeground = false,
            visualControlFlags = 0, spectrumStyle = -1,
            seekRemoteSupported = false, seekTrack = null, seekPlaybackRevision = 0L
        ))
        scheduleReconnect()
    }

    private var metadataAssemblySequence = -1
    private var metadataAssemblyChunks: Array<ByteArray?> = emptyArray()

    private var snapshot =
        Snapshot(State.IDLE, "等待连接 FakePod")

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScanInternal()
            if (!wifiReady && !wifiRecoveryPending) {
                publish(State.ERROR, "没有找到 FakePod，请确认设备已开机并开启 BLE")
            }
        }
    }

    private val reconnectRunnable = Runnable {
        if (remoteActive && reconnectEnabled && !wifiReady) {
            connectSavedDevice(autoConnect = true)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val target = pendingDevice ?: return
            if (device.address != target.address) return

            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingDevice = null
                    publish(State.CONNECTING, "配对完成，正在连接 FakePod…", device.address)
                    connectDevice(device, autoConnect = false)
                }

                BluetoothDevice.BOND_NONE -> {
                    pendingDevice = null
                    publish(State.ERROR, "配对未完成，请重新搜索 FakePod", device.address)
                }
            }
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> handleBluetoothAdapterOff()

                BluetoothAdapter.STATE_ON -> handleBluetoothAdapterOn()
            }
        }
    }

    init {
        registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
        registerReceiver(
            adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Bluetooth/Bond 系统广播可能由高权限蓝牙系统应用发送。
            // RECEIVER_NOT_EXPORTED 会漏掉这类广播，导致蓝牙开关状态无法及时同步。
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun setListener(newListener: Listener?) {
        listener = newListener
        newListener?.onSnapshot(snapshot)
    }

    fun currentSnapshot(): Snapshot = snapshot

    fun estimatedPositionMs(
        source: Snapshot = snapshot,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        var position = source.positionMs
        if (source.state == State.READY &&
            source.playbackState == 4 &&
            source.positionSyncElapsedMs > 0L &&
            nowElapsedMs > source.positionSyncElapsedMs) {
            position += nowElapsedMs - source.positionSyncElapsedMs
        }
        return if (source.durationMs > 0L) {
            position.coerceIn(0L, source.durationMs)
        } else {
            position.coerceAtLeast(0L)
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun recoverWifiConnection() {
        if (!remoteActive) return
        // A working BLE session excludes Wi-Fi on the player. Do not probe both radios.
        if (snapshot.state == State.READY && !wifiReady) return
        wifiTransport.recoverConnection()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!remoteActive) return
        wifiTransport.setDiscoveryEnabled(!(gatt != null && snapshot.state == State.READY && !wifiReady))
        wifiTransport.start()
        if (wifiReady) return
        if (!hasRequiredPermissions()) {
            publish(State.ERROR, "需要“附近设备”蓝牙权限")
            return
        }

        val currentAdapter = adapter
        if (currentAdapter == null) {
            publish(State.ERROR, "这台手机不支持蓝牙")
            return
        }
        if (!currentAdapter.isEnabled) {
            handleBluetoothAdapterOff()
            return
        }

        // Activity 重新打开时不要销毁仍在工作的 GATT 会话。Application 持有的
        // autoConnect 会话应继续等待设备出现，READY/连接中也无需重复 connectGatt。
        if (gatt != null && snapshot.state in setOf(
                State.CONNECTING,
                State.DISCOVERING,
                State.READY,
                State.WIFI_HANDOFF,
                State.DISCONNECTED
            )) {
            reconnectEnabled = true
            return
        }

        reconnectEnabled = true
        val savedAddress = prefs.getString(PREF_ADDRESS, null)
        if (savedAddress.isNullOrBlank()) {
            scanNow()
        } else {
            connectSavedDevice(autoConnect = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothAdapterOff() {
        reconnectEnabled = true
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(scanTimeout)
        scanning = false
        pendingDevice = null
        closeGatt()
        if (!wifiReady) {
            publish(
                State.DISCONNECTED,
                "手机蓝牙已关闭，打开后将自动连接 FakePod",
                prefs.getString(PREF_ADDRESS, null) ?: snapshot.address
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothAdapterOn() {
        if (!remoteActive || wifiReady) return
        if (!hasRequiredPermissions()) {
            publish(State.ERROR, "需要“附近设备”蓝牙权限")
            return
        }

        reconnectEnabled = true
        mainHandler.removeCallbacks(reconnectRunnable)
        closeGatt()

        if (prefs.getString(PREF_ADDRESS, null).isNullOrBlank()) {
            scanNow()
        } else {
            connectSavedDevice(autoConnect = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun scanNow() {
        if (wifiReady) return
        if (!hasRequiredPermissions()) {
            publish(State.ERROR, "需要“附近设备”蓝牙权限")
            return
        }

        reconnectEnabled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        closeGatt()
        pendingDevice = null

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            publish(State.ERROR, "BLE 扫描器不可用，请确认蓝牙已开启")
            return
        }

        if (scanning) stopScanInternal()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        publish(State.SCANNING, "正在搜索 FakePod…")
        scanner.startScan(listOf(filter), settings, scanCallback)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        mainHandler.removeCallbacks(scanTimeout)
    }

    @SuppressLint("MissingPermission")
    private fun connectSavedDevice(autoConnect: Boolean) {
        if (wifiReady) return
        if (!hasRequiredPermissions()) return

        val address = prefs.getString(PREF_ADDRESS, null)
        if (address.isNullOrBlank()) {
            scanNow()
            return
        }

        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }

        if (device == null) {
            prefs.edit().remove(PREF_ADDRESS).apply()
            scanNow()
            return
        }

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            prefs.edit().remove(PREF_ADDRESS).apply()
            scanNow()
            return
        }

        reconnectEnabled = true
        publish(
            State.CONNECTING,
            if (autoConnect) "等待 FakePod 自动连接…" else "正在连接 FakePod…",
            address
        )
        connectDevice(device, autoConnect)
    }

    @SuppressLint("MissingPermission")
    private fun beginDevice(device: BluetoothDevice) {
        if (wifiReady) return
        stopScanInternal()
        prefs.edit().putString(PREF_ADDRESS, device.address).apply()

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            reconnectEnabled = true
            publish(State.CONNECTING, "正在连接 FakePod…", device.address)
            connectDevice(device, autoConnect = false)
            return
        }

        pendingDevice = device
        publish(State.PAIRING, "正在请求配对 FakePod…", device.address)
        val started = device.createBond()
        if (!started) {
            pendingDevice = null
            reconnectEnabled = true
            publish(State.CONNECTING, "正在连接 FakePod…", device.address)
            connectDevice(device, autoConnect = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice, autoConnect: Boolean) {
        closeGatt()
        gattAutoConnect = autoConnect

        gatt = device.connectGatt(
            context,
            autoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: Int): Boolean {
        if (command !in CMD_PLAY_PAUSE..CMD_VOLUME_DOWN &&
            command !in CMD_WAKE_UNLOCK..CMD_SCREEN_OFF) return false
        if (command in CMD_WAKE_UNLOCK..CMD_SCREEN_OFF && !musicUiAvailable()) return false
        if (wifiReady) {
            val ok = wifiTransport.send(byteArrayOf(command.toByte()))
            if (ok) publishCommandConfirmation("FakePod Wi-Fi 已连接 · 已发送 ${commandName(command)}", snapshot.address.orEmpty())
            return ok
        }
        return writeCommand(command, byteArrayOf(command.toByte()))
    }

    fun musicUiAvailable(): Boolean =
        snapshot.state == State.READY && snapshot.musicForeground &&
            !snapshot.videoContext && !snapshot.nsfContext

    fun setLoopMode(mode: Int): Boolean = sendModeCommand(CMD_SET_LOOP_MODE, mode)
    fun setMusicView(view: Int): Boolean = sendModeCommand(CMD_SET_MUSIC_VIEW, view)

    internal fun localSeekContext(current: Snapshot = snapshot): LocalSeekContext {
        val connected = current.state == State.READY
        val allowed = connected && current.musicForeground && !current.videoContext && !current.nsfContext &&
            (!current.nasContext || wifiReady) && current.localSeekEligible && current.seekRemoteSupported && current.seekFeedbackEligible &&
            current.trackIndex != null && current.trackIndex == current.seekTrack && current.seekPlaybackRevision != 0L &&
            current.durationMs > 0L && current.playbackState in listOf(4, 6)
        return LocalSeekContext(connected, allowed, current.trackIndex, current.seekPlaybackRevision,
            current.seekRevision, current.lastSeekTargetMs, current.durationMs, current.statusSerial,
            current.seekFeedbackSerial, current.playbackState,
            current.musicForeground && !current.videoContext && !current.nsfContext, current.nasContext)
    }

    internal fun seekLocal(request: LocalSeekRequest): Boolean {
        val current = localSeekContext()
        if (request.nasSource && !wifiReady) return false
        if (!current.allowed || current.nasSource != request.nasSource || current.track != request.track || current.playbackRevision != request.playbackRevision ||
            current.seekRevision != request.seekRevision || request.targetMs >= current.durationMs) return false
        val payload = LocalSeekWire.encode(request) ?: return false
        return if (wifiReady) wifiTransport.send(payload) else writeCommand(CMD_LOCAL_SEEK, payload)
    }

    fun adjustCassetteCover(down: Boolean): Boolean =
        sendVisualCommand(CMD_CASSETTE_COVER_SHIFT, if (down) 1 else 0, 1, 1)

    fun setSpectrumStyle(style: Int): Boolean =
        style in 0..2 && sendVisualCommand(CMD_SPECTRUM_STYLE, style, 3, 2)

    private fun sendVisualCommand(command: Int, value: Int, view: Int, capability: Int): Boolean {
        if (!musicUiAvailable() || snapshot.musicView != view ||
            snapshot.visualControlFlags and capability == 0 ||
            snapshot.screenPower != 0 || snapshot.screenLocked) return false
        val payload = byteArrayOf(command.toByte(), value.toByte())
        return if (wifiReady) wifiTransport.send(payload) else writeCommand(command, payload)
    }

    private fun sendModeCommand(command: Int, value: Int): Boolean {
        if (value !in 0..3 || !musicUiAvailable()) return false
        val payload = byteArrayOf(command.toByte(), value.toByte())
        if (wifiReady) {
            val ok = wifiTransport.send(payload)
            if (ok) publishCommandConfirmation("FakePod Wi-Fi · 已发送 ${commandName(command)}", snapshot.address.orEmpty())
            return ok
        }
        return writeCommand(command, payload)
    }

    fun provisionWifi(ssid: String, password: String): Boolean {
        if (snapshot.state != State.READY || writeInFlight) return false

        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        if (ssidBytes.isEmpty() || ssidBytes.size > 32 || passwordBytes.size > 63) return false

        val combined = ByteArray(ssidBytes.size + 1 + passwordBytes.size)
        ssidBytes.copyInto(combined, 0)
        combined[ssidBytes.size] = 0
        passwordBytes.copyInto(combined, ssidBytes.size + 1)
        if (combined.size > WIFI_PROVISION_MAX_BYTES) return false

        wifiProvisionFrames.clear()
        wifiProvisionTransaction = (wifiProvisionTransaction + 1) and 0xFF
        if (wifiProvisionTransaction == 0) wifiProvisionTransaction = 1
        var offset = 0
        while (offset < combined.size) {
            val chunkSize = minOf(WIFI_PROVISION_CHUNK_BYTES, combined.size - offset)
            val frame = ByteArray(4 + chunkSize)
            frame[0] = CMD_WIFI_PROVISION.toByte()
            frame[1] = wifiProvisionTransaction.toByte()
            frame[2] = combined.size.toByte()
            frame[3] = offset.toByte()
            combined.copyInto(frame, 4, offset, offset + chunkSize)
            wifiProvisionFrames.addLast(frame)
            offset += chunkSize
        }

        wifiProvisionSsid = ssid
        wifiProvisionHandoffPending = false
        publish(State.READY, "正在发送 Wi-Fi 配置…", snapshot.address)
        return sendNextWifiProvisionFrame()
    }

    private fun sendNextWifiProvisionFrame(): Boolean {
        val frame = wifiProvisionFrames.peekFirst() ?: return false
        return writeCommand(CMD_WIFI_PROVISION, frame)
    }

    fun setVolumePercent(targetPercent: Int): Boolean {
        if (snapshot.state != State.READY) return false
        val target = targetPercent.coerceIn(0, 100)
        if (wifiReady) {
            val ok = wifiTransport.send(byteArrayOf(CMD_SET_VOLUME.toByte(), target.toByte()))
            if (ok) publishCommandConfirmation("FakePod Wi-Fi 已连接 · 音量 $target%", snapshot.address.orEmpty())
            return ok
        }
        return writeCommand(
            CMD_SET_VOLUME,
            byteArrayOf(CMD_SET_VOLUME.toByte(), target.toByte()),
            volumeTarget = target
        )
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(
        command: Int,
        value: ByteArray,
        volumeTarget: Int? = null
    ): Boolean {
        if (!hasRequiredPermissions()) return false

        val activeGatt = gatt ?: return false
        val characteristic = commandCharacteristic ?: return false
        if (snapshot.state != State.READY || writeInFlight) return false

        lastCommand = command
        lastVolumeTarget = volumeTarget
        writeInFlight = true

        val initiated = if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(characteristic)
        }

        if (!initiated) {
            writeInFlight = false
            lastCommand = null
            lastVolumeTarget = null
            publish(State.READY, "命令发送启动失败", activeGatt.device.address)
        }
        return initiated
    }

    @SuppressLint("MissingPermission")
    fun forgetAndRescan() {
        wifiProvisionHandoffPending = false
        wifiProvisionFrames.clear()
        reconnectEnabled = false
        prefs.edit().remove(PREF_ADDRESS).apply()
        closeGatt()
        scanNow()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        resetGattSession()
        gatt?.close()
        gatt = null
        gattAutoConnect = false
    }

    private fun resetGattSession() {
        // A discarded BLE session no longer owns the discovery pause. Wi-Fi
        // takeover and background suspension must not start another worker.
        if (remoteActive && !wifiReady) {
            wifiTransport.setDiscoveryEnabled(true)
            wifiTransport.start()
        }
        mainHandler.post {
            mainHandler.removeCallbacks(bleLibraryPump);bleLibraryFrames.clear();bleLibraryAssembler.reset()
            if(!wifiReady)musicLibrary.onConnection(false)
        }
        requestedBlePriority = null
        bleTransferActive = false
        commandCharacteristic = null
        eventCharacteristic = null
        writeInFlight = false
        subscribeRetryAttempts = 0
        lastCommand = null
        lastVolumeTarget = null
        wifiProvisionFrames.clear()
        resetMetadataAssembly()
    }

    private fun publish(
        state: State,
        message: String,
        address: String? = snapshot.address
    ) {
        // BLE fallback may keep trying, but cannot hide Wi-Fi readiness or its
        // actual disconnect cause with a later scan/permission/GATT error.
        if (state != State.READY && (wifiReady || wifiRecoveryPending)) return
        publishCurrentSnapshot(
            snapshot.copy(
                state = state,
                message = message,
                address = address,
                musicForeground = if (state == State.READY) snapshot.musicForeground else false
            )
        )
    }

    private fun publishCurrentSnapshot(next: Snapshot) {
        snapshot = next
        mainHandler.post {
            listener?.onSnapshot(next)
        }
    }

    private fun resetMetadataAssembly() {
        metadataAssemblySequence = -1
        metadataAssemblyChunks = emptyArray()
    }

    private fun readU16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32Le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)

    private fun handleStatusPacket(data: ByteArray) {
        if (data.size < 20 || (data[1].toInt() and 0xFF) != EVENT_PROTOCOL_VERSION) return

        val playbackStateByte = data[2].toInt() and 0xFF
        val rawTrackIndex = readU32Le(data, 6)
        val trackIndex = if (rawTrackIndex == 0xFFFF_FFFFL) null else rawTrackIndex
        val metadataSequence = readU16Le(data, 18)
        val videoContext = (data[5].toInt() and 0x08) != 0
        val nsfContext = (data[5].toInt() and 0x10) != 0
        val musicForeground = (data[5].toInt() and 0x20) != 0
        val trackChanged = snapshot.trackIndex != trackIndex
        val contextChanged =
            snapshot.videoContext != videoContext || snapshot.nsfContext != nsfContext

        val next = snapshot.copy(
            nasContext = data[5].toInt() and 0x40 != 0,
            localSeekEligible = data[5].toInt() and 0x80 != 0,
            statusSerial = snapshot.statusSerial + 1L,
            playbackState = if (playbackStateByte == 0xFF) -1 else playbackStateByte,
            trackIndex = trackIndex,
            title = if (trackChanged || contextChanged) "" else snapshot.title,
            artist = if (trackChanged || contextChanged) "" else snapshot.artist,
            volumePercent = if (playbackStateByte == 0xFF) null else data[3].toInt() and 0xFF,
            batteryPercent = (data[4].toInt() and 0xFF).let { if (it == 0xFF) null else it },
            muted = (data[5].toInt() and 0x01) != 0,
            videoContext = videoContext,
            nsfContext = nsfContext,
            musicForeground = musicForeground,
            positionMs = readU32Le(data, 10),
            durationMs = readU32Le(data, 14),
            positionSyncElapsedMs = SystemClock.elapsedRealtime(),
            metadataSequence = metadataSequence
        )

        if (metadataAssemblySequence != metadataSequence) resetMetadataAssembly()
        if(trackChanged || contextChanged || snapshot.nasContext!=next.nasContext ||
            snapshot.musicForeground!=next.musicForeground)musicLibrary.onPlaybackChanged()
        publishCurrentSnapshot(next)
    }

    private fun handleRemoteUiPacket(data: ByteArray) {
        if (data.size != 6 || (data[1].toInt() and 0xFF) != EVENT_PROTOCOL_VERSION) return
        val loop = data[2].toInt() and 0xFF
        val view = data[3].toInt() and 0xFF
        val power = data[4].toInt() and 0xFF
        val lock = data[5].toInt() and 0xFF
        if (loop > 3 || view > 3 || power > 2 || lock > 1) return
        publishCurrentSnapshot(snapshot.copy(
            loopMode = loop,
            musicView = view,
            screenPower = power,
            screenLocked = lock == 1
        ))
    }

    private fun handleVisualControlsPacket(data: ByteArray) {
        if (data.size != 4 || (data[1].toInt() and 0xFF) != EVENT_PROTOCOL_VERSION) return
        val flags = data[2].toInt() and 0xFF
        val style = data[3].toInt() and 0xFF
        if (style !in 0..2) return
        publishCurrentSnapshot(snapshot.copy(visualControlFlags = flags and 3, spectrumStyle = style))
    }

    private fun handleLocalSeekPacket(data: ByteArray) {
        val feedback = LocalSeekWire.decode(data) ?: return
        publishCurrentSnapshot(snapshot.copy(
            seekRemoteSupported = feedback.flags and 1 != 0,
            seekFeedbackEligible = feedback.flags and 2 != 0,
            seekTrack = feedback.track, seekPlaybackRevision = feedback.playbackRevision,
            seekRevision = feedback.seekRevision, lastSeekTargetMs = feedback.lastTargetMs,
            seekFeedbackSerial = snapshot.statusSerial
        ))
    }

    private fun handleMetadataPacket(data: ByteArray) {
        if (data.size < 6 || (data[1].toInt() and 0xFF) != EVENT_PROTOCOL_VERSION) return

        val sequence = readU16Le(data, 2)
        val chunkIndex = data[4].toInt() and 0xFF
        val chunkCount = data[5].toInt() and 0xFF
        if (chunkCount == 0 || chunkIndex >= chunkCount) return
        if (snapshot.metadataSequence >= 0 && sequence != snapshot.metadataSequence) return

        if (metadataAssemblySequence != sequence || metadataAssemblyChunks.size != chunkCount) {
            metadataAssemblySequence = sequence
            metadataAssemblyChunks = arrayOfNulls(chunkCount)
        }

        val bytes = data.copyOfRange(6, data.size)
        if (metadataAssemblyChunks[chunkIndex]?.contentEquals(bytes) == true) return
        metadataAssemblyChunks[chunkIndex] = bytes
        if (metadataAssemblyChunks.any { it == null }) return

        var totalSize = 0
        metadataAssemblyChunks.forEach { chunk -> totalSize += chunk?.size ?: 0 }
        val merged = ByteArray(totalSize)
        var offset = 0
        metadataAssemblyChunks.forEach { chunk ->
            val bytes = chunk ?: return@forEach
            bytes.copyInto(merged, destinationOffset = offset)
            offset += bytes.size
        }

        val text = merged.toString(Charsets.UTF_8)
        val separator = text.indexOf('\n')
        val title = if (separator >= 0) text.substring(0, separator) else text
        val artist = if (separator >= 0) text.substring(separator + 1) else ""
        publishCurrentSnapshot(snapshot.copy(title = title, artist = artist))
    }

    private fun handleEventPacket(data: ByteArray) {
        if (data.size < 2) return
        when (data[0].toInt() and 0xFF) {
            EVENT_STATUS -> handleStatusPacket(data)
            EVENT_METADATA -> handleMetadataPacket(data)
            0x03 -> handleRemoteUiPacket(data)
            0x04 -> handleVisualControlsPacket(data)
            0x05 -> handleLocalSeekPacket(data)
            0x06 -> if (wifiReady) musicLibrary.onFrame(data)
            0x07 -> if(!wifiReady)mainHandler.post {
                if(wifiReady)return@post
                try {
                    val frame=bleLibraryAssembler.accept(data)
                    musicLibrary.onTransportProgress()
                    if(frame!=null)musicLibrary.onFrame(frame)
                } catch(e: IllegalArgumentException) {
                    bleLibraryAssembler.reset();musicLibrary.onTransportFailure()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableEventNotifications(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!callbackGatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false

        return if (Build.VERSION.SDK_INT >= 33) {
            callbackGatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            callbackGatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readEncryptedEventSnapshot(callbackGatt: BluetoothGatt): Boolean {
        val characteristic = eventCharacteristic ?: return false
        return callbackGatt.readCharacteristic(characteristic)
    }

    private fun markReady(callbackGatt: BluetoothGatt) {
        if (!remoteActive || wifiReady) return
        wifiTransport.setDiscoveryEnabled(false)
        wifiRecoveryPending = false
        reconnectEnabled = true
        wifiReady = false
        publishCurrentSnapshot(
            snapshot.copy(
                state = State.READY,
                message = "FakePod 蓝牙已连接",
                address = callbackGatt.device.address,
                wifiConnected = false,
                visualControlFlags = 0, spectrumStyle = -1,
                seekRemoteSupported = false, seekTrack = null, seekPlaybackRevision = 0L,
                musicForeground = false
            )
        )
        updateBlePriority()
    }

    private fun handleEventRead(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (callbackGatt !== gatt || characteristic.uuid != EVENT_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            publish(
                State.ERROR,
                "初始状态读取失败：status=$status",
                callbackGatt.device.address
            )
            return
        }
        handleEventPacket(value)
        markReady(callbackGatt)
        mainHandler.post {if(callbackGatt===gatt && !wifiReady)musicLibrary.onConnection(true,bleLocalOnly=true)}
    }

    private fun scheduleReconnect() {
        if (!remoteActive || !reconnectEnabled) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun commandName(command: Int): String =
        when (command) {
            CMD_PLAY_PAUSE -> "播放/暂停"
            CMD_NEXT -> "下一曲"
            CMD_PREVIOUS -> "上一曲"
            CMD_VOLUME_UP -> "音量+"
            CMD_VOLUME_DOWN -> "音量-"
            CMD_SET_VOLUME -> "音量设置"
            CMD_SET_LOOP_MODE -> "循环模式"
            CMD_SET_MUSIC_VIEW -> "音乐视图"
            CMD_WAKE_UNLOCK -> "亮屏并解锁"
            CMD_LOCK -> "锁屏"
            CMD_AOD -> "AOD"
            CMD_SCREEN_OFF -> "息屏"
            CMD_CASSETTE_COVER_SHIFT -> "封面位置"
            CMD_SPECTRUM_STYLE -> "频谱模式"
            CMD_LOCAL_SEEK -> "定位"
            CMD_WIFI_PROVISION -> "Wi-Fi配网"
            else -> "未知命令"
        }

    private fun publishCommandConfirmation(message: String, address: String) {
        val generation = ++commandMessageGeneration
        publish(State.READY, message, address)
        mainHandler.postDelayed({
            if (commandMessageGeneration == generation &&
                snapshot.state == State.READY &&
                snapshot.message == message) {
                publish(State.READY, if (wifiReady) "FakePod Wi-Fi 已连接" else "FakePod 蓝牙已连接", address)
            }
        }, COMMAND_CONFIRMATION_MS)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            beginDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(scanTimeout)
            publish(State.ERROR, "BLE 扫描失败：$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            callbackGatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (!remoteActive || callbackGatt !== gatt) {
                callbackGatt.close()
                return
            }

            when {
                status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                    val returnedFromWifiHandoff = wifiProvisionHandoffPending
                    wifiProvisionHandoffPending = false
                    // 每次物理链路重连都重新发现服务，旧 characteristic 句柄不能复用。
                    resetGattSession()
                    publish(
                        State.DISCOVERING,
                        if (returnedFromWifiHandoff)
                            "Wi-Fi 未连接成功，BLE 已恢复，正在重新同步…"
                        else
                            "已连接，正在发现 FakePod 控制服务…",
                        callbackGatt.device.address
                    )
                    callbackGatt.discoverServices()
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val wifiHandoff = wifiProvisionHandoffPending
                    resetGattSession()

                    if (wifiHandoff) {
                        wifiTransport.start()
                        publish(
                            State.WIFI_HANDOFF,
                            "FakePod 正在连接 Wi-Fi · 若失败会自动恢复 BLE",
                            callbackGatt.device.address
                        )
                        if (gattAutoConnect && reconnectEnabled) return

                        callbackGatt.close()
                        if (callbackGatt === gatt) {
                            gatt = null
                            gattAutoConnect = false
                        }
                        scheduleReconnect()
                        return
                    }

                    if (gattAutoConnect && reconnectEnabled) {
                        // autoConnect=true 的 GATT 必须保持打开；Android 会在设备重新进入
                        // 范围后自动建立链路。这里 close() 会直接取消这次自动重连注册。
                        publish(
                            State.DISCONNECTED,
                            "FakePod 已离线，回到范围后将自动连接…",
                            callbackGatt.device.address
                        )
                        return
                    }

                    publish(
                        State.DISCONNECTED,
                        "FakePod 已断开，正在切换自动重连…",
                        callbackGatt.device.address
                    )
                    callbackGatt.close()
                    if (callbackGatt === gatt) {
                        gatt = null
                        gattAutoConnect = false
                    }
                    scheduleReconnect()
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    resetGattSession()
                    publish(
                        State.ERROR,
                        "BLE 连接失败：status=$status，准备自动重连…",
                        callbackGatt.device.address
                    )
                    callbackGatt.close()
                    if (callbackGatt === gatt) {
                        gatt = null
                        gattAutoConnect = false
                    }
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(
            callbackGatt: BluetoothGatt,
            status: Int
        ) {
            if (callbackGatt !== gatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                publish(
                    State.ERROR,
                    "GATT 服务发现失败：status=$status",
                    callbackGatt.device.address
                )
                return
            }

            val service: BluetoothGattService? = callbackGatt.getService(SERVICE_UUID)
            val command = service?.getCharacteristic(COMMAND_UUID)
            val event = service?.getCharacteristic(EVENT_UUID)

            if (command == null || event == null) {
                publish(
                    State.ERROR,
                    "FakePod 控制服务版本不匹配，请确认固件为 R46.0.15",
                    callbackGatt.device.address
                )
                return
            }

            commandCharacteristic = command
            eventCharacteristic = event
            publish(
                State.DISCOVERING,
                "已发现控制服务，正在订阅状态…",
                callbackGatt.device.address
            )
            if (!enableEventNotifications(callbackGatt, event)) {
                publish(
                    State.ERROR,
                    "订阅 FakePod 状态失败",
                    callbackGatt.device.address
                )
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (callbackGatt !== gatt ||
                descriptor.uuid != CCCD_UUID ||
                descriptor.characteristic.uuid != EVENT_UUID) {
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 已配对设备重连时 Android 会立即发起链路加密，CCCD 写入常与加密流程
                // 竞争而被本地结束（常见 status=133）。等加密完成后再试几次即可成功。
                if (subscribeRetryAttempts < 3 && callbackGatt === gatt && remoteActive) {
                    subscribeRetryAttempts++
                    val attempt = subscribeRetryAttempts
                    Log.w(TAG_BLE, "状态订阅写入失败 status=$status，第 $attempt 次重试")
                    publish(
                        State.DISCOVERING,
                        "状态订阅重试中（status=$status）…",
                        callbackGatt.device.address
                    )
                    mainHandler.postDelayed({
                        if (callbackGatt !== gatt || !remoteActive) return@postDelayed
                        // status=1/257（无效句柄）说明手机缓存的属性句柄已过期，
                        // 重写旧描述符没有意义，必须重新发现服务拿到新句柄。
                        if (status == 1 || status == 257) {
                            commandCharacteristic = null
                            eventCharacteristic = null
                            Log.w(TAG_BLE, "状态订阅句柄失效，重新发现服务（第 $attempt 次）")
                            if (!callbackGatt.discoverServices()) {
                                publish(
                                    State.ERROR,
                                    "服务重新发现失败",
                                    callbackGatt.device.address
                                )
                            }
                            return@postDelayed
                        }
                        val event = eventCharacteristic ?: return@postDelayed
                        if (!enableEventNotifications(callbackGatt, event)) {
                            publish(
                                State.ERROR,
                                "订阅 FakePod 状态失败",
                                callbackGatt.device.address
                            )
                        }
                    }, 400L * attempt)
                    return
                }
                Log.w(TAG_BLE, "状态订阅写入失败 status=$status，放弃重试")
                publish(
                    State.ERROR,
                    "状态订阅写入失败：status=$status",
                    callbackGatt.device.address
                )
                return
            }
            subscribeRetryAttempts = 0

            // Event characteristic 要求加密读取。读取一次既取得初始快照，也让已有 Bond
            // 在重连后恢复链路加密；后续实时数据全部走 Notify。
            if (!readEncryptedEventSnapshot(callbackGatt)) {
                publish(
                    State.ERROR,
                    "读取 FakePod 初始状态失败",
                    callbackGatt.device.address
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            handleEventRead(
                callbackGatt,
                characteristic,
                characteristic.value ?: byteArrayOf(),
                status
            )
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleEventRead(callbackGatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (callbackGatt === gatt && characteristic.uuid == EVENT_UUID) {
                handleEventPacket(characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (callbackGatt === gatt && characteristic.uuid == EVENT_UUID) {
                handleEventPacket(value)
            }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (callbackGatt !== gatt || characteristic.uuid != COMMAND_UUID) return

            val command = lastCommand
            val volumeTarget = lastVolumeTarget
            writeInFlight = false
            lastCommand = null
            lastVolumeTarget = null

            if(command==0x31) {
                mainHandler.post {
                    if(callbackGatt!==gatt || wifiReady)return@post
                    if(status!=BluetoothGatt.GATT_SUCCESS) {
                        bleLibraryFrames.clear();bleLibraryAssembler.reset();musicLibrary.onTransportFailure()
                    } else {
                        if(bleLibraryFrames.isNotEmpty())bleLibraryFrames.removeFirst()
                        musicLibrary.onTransportProgress()
                        mainHandler.post(bleLibraryPump)
                    }
                }
                return
            }
            if (command == CMD_WIFI_PROVISION) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    wifiProvisionFrames.clear()
                    publish(
                        State.READY,
                        "Wi-Fi 配置发送失败：status=$status",
                        callbackGatt.device.address
                    )
                    return
                }

                if (wifiProvisionFrames.isNotEmpty()) wifiProvisionFrames.removeFirst()
                if (wifiProvisionFrames.isNotEmpty()) {
                    if (!sendNextWifiProvisionFrame()) {
                        wifiProvisionFrames.clear()
                        publish(State.READY, "Wi-Fi 配置分片发送失败", callbackGatt.device.address)
                    }
                    return
                }

                wifiProvisionHandoffPending = true
                publish(
                    State.WIFI_HANDOFF,
                    "Wi-Fi 配置已发送 · $wifiProvisionSsid · 正在切换链路",
                    callbackGatt.device.address
                )
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val message = if (command == CMD_SET_VOLUME && volumeTarget != null) {
                    "FakePod 蓝牙已连接 · 音量 $volumeTarget%"
                } else if (command != null) {
                    "FakePod 蓝牙已连接 · 已发送 ${commandName(command)}"
                } else {
                    "FakePod 蓝牙已连接 · 命令已发送"
                }
                publishCommandConfirmation(message, callbackGatt.device.address)
            } else {
                publish(
                    State.READY,
                    "命令写入失败：status=$status",
                    callbackGatt.device.address
                )
            }
        }
    }
}
