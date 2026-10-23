package com.iosloc.spoofer.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.iosloc.spoofer.usb.UsbMuxDaemon
import java.io.File

/**
 * iOS 17+ location test UI.
 *
 * This activity does not speak the Apple protocols itself. It runs the bundled
 * go-ios binary (arm64) and talks to a usbmuxd endpoint:
 *
 *   USBMUXD_SOCKET_ADDRESS=tcp://127.0.0.1:27016
 *
 * For the first proof-of-concept the endpoint is provided by adb reverse:
 *
 *   adb reverse tcp:27016 tcp:27015
 *
 * i.e. the iPhone is still attached to the PC, and the phone's localhost:27016
 * is forwarded to the PC's Apple usbmuxd. The next step replaces this with the
 * app's own USB -> usbmuxd bridge on port 27015.
 */
class GoIosActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val logBuf = StringBuilder()

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText

    private var tunnel: Process? = null
    private var location: Process? = null

    private lateinit var usbManager: UsbManager
    private var muxDaemon: UsbMuxDaemon? = null

    private val usbmuxdAddress = "tcp://127.0.0.1:27015"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            append("USB 权限结果: ${if (granted) "已授予" else "被拒绝"}")
            if (granted) {
                dumpUsb()
                startLocalBridge()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver()
        append("就绪。iPhone 请用 OTG 接到本机。")
        append("首次流程：1 检查设备 → 2 配对设备（iPhone 上点“信任”）→ 3 启动隧道 → 4 设置位置。")
        append("本机内置 usbmuxd：127.0.0.1:27015")
        checkBinary()
        startLocalBridge()
        requestUsbPermissionIfNeeded()
    }

    private fun buildUi(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "iOS 17+ 定位（本机 USB 桥）"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })

        tvStatus = TextView(this).apply {
            text = "状态：未连接"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(tvStatus)

        root.addView(row(
            button("1. 检查设备") { startCommand("list", "list") },
            button("2. 配对设备") { startCommand("pair", "pair") },
            button("3. 启动隧道") { startTunnel() }
        ))

        root.addView(row(
            button("停止隧道") { stopTunnel() },
            button("隧道状态") {
                startCommand(
                    "tunnel-ls", "tunnel", "ls",
                    "--tunnel-info-host=127.0.0.1", "--tunnel-info-port=28100"
                )
            },
            button("USB 诊断") { dumpUsb() }
        ))

        root.addView(row(
            button("USB 权限") { requestUsbPermissionIfNeeded() },
            button("启动本地桥") { startLocalBridge() },
            button("停止本地桥") { stopLocalBridge() }
        ))

        root.addView(row(
            button("清空日志") { clearLog() }
        ))

        val coordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        etLat = EditText(this).apply {
            hint = "纬度"
            setText("30.572815")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        etLon = EditText(this).apply {
            hint = "经度"
            setText("104.066800")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        coordRow.addView(etLat, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        coordRow.addView(etLon, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(coordRow)

        root.addView(row(
            button("4. 设置位置") { setLocation() },
            button("停止定位") { stopLocation() },
            button("恢复真实定位") { resetLocation() }
        ))

        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(245, 245, 245))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            addView(tvLog)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)
            )
        }
        root.addView(logScroll)

        return ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        }
    }

    private fun row(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { v ->
                addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun checkBinary() {
        val bin = File(applicationInfo.nativeLibraryDir, "libgoios.so")
        append("go-ios: ${bin.absolutePath} exists=${bin.exists()} canExec=${bin.canExecute()}")
        if (!bin.exists()) {
            tvStatus.text = "状态：缺少 libgoios.so"
        }
    }

    private fun startCommand(label: String, vararg args: String): Process? {
        val bin = File(applicationInfo.nativeLibraryDir, "libgoios.so")
        if (!bin.exists()) {
            append("[$label] 缺少 go-ios 二进制")
            return null
        }
        append("$ $label: ${args.joinToString(" ")}")
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("USBMUXD_SOCKET_ADDRESS", usbmuxdAddress)
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                put("PATH", "/system/bin:/system/xbin")
                put("LANG", "en_US.UTF-8")
            }
            val p = pb.start()
            Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> append("[$label] $line") }
                    }
                } catch (t: Throwable) {
                    append("[$label] 读取输出失败: ${t.message}")
                }
                val code = try {
                    p.waitFor()
                } catch (_: Throwable) {
                    -1
                }
                append("[$label] 退出: $code")
            }.start()
            p
        } catch (t: Throwable) {
            append("[$label] 启动失败: ${t.message}")
            null
        }
    }

    private fun startTunnel() {
        if (tunnel?.isAlive == true) {
            append("隧道进程已在运行")
            return
        }
        tunnel = startCommand(
            "tunnel",
            "tunnel", "start", "--userspace",
            "--tunnel-info-host=127.0.0.1", "--tunnel-info-port=28100"
        )
        tvStatus.text = "状态：隧道启动中（看日志）"
    }

    private fun stopTunnel() {
        startCommand(
            "tunnel-stop", "tunnel", "stop",
            "--tunnel-info-host=127.0.0.1", "--tunnel-info-port=28100"
        )
        startCommand(
            "tunnel-agent", "tunnel", "stopagent",
            "--tunnel-info-host=127.0.0.1", "--tunnel-info-port=28100"
        )
        tunnel?.destroy()
        tunnel = null
        tvStatus.text = "状态：隧道已停止"
    }

    private fun setLocation() {
        val lat = etLat.text.toString().trim()
        val lon = etLon.text.toString().trim()
        if (lat.toDoubleOrNull() == null || lon.toDoubleOrNull() == null) {
            append("坐标格式错误")
            return
        }
        stopLocation(silent = true)
        location = startCommand(
            "loc", "setlocation",
            "--tunnel-info-host=127.0.0.1", "--tunnel-info-port=28100",
            "--lat=$lat", "--lon=$lon"
        )
        tvStatus.text = "状态：模拟定位  $lat, $lon（保持 App 前台）"
    }

    private fun stopLocation(silent: Boolean = false) {
        val p = location ?: return
        location = null
        // java.lang.Process.pid() is a Java 9 API; android.jar exposes it, but
        // some Kotlin/Android toolchains fail to resolve it. Use reflection so
        // this compiles everywhere, and fall back to destroy() if unavailable.
        val pid = try {
            (Process::class.java.getMethod("pid").invoke(p) as Long).toString()
        } catch (_: Throwable) {
            null
        }
        try {
            if (pid != null) {
                Runtime.getRuntime()
                    .exec(arrayOf("/system/bin/kill", "-2", pid))
                    .waitFor()
            } else {
                p.destroy()
            }
        } catch (_: Throwable) {
            p.destroy()
        }
        if (!silent) append("已发送 SIGINT，停止定位会话")
        tvStatus.text = "状态：已停止定位"
    }

    private fun resetLocation() {
        stopLocation(silent = true)
        append("已停止 DVT 会话。若手机仍显示模拟位置，重启 iPhone 即可恢复。")
        tvStatus.text = "状态：已停止定位"
    }

    private fun startLocalBridge() {
        if (muxDaemon != null) {
            append("本地 usbmuxd 已在运行")
            return
        }
        val d = UsbMuxDaemon(this) { msg -> append("[usbmuxd] $msg") }
        d.start()
        muxDaemon = d
    }

    private fun stopLocalBridge() {
        muxDaemon?.stop()
        muxDaemon = null
        append("本地 usbmuxd 已停止")
    }

    private fun registerUsbReceiver() {
        val flag = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flag
        )
    }

    private fun requestUsbPermissionIfNeeded() {
        val dev = usbManager.deviceList.values.firstOrNull { it.vendorId == APPLE_VID }
        if (dev == null) {
            append("未发现 Apple USB 设备（iPhone 是否已用 OTG 接到本机？）")
            return
        }
        if (usbManager.hasPermission(dev)) {
            append("已有 USB 权限: PID=0x${dev.productId.toString(16)}")
            dumpUsb()
            return
        }
        append("请求 USB 权限…")
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(dev, pi)
    }

    private fun dumpUsb() {
        val devices = usbManager.deviceList.values.toList()
        append("USB 设备数: ${devices.size}")
        for (d in devices) {
            val apple = d.vendorId == APPLE_VID
            val serial = runCatching { d.serialNumber }.getOrNull() ?: "-"
            append(
                "${if (apple) "[Apple] " else ""}VID=0x${d.vendorId.toString(16)} " +
                    "PID=0x${d.productId.toString(16)} ifaces=${d.interfaceCount} serial=$serial"
            )
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                append(
                    "  iface ${f.id} class=0x${f.interfaceClass.toString(16)} " +
                        "sub=0x${f.interfaceSubclass.toString(16)} proto=${f.interfaceProtocol} " +
                        "eps=${f.endpointCount}"
                )
            }
        }
    }

    private fun clearLog() {
        logBuf.setLength(0)
        tvLog.text = ""
    }

    private fun append(msg: String) {
        ui.post {
            if (logBuf.length > 150_000) {
                logBuf.delete(0, logBuf.length - 80_000)
            }
            logBuf.append(msg).append('\n')
            tvLog.text = logBuf
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        try {
            stopLocation(silent = true)
            tunnel?.destroy()
            muxDaemon?.stop()
            muxDaemon = null
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.iosloc.spoofer.USB_PERMISSION"
        private const val APPLE_VID = 0x05AC
    }
}
