package com.ai.assistance.operit.ui.companion

import android.annotation.SuppressLint
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * Thin companion shell. Chat remains in the cloud; native capabilities stay in
 * the existing Operit tool layer. Only the allowlisted cloud origin is loaded.
 */
class CompanionActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val allowedOrigin = "https://aionhome-cloud.onrender.com"
    private val companionUrl = "$allowedOrigin/glass/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val allowed = uri.scheme == Uri.parse(allowedOrigin).scheme &&
                    uri.host == Uri.parse(allowedOrigin).host
                return !allowed
            }
        }
        webView.addJavascriptInterface(DeviceBridge(), "OperitDevice")
        setContentView(webView)
        webView.loadUrl(companionUrl)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("OperitDevice")
        webView.destroy()
        super.onDestroy()
    }

    private inner class DeviceBridge {
        @JavascriptInterface
        fun deviceInfo(): String {
            val battery = getSystemService(BATTERY_SERVICE) as? BatteryManager
            val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it >= 0 }
            return JSONObject().apply {
                put("ok", true)
                put("sdk", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE ?: "")
                put("manufacturer", Build.MANUFACTURER ?: "")
                put("model", Build.MODEL ?: "")
                put("battery_percent", level ?: JSONObject.NULL)
            }.toString()
        }
    }
}
