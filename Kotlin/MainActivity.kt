package com.alzubair.omri

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val notificationRequest = 5101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 17, 27)
        window.navigationBarColor = Color.rgb(16, 17, 27)

        ReminderScheduler.createChannel(this)

        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(16, 17, 27))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return openExternal(uri)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                return try { openExternal(Uri.parse(url)) } catch (_: Exception) { false }
            }
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        setContentView(webView)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
        ReminderScheduler.rescheduleAll(this)
        requestNotificationPermissionIfNeeded()
    }

    private fun openExternal(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme == "file" || scheme == "about" || scheme == "data") return false
        if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (_: Exception) {
                Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
                true
            }
        }
        return false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread { requestNotificationPermissionIfNeeded() }
        }

        @JavascriptInterface
        fun requestExactAlarmPermission() {
            // This project uses Android's inexact, battery-friendly alarms.
        }

        @JavascriptInterface
        fun setNotificationSettings(json: String) {
            try {
                val obj = JSONObject(json)
                val prefs = getSharedPreferences("omri_native", MODE_PRIVATE)
                prefs.edit().putString("settings", obj.toString()).apply()
                val birth = obj.optString("birthDate", "")
                if (birth.matches(Regex("\\d{1,4}-\\d{1,2}-\\d{1,2}"))) {
                    prefs.edit().putString("birthDate", birth).apply()
                } else {
                    prefs.edit().remove("birthDate").apply()
                }
                ReminderScheduler.rescheduleAll(this@MainActivity)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun testNotification(message: String) {
            runOnUiThread {
                ReminderScheduler.showNow(this@MainActivity, "عُمري | My Age", message)
            }
        }

        @JavascriptInterface
        fun scheduleReminder(json: String) {
            try { ReminderScheduler.scheduleCustom(this@MainActivity, JSONObject(json)) } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun cancelReminder(id: String) {
            ReminderScheduler.cancel(this@MainActivity, id.hashCode())
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun openWhatsApp(number: String) {
            runOnUiThread {
                val digits = number.filter { it.isDigit() }
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))) } catch (_: Exception) { }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            super.onBackPressed()
            return
        }
        webView.evaluateJavascript(
            "(function(){return window.onAndroidBack ? window.onAndroidBack() : false;})()"
        ) { result ->
            if (result != "true") finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
