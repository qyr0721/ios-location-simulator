package com.iosloc.spoofer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.iosloc.spoofer.databinding.ActivityMapPickerBinding

/**
 * Full-screen Leaflet/OpenStreetMap tap picker. Returns EXTRA_LAT / EXTRA_LON.
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding

    inner class JsBridge {
        @JavascriptInterface
        fun onPick(lat: String, lon: String) {
            val data = Intent().apply {
                putExtra(EXTRA_LAT, lat.toDouble())
                putExtra(EXTRA_LON, lon.toDouble())
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "地图选点"

        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
        }
        binding.webview.webViewClient = WebViewClient()
        binding.webview.webChromeClient = WebChromeClient()
        binding.webview.addJavascriptInterface(JsBridge(), "Android")
        binding.webview.loadUrl("file:///android_asset/map.html")
    }

    override fun onDestroy() {
        binding.webview.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
    }
}
