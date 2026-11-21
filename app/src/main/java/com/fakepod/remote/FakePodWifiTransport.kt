package com.fakepod.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

class FakePodWifiTransport(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onWifiConnected(address: String)
        fun onWifiDisconnected(reason: String)
        fun onWifiEvent(payload: ByteArray)
    }

    companion object {
        private const val DISCOVERY_PORT = 46320
        private const val CONTROL_PORT = 46321
        private const val DISCOVERY_QUERY = "FAKEPOD_DISCOVER_V1"
        private const val DISCOVERY_REPLY = "FAKEPOD_REMOTE_V1"
        private const val FRAME_MAGIC = 0xA5
        private const val FRAME_STALE_TIMEOUT_MS = 3000L
        private const val PREFS_NAME = "fakepod_wifi_remote"
        private const val PREF_LAST_ADDRESS = "last_address"
    }

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val commandQueue = LinkedBlockingQueue<ByteArray>(32)
    private val lock = Any()
    private val discoveryWake = java.lang.Object()

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var remoteAddress: String? = null
    private var worker: Thread? = null
    private var activeSocket: Socket? = null
    @Volatile private var discoveryEnabled = true
    @Volatile private var generation = 0L
    @Volatile private var retryReset = false

    fun setDiscoveryEnabled(enabled: Boolean) {
        discoveryEnabled = enabled
        if (enabled) retryReset = true
        synchronized(discoveryWake) { discoveryWake.notifyAll() }
        worker?.interrupt()
    }

    private fun current(epoch: Long) = running && generation == epoch

    fun start() {
        synchronized(lock) {
            if (running && worker?.isAlive == true) return
            running = true
            val epoch = ++generation
            worker = Thread({ runLoop(epoch) }, "FakePodWifiRemote").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        val notify = synchronized(lock) {
            running = false
            generation++
            try { activeSocket?.close() } catch (_: Exception) {}
            activeSocket = null
            worker?.interrupt()
            worker = null
            commandQueue.clear()
            val previous = connected
            connected = false
            remoteAddress = null
            previous
        }
        if (notify) listener.onWifiDisconnected("遥控已停止")
    }

    /** Keep a healthy session; wake discovery or replace a dead worker after long NAS work. */
    fun recoverConnection() {
        start()
        retryReset = true
        if (!connected) worker?.interrupt()
    }

    fun isConnected(): Boolean = connected

    fun address(): String? = remoteAddress

    // Short receive polls only while a library page stream is active. Commands
    // queued by the main-thread parser should not wait 250ms for every page.
    fun setLibraryTransferActive(active: Boolean) {
        synchronized(lock) {
            try { activeSocket?.soTimeout = if (active) 20 else 250 } catch (_: Exception) {}
        }
    }

    fun send(payload: ByteArray): Boolean = synchronized(lock) {
        if (!connected || payload.isEmpty() || payload.size > 32) false
        else commandQueue.offer(payload.copyOf())
    }

    private fun currentWifiNetwork(): Network? {
        val manager = connectivity ?: return null
        val active = manager.activeNetwork
        if (active != null) {
            val caps = manager.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active
        }
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun broadcastAddresses(network: Network): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        result.add(InetAddress.getByName("255.255.255.255"))
        val links = connectivity?.getLinkProperties(network)?.linkAddresses.orEmpty()
        for (link in links) {
            val ipv4 = link.address as? Inet4Address ?: continue
            val prefix = link.prefixLength
            if (prefix !in 1..31) continue
            val raw = ipv4.address
            var value = 0
            for (b in raw) value = (value shl 8) or (b.toInt() and 0xFF)
            val mask = -1 shl (32 - prefix)
            val broadcast = value or mask.inv()
            val bytes = byteArrayOf(
                (broadcast ushr 24).toByte(),
                (broadcast ushr 16).toByte(),
                (broadcast ushr 8).toByte(),
                broadcast.toByte()
            )
            result.add(InetAddress.getByAddress(bytes))
        }
        return result.toList()
    }

    private fun lastKnownAddress(): InetAddress? {
        val raw = prefs.getString(PREF_LAST_ADDRESS, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            InetAddress.getByName(raw) as? Inet4Address
        } catch (_: Exception) {
            null
        }
    }

    private fun discoveryAddresses(network: Network): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        lastKnownAddress()?.let(result::add)
        result.addAll(broadcastAddresses(network))
        return result.toList()
    }

    private fun rememberAddress(address: String) {
        if (prefs.getString(PREF_LAST_ADDRESS, null) == address) return
        prefs.edit().putString(PREF_LAST_ADDRESS, address).apply()
    }

    private fun discover(network: Network, epoch: Long): InetAddress? {
        val socket = DatagramSocket(null)
        return try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 350
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(0))
            val query = DISCOVERY_QUERY.toByteArray(Charsets.US_ASCII)
            for (address in discoveryAddresses(network)) {
                try {
                    socket.send(DatagramPacket(query, query.size, address, DISCOVERY_PORT))
                } catch (_: Exception) {}
            }

            val deadline = SystemClock.elapsedRealtime() + 900L
            val buffer = ByteArray(64)
            while (current(epoch) && discoveryEnabled && SystemClock.elapsedRealtime() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val reply = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (reply == DISCOVERY_REPLY) return packet.address
                } catch (_: SocketTimeoutException) {
                    // One discovery burst per round; receive until the deadline.
                }
            }
            null
        } finally {
            socket.close()
        }
    }

    private fun connect(network: Network, address: InetAddress): Socket? {
        val socket = Socket()
        return try {
            network.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = 250
            socket.connect(InetSocketAddress(address, CONTROL_PORT), 1500)
            socket
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
            null
        }
    }

    private fun sendFrame(output: BufferedOutputStream, payload: ByteArray) {
        output.write(FRAME_MAGIC)
        output.write(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun runConnected(socket: Socket, address: String, epoch: Long) {
        val input = BufferedInputStream(socket.getInputStream(), 512)
        val output = BufferedOutputStream(socket.getOutputStream(), 256)
        synchronized(lock) {
            if (!current(epoch) || activeSocket !== socket) return
            connected = true
            remoteAddress = address
            listener.onWifiConnected(address)
        }
        var lastFrameAt = SystemClock.elapsedRealtime()
        var addressRemembered = false
        val reader = WifiFrameReader()
        var reason = "播放器关闭连接"
        var operation = "接收状态"
        try {
            while (current(epoch) && !socket.isClosed) {
                while (true) {
                    val command = commandQueue.poll() ?: break
                    operation = "发送控制指令"
                    sendFrame(output, command)
                }
                operation = "接收状态"
                val frame = reader.poll(input) ?: break
                if (frame.isNotEmpty()) {
                    lastFrameAt = SystemClock.elapsedRealtime()
                    if (!addressRemembered) {
                        rememberAddress(address)
                        addressRemembered = true
                    }
                    operation = "处理状态"
                    listener.onWifiEvent(frame)
                } else if (SystemClock.elapsedRealtime() - lastFrameAt >= FRAME_STALE_TIMEOUT_MS) {
                    reason = "连续3秒无完整状态（半帧${reader.pendingBytes()}字节）"
                    break
                }
            }
        } catch (error: Exception) {
            reason = "$operation：${error.javaClass.simpleName} ${error.message.orEmpty()}"
        } finally {
            try { socket.close() } catch (_: Exception) {}
            val wasConnected = synchronized(lock) {
                if (activeSocket !== socket) false else {
                    activeSocket = null
                    commandQueue.clear()
                    val previous = connected
                    connected = false
                    remoteAddress = null
                    previous
                }
            }
            Log.w("FakePodWifiRemote", "TCP disconnected: $reason; lastFrameAgeMs=${SystemClock.elapsedRealtime() - lastFrameAt}; address=$address")
            if (wasConnected) listener.onWifiDisconnected(reason)
        }
    }

    private fun runLoop(epoch: Long) {
        var failures = 0
        var previousNetwork: Network? = null
        while (current(epoch)) {
            if (retryReset) { failures = 0; retryReset = false }
            if (!discoveryEnabled) {
                synchronized(discoveryWake) {
                    while (current(epoch) && !discoveryEnabled) {
                        try { discoveryWake.wait() } catch (_: InterruptedException) {}
                    }
                }
                continue
            }
            val network = try { currentWifiNetwork() } catch (_: SecurityException) { null }
            if (network != previousNetwork) { failures = 0; previousNetwork = network }
            if (network == null) {
                sleepQuietly(RemotePowerPolicy.retryDelay(failures++))
                continue
            }
            // The last validated player can reconnect even when UDP broadcasts are lost.
            val known = lastKnownAddress()
            var socket = known?.let { connect(network, it) }
            val address = if (socket != null) known else try { discover(network, epoch) } catch (_: Exception) { null }
            if (address == null) {
                sleepQuietly(RemotePowerPolicy.retryDelay(failures++))
                continue
            }
            if (socket == null) socket = connect(network, address)
            if (socket == null) {
                sleepQuietly(RemotePowerPolicy.retryDelay(failures++))
                continue
            }
            val accepted = synchronized(lock) {
                if (!current(epoch) || !discoveryEnabled) false
                else { activeSocket = socket; true }
            }
            if (!accepted) { try { socket.close() } catch (_: Exception) {}; continue }
            val sessionStarted = SystemClock.elapsedRealtime()
            try {
                runConnected(socket, address.hostAddress ?: address.toString(), epoch)
            } catch (error: Exception) {
                // Stream setup or a callback must not permanently kill reconnection.
                try { socket.close() } catch (_: Exception) {}
                val wasConnected = synchronized(lock) {
                    if (activeSocket !== socket) false else {
                        activeSocket = null
                        commandQueue.clear()
                        val previous = connected
                        connected = false
                        remoteAddress = null
                        previous
                    }
                }
                if (wasConnected) try { listener.onWifiDisconnected("连接初始化：${error.javaClass.simpleName}") } catch (_: Exception) {}
            }
            if (SystemClock.elapsedRealtime() - sessionStarted >= 10_000L) failures = 0
            if (current(epoch)) sleepQuietly(RemotePowerPolicy.retryDelay(failures++))
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
