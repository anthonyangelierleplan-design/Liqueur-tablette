package com.foudreaudio.liqueurs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File

/**
 * Coquille minimale : toute l'appli est dans assets/index.html et tourne hors ligne.
 * Les données (stock, ventes, réglages) sont écrites dans un fichier privé de l'appli,
 * via le pont JavaScript « Android ».
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingExport: String? = null

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2
        // Clé affiliée SumUp, liée à l'identifiant com.foudreaudio.liqueurs (n'ouvre aucun accès au compte).
        private const val SUMUP_KEY = "sup_afk_3DtdrfY6jgtAfcStm7RJwfuzKLGifWcI"
        private const val SUMUP_CALLBACK = "eaudela://sumup"
    }

    private var pageReady = false
    private var pendingSumUpResult: String? = null

    private val dataFile by lazy { File(filesDir, "data.json") }
    private val sync by lazy { SyncManager(this, dataFile) { js(it) } }
    private val REQ_PERMS = 3

    private fun syncPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT", "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_FINE_LOCATION")
        Build.VERSION.SDK_INT >= 31 -> arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT", "android.permission.ACCESS_FINE_LOCATION")
        else -> arrayOf("android.permission.ACCESS_FINE_LOCATION")
    }

    private fun startSyncWithPermissions() {
        val missing = syncPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) sync.start() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) sync.start()
        else js("window.onSyncStatus && onSyncStatus({state:'perm'})")
    }

    override fun onDestroy() {
        try { sync.stop(silent = true) } catch (_: Exception) {}
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Plein écran bord à bord sur toutes les versions : on gère nous-mêmes les marges système.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        val root = FrameLayout(this)
        web = WebView(this)
        web.setBackgroundColor(0x00000000)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            // Aucune navigation externe : l'appli reste sur sa page.
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingSumUpResult?.let { js("window.onSumUpResult && onSumUpResult($it)") }
                pendingSumUpResult = null
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        handleSumUpReturn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSumUpReturn(intent)
    }

    /** Lit la réponse de SumUp (eaudela://sumup?smp-status=…) et la transmet à la page. */
    private fun handleSumUpReturn(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "eaudela" || uri.host != "sumup") return
        val o = JSONObject()
            .put("status", uri.getQueryParameter("smp-status") ?: "")
            .put("message", uri.getQueryParameter("smp-message") ?: "")
            .put("txCode", uri.getQueryParameter("smp-tx-code") ?: "")
            .put("foreignId", uri.getQueryParameter("foreign-tx-id") ?: "")
        intent.data = null
        if (pageReady) js("window.onSumUpResult && onSumUpResult($o)") else pendingSumUpResult = o.toString()
    }

    inner class Bridge {
        @JavascriptInterface
        fun load(): String = try {
            if (dataFile.exists()) dataFile.readText() else ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun save(json: String): Boolean = try {
            val tmp = File(filesDir, "data.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(dataFile)) { dataFile.writeText(json); tmp.delete() }
            true
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun exportFile(name: String, mime: String, content: String) {
            runOnUiThread {
                pendingExport = content
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, name)
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_EXPORT)
            }
        }

        @JavascriptInterface
        fun payWithSumUp(total: String, title: String, foreignId: String) {
            runOnUiThread {
                val uri = Uri.parse("sumupmerchant://pay/1.0").buildUpon()
                    .appendQueryParameter("affiliate-key", SUMUP_KEY)
                    .appendQueryParameter("app-id", packageName)
                    .appendQueryParameter("total", total)
                    .appendQueryParameter("amount", total) // anciennes versions de SumUp
                    .appendQueryParameter("currency", "EUR")
                    .appendQueryParameter("title", title)
                    .appendQueryParameter("foreign-tx-id", foreignId)
                    .appendQueryParameter("callback", SUMUP_CALLBACK)
                    .build()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    val o = JSONObject().put("status", "noapp").put("foreignId", foreignId)
                    js("window.onSumUpResult && onSumUpResult($o)")
                }
            }
        }

        @JavascriptInterface fun syncStart() = runOnUiThread { startSyncWithPermissions() }
        @JavascriptInterface fun syncAccept() = runOnUiThread { sync.accept() }
        @JavascriptInterface fun syncReject() = runOnUiThread { sync.reject() }
        @JavascriptInterface fun syncStop() = runOnUiThread { sync.stop(silent = true) }
        @JavascriptInterface fun syncMerged() = runOnUiThread { sync.mergedDone() }

        @JavascriptInterface
        fun importFile() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_IMPORT)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = if (resultCode == RESULT_OK) data?.data else null
        when (requestCode) {
            REQ_EXPORT -> {
                val content = pendingExport
                pendingExport = null
                var ok = false
                if (uri != null && content != null) {
                    ok = try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                        true
                    } catch (e: Exception) { false }
                }
                js("window.onExportDone && onExportDone(${if (uri == null) "null" else ok})")
            }
            REQ_IMPORT -> {
                if (uri == null) return
                val text = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) { null }
                js("window.onImport && onImport(${JSONObject.quote(text ?: "")})")
            }
        }
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // La page ferme d'abord ses fenêtres ouvertes ; sinon on quitte.
        web.evaluateJavascript("window.androidBack ? androidBack() : false") { r ->
            if (r != "true") finish()
        }
    }
}
