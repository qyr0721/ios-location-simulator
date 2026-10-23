package com.iosloc.spoofer

import android.content.Context
import com.iosloc.spoofer.usb.UsbMuxDaemon
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the stateful half of the app:
 *
 *   UsbMuxDaemon  (USB -> local usbmuxd on 127.0.0.1:27015)
 *   go-ios        (pair, CoreDeviceProxy userspace tunnel on 127.0.0.1:28100)
 *   locsim        (one persistent DVT LocationSimulation session, stdin commands)
 *
 * Route playback writes "S lat lon" lines to locsim instead of spawning a new
 * process per point.
 */
object GoIosSession {

    private lateinit var app: Context
    private var daemon: UsbMuxDaemon? = null
    private var tunnel: Process? = null
    private var locsim: Process? = null
    private var locsimIn: BufferedWriter? = null
    @Volatile private var tunnelReady = false
    @Volatile private var locsimReady = false
    private val connectLock = Any()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val history = ArrayDeque<String>()
    private val started = AtomicBoolean(false)

    fun init(context: Context) {
        app = context.applicationContext
        if (started.compareAndSet(false, true)) {
            startDaemon()
        }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        synchronized(history) { history.forEach { listener(it) } }
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun log(message: String) {
        synchronized(history) {
            history.addLast(message)
            while (history.size > 500) history.removeFirst()
        }
        listeners.forEach { runCatching { it(message) } }
    }

    private fun startDaemon() {
        if (daemon != null) return
        val d = UsbMuxDaemon(app) { msg -> log("[usbmuxd] $msg") }
        d.start()
        daemon = d
    }

    fun udid(): String? {
        startDaemon()
        return daemon?.udid()
    }

    fun hasPairRecord(udid: String): Boolean =
        File(app.filesDir, "pair/$udid.plist").exists()

    fun isReady(): Boolean =
        locsimReady && locsim?.isAlive == true && tunnel?.isAlive == true

    fun connect() {
        Thread {
            synchronized(connectLock) {
                try {
                    doConnect()
                } catch (t: Throwable) {
                    log("连接失败: ${t.message}")
                }
            }
        }.start()
    }

    private fun doConnect() {
        startDaemon()
        val udid = daemon?.udid()
        if (udid.isNullOrEmpty()) {
            log("未检测到 iPhone：检查 OTG 连接和 USB 权限")
            return
        }
        if (!hasPairRecord(udid)) {
            log("首次连接：请在 iPhone 上点“信任”并输入锁屏密码…")
            if (!runPair()) {
                log("配对失败（iPhone 需解锁并点信任）")
                return
            }
        }
        log("启动隧道…")
        if (!startTunnelBlocking()) {
            log("隧道启动失败，请查看上方日志")
            return
        }
        log("启动定位会话…")
        if (!startLocsimBlocking()) {
            log("定位会话启动失败，请查看上方日志")
            return
        }
        log("已连接，可以设置位置或播放路线")
    }

    private fun runPair(): Boolean {
        val p = startProcess("pair", listOf("pair")) ?: return false
        val finished = try {
            p.waitFor(120, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) {
            p.destroy()
            log("配对超时")
            return false
        }
        return p.exitValue() == 0
    }

    private fun startTunnelBlocking(): Boolean {
        if (tunnel?.isAlive == true && tunnelReady) return true
        tunnelReady = false
        val p = startProcess(
            "tunnel",
            listOf(
                "tunnel", "start", "--userspace",
                "--tunnel-info-host=127.0.0.1", "--tunnel-info-port=28100"
            )
        ) ?: return false
        tunnel = p
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (tunnelReady) return true
            if (!p.isAlive) return false
            Thread.sleep(200)
        }
        return false
    }

    private fun startLocsimBlocking(): Boolean {
        if (locsim?.isAlive == true && locsimReady) return true
        locsimReady = false
        val bin = File(app.applicationInfo.nativeLibraryDir, "liblocsim.so")
        if (!bin.exists()) {
            log("缺少 liblocsim.so")
            return false
        }
        val p = try {
            val pb = ProcessBuilder(bin.absolutePath)
                .directory(app.filesDir)
                .redirectErrorStream(true)
            pb.environment().apply { putAll(baseEnv()) }
            pb.start()
        } catch (t: Throwable) {
            log("locsim 启动失败: ${t.message}")
            return false
        }
        locsim = p
        locsimIn = BufferedWriter(OutputStreamWriter(p.outputStream))
        Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        log("[locsim] $line")
                        if (line.contains("\"status\":\"ready\"")) locsimReady = true
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (locsimReady) return true
            if (!p.isAlive) return false
            Thread.sleep(200)
        }
        return false
    }

    fun setLocation(lat: Double, lon: Double): Boolean {
        val writer = locsimIn ?: return false
        return try {
            writer.write("S %.6f %.6f\n".format(lat, lon))
            writer.flush()
            true
        } catch (t: Throwable) {
            log("写入定位失败: ${t.message}")
            false
        }
    }

    fun stopLocation() {
        val writer = locsimIn
        locsimIn = null
        locsimReady = false
        try {
            writer?.write("X\n")
            writer?.flush()
            writer?.close()
        } catch (_: Throwable) {
        }
        val p = locsim
        locsim = null
        Thread {
            try {
                if (p != null && !p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
            } catch (_: Throwable) {
                p?.destroy()
            }
        }.start()
    }

    fun shutdown() {
        stopLocation()
        try { tunnel?.destroy() } catch (_: Throwable) {}
        tunnel = null
        tunnelReady = false
        try { daemon?.stop() } catch (_: Throwable) {}
        daemon = null
    }

    private fun startProcess(tag: String, args: List<String>): Process? {
        val bin = File(app.applicationInfo.nativeLibraryDir, "libgoios.so")
        if (!bin.exists()) {
            log("缺少 libgoios.so")
            return null
        }
        val p = try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
                .directory(app.filesDir)
                .redirectErrorStream(true)
            pb.environment().apply { putAll(baseEnv()) }
            pb.start()
        } catch (t: Throwable) {
            log("$tag 启动失败: ${t.message}")
            return null
        }
        Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        log("[$tag] $line")
                        if (tag == "tunnel" && line.contains("userspace tunnel negotiated")) {
                            tunnelReady = true
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
        return p
    }

    private fun baseEnv(): Map<String, String> = mapOf(
        "USBMUXD_SOCKET_ADDRESS" to "tcp://127.0.0.1:27015",
        "HOME" to app.filesDir.absolutePath,
        "TMPDIR" to app.cacheDir.absolutePath,
        "PATH" to "/system/bin:/system/xbin",
        "LANG" to "en_US.UTF-8"
    )
}
