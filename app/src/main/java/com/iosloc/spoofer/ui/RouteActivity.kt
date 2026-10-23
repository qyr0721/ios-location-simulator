package com.iosloc.spoofer.ui

import android.app.PendingIntent
import android.app.AlertDialog
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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iosloc.spoofer.GoIosSession
import com.iosloc.spoofer.data.MapConfig
import com.iosloc.spoofer.data.RouteStore
import com.iosloc.spoofer.data.SavedRoute
import com.iosloc.spoofer.location.GeoPoint
import com.iosloc.spoofer.location.RouteEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main screen: Leaflet map + route builder + route player.
 *
 * The map is an asset WebView. Points are held in Kotlin and mirrored into the
 * map through setPoints([[lat,lon],...]); the go-ios DVT session is fed by
 * RouteEngine through GoIosSession.setLocation().
 */
class RouteActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var sbSpeed: SeekBar
    private lateinit var cbLoop: CheckBox
    private lateinit var btnAdd: Button
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var usbManager: UsbManager

    private val ui = Handler(Looper.getMainLooper())
    private val points = mutableListOf<GeoPoint>()
    private val logBuf = StringBuilder()

    private var addMode = false
    private var mapReady = false
    private var engine: RouteEngine? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            appendLog(if (granted) "USB 权限已授予" else "USB 权限被拒绝")
            if (granted) tvStatus.text = "USB 已授权，点“连接”"
        }
    }

    private val sessionListener: (String) -> Unit = { message ->
        runOnUiThread {
            appendLog(message)
            when {
                message.contains("已连接，可以") -> tvStatus.text = "已连接"
                message.contains("配对失败") || message.contains("隧道启动失败") ||
                    message.contains("定位会话启动失败") || message.contains("连接失败") ->
                    tvStatus.text = "连接失败"
                message.contains("未检测到 iPhone") -> tvStatus.text = "未连接"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        GoIosSession.init(applicationContext)
        setContentView(buildUi())
        setupWebView()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver()
        requestUsbPermissionIfNeeded()
        appendLog("iPhone 用 OTG 接本机；首次使用先“连接”（iPhone 上点信任）。")
    }

    override fun onStart() {
        super.onStart()
        GoIosSession.addListener(sessionListener)
    }

    override fun onStop() {
        GoIosSession.removeListener(sessionListener)
        super.onStop()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Throwable) {}
        try { web.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ---- UI ---------------------------------------------------------------

    private fun buildUi(): View {
        val pad = dp(10)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, dp(4))
        }
        tvStatus = TextView(this).apply {
            text = "未连接"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.DKGRAY)
            maxLines = 2
        }
        top.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("连接") { connect() })
        top.addView(button("设置") { showMapSettings() })
        root.addView(top)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, dp(2))
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnAdd = button("添加点：关") { toggleAddMode() }
        row1.addView(btnAdd, weight())
        row1.addView(button("撤销") { undoPoint() }, weight())
        row1.addView(button("清空") { clearPoints() }, weight())
        tvCount = TextView(this).apply {
            text = "0 点"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        row1.addView(tvCount, weight())
        panel.addView(row1)

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        tvSpeed = TextView(this).apply {
            text = "30 km/h"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        sbSpeed = SeekBar(this).apply {
            max = 120
            progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvSpeed.text = "${progress.coerceAtLeast(1)} km/h"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        cbLoop = CheckBox(this).apply { text = "循环"; textSize = 12f }
        speedRow.addView(tvSpeed)
        speedRow.addView(sbSpeed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedRow.addView(cbLoop)
        panel.addView(speedRow)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("播放路线") { playRoute() }, weight())
        row3.addView(button("停止") { stopRoute() }, weight())
        row3.addView(button("回到路线") { js("fitRoute()") }, weight())
        panel.addView(row3)

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(button("保存路线") { saveRoute() }, weight())
        row4.addView(button("历史") { showHistory() }, weight())
        row4.addView(button("收藏点") { favoriteLastPoint() }, weight())
        row4.addView(button("收藏夹") { showFavorites() }, weight())
        panel.addView(row4)

        tvLog = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            movementMethod = ScrollingMovementMethod()
        }
        logScroll = ScrollView(this).apply {
            addView(tvLog)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92)
            )
        }
        panel.addView(logScroll)
        root.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return root
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { onClick() }
        }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- WebView / map ----------------------------------------------------

    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                applyMapConfig()
                pushPoints()
                js("setAddMode($addMode)")
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/map.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun onMapReady() {
            runOnUiThread {
                mapReady = true
                applyMapConfig()
                pushPoints()
                js("setAddMode($addMode)")
            }
        }

        @JavascriptInterface
        fun onMapClick(lat: Double, lon: Double) {
            runOnUiThread {
                if (!addMode) return@runOnUiThread
                points.add(GeoPoint(lat, lon))
                pushPoints()
                appendLog("添加点 ${points.size}: ${"%.6f".format(lat)}, ${"%.6f".format(lon)}")
            }
        }

        @JavascriptInterface
        fun onMarkerClick(index: Int) {
            runOnUiThread { markerDialog(index) }
        }
    }

    private fun applyMapConfig() {
        val cfg = RouteStore.mapConfig(this)
        js("setTileProvider(${JSONObject.quote(cfg.effectiveUrl())}, ${JSONObject.quote(cfg.effectiveAttribution())})")
    }

    private fun pushPoints() {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONArray().put(p.lat).put(p.lon))
        }
        js("setPoints(${JSONObject.quote(arr.toString())})")
        tvCount.text = "${points.size} 点"
    }

    private fun js(code: String) {
        if (!mapReady) return
        web.evaluateJavascript(code, null)
    }

    private fun toggleAddMode() {
        addMode = !addMode
        btnAdd.text = if (addMode) "添加点：开" else "添加点：关"
        js("setAddMode($addMode)")
        if (addMode) appendLog("在地图上点击添加路线点")
    }

    // ---- route editing ----------------------------------------------------

    private fun undoPoint() {
        if (points.isEmpty()) return
        points.removeAt(points.size - 1)
        pushPoints()
    }

    private fun clearPoints() {
        if (points.isEmpty()) return
        points.clear()
        pushPoints()
        js("clearCurrent()")
    }

    private fun markerDialog(index: Int) {
        if (index !in points.indices) return
        val p = points[index]
        AlertDialog.Builder(this)
            .setTitle("第 ${index + 1} 个点")
            .setItems(arrayOf("删除此点", "收藏此点", "关闭")) { _, which ->
                when (which) {
                    0 -> {
                        points.removeAt(index)
                        pushPoints()
                    }
                    1 -> favoritePoint(p)
                }
            }
            .show()
    }

    // ---- playback ---------------------------------------------------------

    private fun playRoute() {
        if (points.size < 2) {
            toast("路线至少需要 2 个点")
            return
        }
        if (!GoIosSession.isReady()) {
            toast("请先点右上角“连接”，等状态变成“已连接”")
            appendLog("尚未连接，先点“连接”")
            return
        }
        // Stop the previous engine only; stopping the DVT session here would
        // kill the locsim process the new engine is about to write to.
        engine?.stop()
        engine = null
        val speed = sbSpeed.progress.toDouble().coerceAtLeast(1.0)
        val loop = cbLoop.isChecked
        val engine = RouteEngine(
            sink = { p -> GoIosSession.setLocation(p.lat, p.lon) },
            onTick = { p, progress ->
                runOnUiThread {
                    js("setCurrent(${p.lat}, ${p.lon}, false)")
                    tvStatus.text = "播放中 ${(progress * 100).toInt()}%"
                }
            },
            onFinish = {
                runOnUiThread {
                    tvStatus.text = "已连接"
                    appendLog("路线播放完成")
                }
            },
            onError = { t ->
                runOnUiThread {
                    tvStatus.text = "已连接"
                    appendLog("路线出错: ${t.message}")
                }
            }
        )
        this.engine = engine
        engine.start(lifecycleScope, points, speed, 1000L, loop)
        appendLog("开始播放：${points.size} 个点，${speed.toInt()} km/h，循环=$loop")
    }

    private fun stopRoute() {
        engine?.stop()
        engine = null
        GoIosSession.stopLocation()
        js("clearCurrent()")
        tvStatus.text = "已停止，点“连接”恢复"
        appendLog("已停止路线并结束定位会话")
    }

    // ---- persistence ------------------------------------------------------

    private fun saveRoute() {
        if (points.isEmpty()) {
            toast("先添加路线点")
            return
        }
        val input = EditText(this).apply {
            setText("路线 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("保存路线")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "未命名路线" }
                RouteStore.saveRoute(this, name, points)
                toast("已保存：$name")
                appendLog("保存路线：$name（${points.size} 点）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistory() {
        val routes = RouteStore.routes(this)
        if (routes.isEmpty()) {
            toast("没有历史路线")
            return
        }
        val names = routes.map { "${it.name}（${it.points.size} 点）" }.toTypedArray()
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@RouteActivity, android.R.layout.simple_list_item_1, names)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("历史路线")
            .setView(list)
            .setNegativeButton("关闭", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val route = routes[position]
            points.clear()
            points.addAll(route.points)
            pushPoints()
            js("fitRoute()")
            dialog.dismiss()
            appendLog("加载路线：${route.name}（${route.points.size} 点）")
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val route = routes[position]
            AlertDialog.Builder(this)
                .setTitle("删除路线")
                .setMessage("删除「${route.name}」？")
                .setPositiveButton("删除") { _, _ ->
                    RouteStore.deleteRoute(this, route.id)
                    dialog.dismiss()
                    toast("已删除")
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        dialog.show()
    }

    private fun favoriteLastPoint() {
        if (points.isEmpty()) {
            toast("先加一个点，再收藏")
            return
        }
        favoritePoint(points.last())
    }

    private fun favoritePoint(p: GeoPoint) {
        val input = EditText(this).apply {
            setText("${"%.5f".format(p.lat)}, ${"%.5f".format(p.lon)}")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("收藏这个点")
            .setView(input)
            .setPositiveButton("收藏") { _, _ ->
                val name = input.text.toString().trim()
                    .ifBlank { "${"%.5f".format(p.lat)}, ${"%.5f".format(p.lon)}" }
                RouteStore.savePlace(this, name, p.lat, p.lon)
                toast("已收藏：$name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavorites() {
        val places = RouteStore.places(this)
        if (places.isEmpty()) {
            toast("收藏夹为空")
            return
        }
        val names = places.map { "${it.name}  (${"%.4f".format(it.lat)}, ${"%.4f".format(it.lon)})" }
            .toTypedArray()
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@RouteActivity, android.R.layout.simple_list_item_1, names)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("收藏夹（点击加入路线）")
            .setView(list)
            .setNegativeButton("关闭", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val place = places[position]
            points.add(GeoPoint(place.lat, place.lon, place.name))
            pushPoints()
            dialog.dismiss()
            toast("已加入路线：${place.name}")
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val place = places[position]
            AlertDialog.Builder(this)
                .setTitle("删除收藏")
                .setMessage("删除「${place.name}」？")
                .setPositiveButton("删除") { _, _ ->
                    RouteStore.deletePlace(this, place.id)
                    dialog.dismiss()
                    toast("已删除")
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        dialog.show()
    }

    // ---- map provider settings -------------------------------------------

    private fun showMapSettings() {
        val cfg = RouteStore.mapConfig(this)
        val pad = dp(12)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val group = RadioGroup(this)
        val rbOsm = RadioButton(this).apply { id = 1; text = "OpenStreetMap（免费，无需 Key）" }
        val rbCustom = RadioButton(this).apply { id = 2; text = "自定义 XYZ 瓦片 + API Key" }
        group.addView(rbOsm)
        group.addView(rbCustom)
        group.check(if (cfg.provider == MapConfig.PROVIDER_CUSTOM) 2 else 1)
        container.addView(group)

        val etUrl = EditText(this).apply {
            hint = "瓦片 URL，例如 https://api.maptiler.com/maps/streets-v2/{z}/{x}/{y}.png?key={key}"
            setText(if (cfg.provider == MapConfig.PROVIDER_CUSTOM) cfg.urlTemplate else "")
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
        }
        val etKey = EditText(this).apply {
            hint = "API Key（会替换 URL 里的 {key}）"
            setText(cfg.apiKey)
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etAttr = EditText(this).apply {
            hint = "版权信息（可留空）"
            setText(if (cfg.provider == MapConfig.PROVIDER_CUSTOM) cfg.attribution else "")
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(label("自定义瓦片 URL（支持 {s}/{z}/{x}/{y}/{key}）"))
        container.addView(etUrl)
        container.addView(label("API Key"))
        container.addView(etKey)
        container.addView(label("版权/Attribution"))
        container.addView(etAttr)
        container.addView(label(
            "示例：\nMapTiler: https://api.maptiler.com/maps/streets-v2/{z}/{x}/{y}.png?key={key}\n" +
                "Thunderforest: https://tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey={key}\n" +
                "OSM 不需要 Key；请遵守各瓦片服务的使用条款。"
        ))

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("地图设置")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val custom = group.checkedRadioButtonId == 2
                val url = etUrl.text.toString().trim()
                val key = etKey.text.toString().trim()
                val attr = etAttr.text.toString().trim()
                if (custom && url.isBlank()) {
                    toast("自定义模式需要填写瓦片 URL")
                    return@setPositiveButton
                }
                val next = if (custom) {
                    MapConfig(
                        MapConfig.PROVIDER_CUSTOM, url, key,
                        attr.ifBlank { "Custom tiles" }
                    )
                } else {
                    MapConfig.DEFAULT
                }
                RouteStore.saveMapConfig(this, next)
                applyMapConfig()
                appendLog("地图源已切换：${if (custom) "自定义" else "OpenStreetMap"}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(6), 0, dp(2))
    }

    // ---- connection / USB -------------------------------------------------

    private fun connect() {
        tvStatus.text = "连接中…"
        appendLog("开始连接…")
        GoIosSession.connect()
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
        val dev = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x05AC }
        if (dev == null) {
            appendLog("未发现 Apple USB 设备（请用 OTG 接 iPhone）")
            return
        }
        if (usbManager.hasPermission(dev)) {
            appendLog("已有 USB 权限")
            return
        }
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(dev, pi)
    }

    // ---- logging ----------------------------------------------------------

    private fun appendLog(message: String) {
        Log.i("RouteActivity", message)
        runOnUiThread {
            if (logBuf.length > 60_000) logBuf.delete(0, logBuf.length - 30_000)
            logBuf.append(message).append('\n')
            tvLog.text = logBuf
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.iosloc.spoofer.USB_PERMISSION"
    }
}
