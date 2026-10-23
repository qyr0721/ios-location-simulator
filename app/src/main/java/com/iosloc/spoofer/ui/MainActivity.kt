package com.iosloc.spoofer.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iosloc.spoofer.databinding.ActivityMainBinding
import com.iosloc.spoofer.ddi.DdiBundle
import com.iosloc.spoofer.ddi.DdiLoader
import com.iosloc.spoofer.ios.DeviceManager
import com.iosloc.spoofer.location.GeoPoint
import com.iosloc.spoofer.location.RoutePlayer
import com.iosloc.spoofer.usb.AppleUsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var deviceManager: DeviceManager

    private val routePoints = mutableListOf<GeoPoint>()
    private lateinit var routeAdapter: RoutePointsAdapter
    private var routePlayer: RoutePlayer? = null

    /** "point" | "route" - which panel requested the map picker. */
    private var mapTarget = "point"
    private var ddiUri: Uri? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                log("已获得 USB 权限")
                connect()
            } else {
                log("用户拒绝了 USB 权限")
            }
        }
    }

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val lat = result.data?.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN) ?: Double.NaN
        val lon = result.data?.getDoubleExtra(MapPickerActivity.EXTRA_LON, Double.NaN) ?: Double.NaN
        if (lat.isNaN() || lon.isNaN()) return@registerForActivityResult
        if (mapTarget == "point") {
            b.etLat.setText("%.6f".format(lat))
            b.etLon.setText("%.6f".format(lon))
        } else {
            routePoints.add(GeoPoint(lat, lon))
            routeAdapter.notifyItemInserted(routePoints.size - 1)
        }
    }

    private val ddiPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        ddiUri = uri
        b.tvDdi.text = uri.lastPathSegment ?: "已选择 DDI 目录"
        log("已选择 DDI 目录，挂载时将读取文件")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        deviceManager = DeviceManager(this)

        registerUsbReceiver()
        setupTabs()
        setupRoute()
        setupButtons()
        b.tvLog.movementMethod = ScrollingMovementMethod()
        b.tvSpeed.text = "${b.sbSpeed.progress} km/h"
        b.sbSpeed.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(p0: android.widget.SeekBar?, p1: Int, p2: Boolean) {
                b.tvSpeed.text = "$p1 km/h"
            }
        })

        // Auto-connect if the phone was launched by USB_DEVICE_ATTACHED.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            log("检测到 USB 设备插入")
            requestUsbPermissionAndConnect()
        }
    }

    private fun registerUsbReceiver() {
        val flag = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.RECEIVER_NOT_EXPORTED else 0
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flag
        )
    }

    private fun setupTabs() {
        b.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val isPoint = tab.position == 0
                b.cardPoint.visibility = if (isPoint) View.VISIBLE else View.GONE
                b.cardRoute.visibility = if (isPoint) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun setupRoute() {
        routeAdapter = RoutePointsAdapter(routePoints) { pos ->
            routePoints.removeAt(pos)
            routeAdapter.notifyItemRemoved(pos)
            routeAdapter.notifyItemRangeChanged(pos, routePoints.size)
        }
        b.rvPoints.layoutManager = LinearLayoutManager(this)
        b.rvPoints.adapter = routeAdapter
    }

    private fun setupButtons() {
        b.btnConnect.setOnClickListener { requestUsbPermissionAndConnect() }
        b.btnMount.setOnClickListener { mountImage() }
        b.btnReset.setOnClickListener { resetLocation() }
        b.btnPickMap.setOnClickListener { mapTarget = "point"; launchMap() }
        b.btnAddPointMap.setOnClickListener { mapTarget = "route"; launchMap() }
        b.btnAddPointManual.setOnClickListener { showManualAddDialog() }
        b.btnClearRoute.setOnClickListener {
            routePoints.clear()
            routeAdapter.notifyDataSetChanged()
        }
        b.btnSetPoint.setOnClickListener { setPoint() }
        b.btnPlayRoute.setOnClickListener { playRoute() }
        b.btnStopRoute.setOnClickListener { routePlayer?.stop() }
        b.btnPickDdi.setOnClickListener { ddiPicker.launch(null) }
    }

    private fun launchMap() {
        mapPicker.launch(Intent(this, MapPickerActivity::class.java))
    }

    private fun showManualAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etLat = EditText(this).apply { hint = "纬度，例如 30.572815" }
        val etLon = EditText(this).apply { hint = "经度，例如 104.066800" }
        container.addView(etLat)
        container.addView(etLon)
        AlertDialog.Builder(this)
            .setTitle("输入坐标")
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val lat = etLat.text.toString().trim().toDoubleOrNull()
                val lon = etLon.text.toString().trim().toDoubleOrNull()
                if (lat == null || lon == null) {
                    log("坐标格式错误")
                } else {
                    routePoints.add(GeoPoint(lat, lon))
                    routeAdapter.notifyItemInserted(routePoints.size - 1)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- USB / pairing ----------------------------------------------------

    private fun requestUsbPermissionAndConnect() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = deviceManager.listAttachedDevices().firstOrNull()
        if (device == null) {
            log("未检测到 iPhone：请用 OTG 数据线连接，并在手机上选择“信任/传输文件”")
            return
        }
        if (usb.hasPermission(device)) {
            connect()
        } else {
            log("请求 USB 权限…")
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usb.requestPermission(device, pi)
        }
    }

    private fun connect() {
        lifecycleScope.launch {
            try {
                b.tvDeviceStatus.text = "正在连接…"
                val info = withContext(Dispatchers.IO) {
                    val dev = deviceManager.findFirstAttached()
                    deviceManager.connect(dev) {
                        log("请在 iPhone 上点击“信任”并输入锁屏密码…")
                    }
                }
                b.tvDeviceStatus.text =
                    "已连接：${info.name}（iOS ${info.productVersion}）\nUDID: ${info.udid}"
                log("连接成功：${info.name} iOS ${info.productVersion}")
            } catch (t: Throwable) {
                b.tvDeviceStatus.text = "连接失败"
                log("连接失败：${t.message}")
            }
        }
    }

    // ---- DDI --------------------------------------------------------------

    private fun mountImage() {
        val uri = ddiUri
        if (uri == null) {
            log("请先选择 DDI 文件夹")
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val loaded = DdiLoader.load(this@MainActivity, uri)
                    log("DDI 已加载：${loaded.description}")
                    deviceManager.ensureDeveloperImage({ loaded.bundle }) { p ->
                        log("上传镜像：${(p * 100).toInt()}%")
                    }
                }
                log("开发者镜像挂载完成")
            } catch (t: Throwable) {
                log("挂载失败：${t.message}")
            }
        }
    }

    /**
     * Load the (cached) DDI bundle when the device asks for it during the
     * ensure-image check.
     */
    private fun bundleProvider(modern: Boolean): DdiBundle? {
        val uri = ddiUri ?: return null
        val loaded = DdiLoader.load(this, uri)
        if (loaded.bundle.legacy == modern) {
            log("提示：所选 DDI 类型与当前 iOS 版本不匹配（modern=$modern）")
        }
        return loaded.bundle
    }

    private suspend fun ensureReady() {
        if (deviceManager.info == null) {
            withContext(Dispatchers.IO) {
                deviceManager.connect(deviceManager.findFirstAttached()) {
                    log("请在 iPhone 上点击“信任”并输入锁屏密码…")
                }
            }
        }
        withContext(Dispatchers.IO) {
            deviceManager.ensureDeveloperImage(
                bundleProvider = { modern -> bundleProvider(modern) },
                onProgress = { p -> log("上传镜像：${(p * 100).toInt()}%") }
            )
        }
    }

    // ---- Location ---------------------------------------------------------

    private fun setPoint() {
        val lat = b.etLat.text.toString().trim().toDoubleOrNull()
        val lon = b.etLon.text.toString().trim().toDoubleOrNull()
        if (lat == null || lon == null) {
            log("请输入或选择有效的经纬度")
            return
        }
        lifecycleScope.launch {
            try {
                ensureReady()
                withContext(Dispatchers.IO) { deviceManager.location().set(lat, lon) }
                log("已设置位置：$lat, $lon")
            } catch (t: Throwable) {
                log("设置位置失败：${t.message}")
            }
        }
    }

    private fun playRoute() {
        if (routePoints.size < 2) {
            log("路线至少需要 2 个点")
            return
        }
        val speed = b.sbSpeed.progress.toDouble().coerceAtLeast(1.0)
        val interval = b.etInterval.text.toString().toLongOrNull()?.coerceAtLeast(200L) ?: 1000L
        val loop = b.cbLoop.isChecked
        val points = routePoints.toList()
        lifecycleScope.launch {
            try {
                ensureReady()
                val player = RoutePlayer(
                    scope = lifecycleScope,
                    controller = deviceManager.location(),
                    onPoint = { p -> log("当前位置：%.6f, %.6f".format(p.lat, p.lon)) },
                    onFinish = { log("路线播放完成") },
                    onError = { t -> log("路线播放出错：${t.message}") }
                )
                routePlayer = player
                withContext(Dispatchers.IO) {
                    player.playSpeed(points, speed, interval, loop)
                }
            } catch (t: Throwable) {
                log("路线播放失败：${t.message}")
            }
        }
    }

    private fun resetLocation() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { deviceManager.location().reset() }
                routePlayer?.stop()
                log("已恢复真实定位")
            } catch (t: Throwable) {
                log("恢复失败：${t.message}")
            }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            b.tvLog.append("• $msg\n")
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        routePlayer?.stop()
        super.onDestroy()
    }

    private abstract class SimpleSeek : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.iosloc.spoofer.USB_PERMISSION"
    }
}
