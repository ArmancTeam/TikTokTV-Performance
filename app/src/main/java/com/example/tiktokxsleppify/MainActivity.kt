package com.example.tiktokxsleppify

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.util.Locale
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var webView: WebView
    private lateinit var virtualMouse: VirtualMouse

    private lateinit var loadingOverlay: FrameLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var osdText: TextView
    private lateinit var voiceOverlay: LinearLayout
    private lateinit var voiceMic: TextView
    private lateinit var voiceTitle: TextView
    private lateinit var voicePartial: TextView

    private lateinit var tvMenuOverlay: FrameLayout
    private lateinit var tvHelpOverlay: LinearLayout
    private lateinit var tvSearchButton: Button
    private lateinit var tvHelpCloseButton: Button

    private var preloadedBridgeScript: String = ""
    private var tvMenuOpen = false
    private var tvHelpOpen = false

    // Read from the WebView's IO thread (shouldInterceptRequest), written from the UI thread.
    @Volatile
    private var blockTrackers = true

    // Set when the WebView renderer died and the view cannot be used any more.
    private var webViewDead = false

    // True between a failed main-frame load and the next page start (skips bridge injection on error pages).
    private var lastLoadFailed = false

    private var isPaused = false
    private var googleDialog: AlertDialog? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingInstallUri: Uri? = null

    // Voice search
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceActive = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceSearch() else showOsd(getString(R.string.voice_permission_denied))
        }

    // Fallback when no in-app recognition service exists: the system voice dialog.
    private val voiceDialogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) performVoiceQuery(text)
            else resumeAfterVoice()
        }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val okLongPressRunnable = Runnable { virtualMouse.toggle() }
    private val hideOsdRunnable = Runnable { osdText.visibility = View.GONE }

    // Red remote key: tap = mouse on/off, hold = mouse/remote settings.
    private var redLongPressed = false

    // Clean screen (video only). Kept in the app so it survives page loads; the bridge re-applies it.
    private var cleanMode = false

    // Set when a voice/system search was started: open the first result once the results page is ready.
    private var pendingOpenFirst = false
    private val redLongPressRunnable = Runnable {
        redLongPressed = true
        // Long-press the red remote button to toggle the virtual mouse.
        virtualMouse.toggle()
        showOsd(getString(if (virtualMouse.isEnabled) R.string.osd_mouse_on else R.string.osd_mouse_off))
    }

    companion object {
        private const val TAG = "TikTok TV"
        private const val LOGIN_URL = "https://www.tiktok.com/login"
        private const val QR_LOGIN_URL = "https://www.tiktok.com/login/qrcode"
        private const val SEARCH_URL = "https://www.tiktok.com/search/video?q="
        private const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

        private const val OK_LONG_PRESS_MS = 800L
        private const val RED_LONG_PRESS_MS = 700L
        // Turning the mouse OFF needs a longer hold, so click-and-hold / dragging never switches it off by accident.
        private const val OK_LONG_PRESS_OFF_MS = 1500L
        private const val SEEK_SECONDS = 10
        private const val SCROLL_STEP = 300
        private const val OSD_DURATION_MS = 1400L
        private const val RENDERER_CRASH_LOOP_MS = 15_000L

        // Survives activity re-creation, so a crash loop can be detected.
        @Volatile
        private var lastRendererCrashAt = 0L

        // Keys that are always consumed here (play/pause, seek, previous/next video).
        private val HANDLED_MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN
        )
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------


    private fun showWhatsNewIfNeeded() {
        val currentVersion =
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"

        val prefs = getSharedPreferences("whats_new", MODE_PRIVATE)
        if (prefs.getString("shown_version", "") == currentVersion) return

        val dialog = AlertDialog.Builder(this)
            .setTitle("🎉 TikTokTV - Yenilikler")
            .setMessage(
                "🚀 Akıllı güncelleme\n\n" +
                "🎨 AMOLED siyah tema\n\n" +
                "🖱️ Neon imleç ve kumanda desteği\n\n" +
                "⚡ Performans iyileştirmeleri\n\n" +
                "Sürüm: v$currentVersion"
            )
            .setCancelable(false)
            .setPositiveButton("Başlayalım!") { _, _ ->
                prefs.edit().putString("shown_version", currentVersion).apply()
            }
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()

        settings = AppSettings(this)
        blockTrackers = settings.blockTrackers

        try {
            preloadedBridgeScript = assets.open("tiktok_tv_bridge.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload JS bridge", e)
        }

        setContentView(R.layout.activity_main)
        bindViews()

        uiHandler.postDelayed({
            if (!isFinishing && !isDestroyed) showWhatsNewIfNeeded()
        }, 1200)

        virtualMouse = VirtualMouse(this, webView) { x, y, dy -> scrollAt(x, y, dy) }
        virtualMouse.speedMultiplier = settings.mouseSpeed
        virtualMouse.edgeScrollEnabled = settings.edgeScroll
        virtualMouse.cursorColor = cursorColorFor(settings.cursorColorKey)
        virtualMouse.neonEnabled = settings.neonEnabled
        virtualMouse.neonIntensity = settings.neonIntensity
        applyAmoledMode()

        setupTvMenu()
        setupWebView()
        setupBackHandling()
        registerNetworkCallback()

        retryButton.setOnClickListener { retryLoad() }

        val startUrl = urlFromIntent(intent) ?: settings.startPage.url
        markSearchPending(startUrl)
        webView.loadUrl(startUrl)
        uiHandler.postDelayed({ checkForUpdates(false) }, 5000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = urlFromIntent(intent) ?: return
        if (webViewDead) {
            recreate()
            return
        }
        closeTvMenu()
        markSearchPending(url)
        webView.loadUrl(url)
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        if (voiceActive) cancelVoice()
        if (!webViewDead) {
            // Stop audio/video first, then freeze the WebView (pausing alone does not always silence it).
            bridgeCall("window.TikTokTV.pauseAll()")
            uiHandler.postDelayed({ if (isPaused && !webViewDead) webView.onPause() }, 150)
        }
        CookieManager.getInstance().flush() // keep the login session even if the process is killed
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        pendingInstallUri?.let { uri ->
            if (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                pendingInstallUri = null
                launchApkInstaller(uri)
            }
        }
        setupFullscreen()
        if (!webViewDead) {
            webView.onResume()
            uiHandler.postDelayed({ sendBridgeCommand("playIfPaused") }, 300)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        googleDialog?.dismiss()
        try { speechRecognizer?.destroy() } catch (e: Exception) { }
        speechRecognizer = null
        virtualMouse.destroy()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
        retryButton = findViewById(R.id.retryButton)
        osdText = findViewById(R.id.osdText)
        voiceOverlay = findViewById(R.id.voiceOverlay)
        voiceMic = findViewById(R.id.voiceMic)
        voiceTitle = findViewById(R.id.voiceTitle)
        voicePartial = findViewById(R.id.voicePartial)
        tvMenuOverlay = findViewById(R.id.tvMenuOverlay)
        tvHelpOverlay = findViewById(R.id.tvHelpOverlay)
        tvSearchButton = findViewById(R.id.tvSearchButton)
        tvHelpCloseButton = findViewById(R.id.tvHelpCloseButton)
    }

    @Suppress("DEPRECATION")
    private fun setupFullscreen() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------------------------------
    // Back handling (Android 16 / targetSdk 36 no longer delivers KEYCODE_BACK to the activity)
    // ------------------------------------------------------------------------------------------

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                performBack()
            }
        })
    }

    private fun performBack() {
        when {
            voiceActive -> cancelVoice()
            tvHelpOpen -> closeTvHelp()
            tvMenuOpen -> closeTvMenu()
            cleanMode -> setCleanMode(false)
            else -> handleBack()
        }
    }

    private fun handleBack() {
        if (webViewDead) {
            finish()
            return
        }
        // The SPA closes whatever is open (sidebar, modal, ...). If nothing is open, leave the page/app.
        bridgeCall("window.TikTokTV.back()") { result ->
            val handled = result.isNotEmpty() && result != "null" && result != "nav_back" && result != "NOT_FOUND"
            if (!handled) {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Intents (voice search)
    // ------------------------------------------------------------------------------------------

    private fun urlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action
        if (action == Intent.ACTION_SEARCH || action == ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY)?.trim()
            if (!query.isNullOrEmpty()) return SEARCH_URL + Uri.encode(VoiceQuery.clean(query))
        }
        return null
    }

    // ------------------------------------------------------------------------------------------
    // TV menu
    // ------------------------------------------------------------------------------------------

    private fun setupTvMenu() {
        tvSearchButton.setOnClickListener { openTikTokSearch() }
        findViewById<Button>(R.id.tvVoiceButton).setOnClickListener { startVoiceSearch() }
        findViewById<Button>(R.id.tvTabForYouButton).setOnClickListener { navigateTo(StartPage.FOR_YOU.url) }
        findViewById<Button>(R.id.tvTabFollowingButton).setOnClickListener { navigateTo(StartPage.FOLLOWING.url) }
        findViewById<Button>(R.id.tvTabExploreButton).setOnClickListener { navigateTo(StartPage.EXPLORE.url) }
        findViewById<Button>(R.id.tvTabLiveButton).setOnClickListener { navigateTo("https://www.tiktok.com/live") }
        findViewById<Button>(R.id.tvMouseButton).setOnClickListener {
            closeTvMenu()
            virtualMouse.toggle()
        }
        findViewById<Button>(R.id.tvQrLoginButton).setOnClickListener { navigateTo(QR_LOGIN_URL) }
        findViewById<Button>(R.id.tvMuteButton).setOnClickListener {
            closeTvMenu()
            toggleMute()
        }
        findViewById<Button>(R.id.tvCleanButton).setOnClickListener {
            closeTvMenu()
            setCleanMode(!cleanMode)
        }
        findViewById<Button>(R.id.tvSettingsButton).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.tvHelpButton).setOnClickListener { openTvHelp() }
        tvHelpCloseButton.setOnClickListener { closeTvHelp() }

        tvMenuOverlay.setOnClickListener { closeTvMenu() }
        tvMenuOverlay.findViewById<View>(R.id.tvMenuPanel).setOnClickListener { /* keep panel open */ }
    }

    private fun toggleTvMenu() {
        if (tvHelpOpen) {
            closeTvHelp()
            return
        }
        if (virtualMouse.isEnabled) virtualMouse.toggle()
        tvMenuOpen = !tvMenuOpen
        tvMenuOverlay.visibility = if (tvMenuOpen) View.VISIBLE else View.GONE
        if (tvMenuOpen) tvSearchButton.requestFocus() else webView.requestFocus()
    }

    private fun closeTvMenu() {
        tvMenuOpen = false
        tvMenuOverlay.visibility = View.GONE
        webView.requestFocus()
    }

    private fun openTvHelp() {
        tvMenuOpen = false
        tvMenuOverlay.visibility = View.GONE
        tvHelpOpen = true
        tvHelpOverlay.visibility = View.VISIBLE
        tvHelpCloseButton.requestFocus()
    }

    private fun closeTvHelp() {
        tvHelpOpen = false
        tvHelpOverlay.visibility = View.GONE
        webView.requestFocus()
    }

    private fun navigateTo(url: String) {
        closeTvMenu()
        if (webViewDead) {
            recreate()
            return
        }
        errorOverlay.visibility = View.GONE
        markSearchPending(url)
        webView.loadUrl(url)
    }

    private fun markSearchPending(url: String) {
        pendingOpenFirst = settings.voiceAutoOpen && url.startsWith(SEARCH_URL)
    }

    private fun openTikTokSearch() {
        closeTvMenu()
        // Uses TikTok's own search control; it only opens the normal search UI of TikTok Web.
        val js = """
            (function(){
                var selectors = [
                    '[data-e2e="nav-search"]',
                    '[data-e2e="search-icon"]',
                    'button[aria-label*="Search"]',
                    'button[aria-label*="Ara"]',
                    'a[href*="/search"]'
                ];
                for (var i=0;i<selectors.length;i++) {
                    var el=document.querySelector(selectors[i]);
                    if(el){ (el.closest('button,a,[role="button"]')||el).click(); return 'ok'; }
                }
                return 'not_found';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            if (result == "\"not_found\"") showOsd(getString(R.string.osd_search_not_found))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startPageLabel(page: StartPage): String = getString(
        when (page) {
            StartPage.FOR_YOU -> R.string.menu_tab_foryou
            StartPage.FOLLOWING -> R.string.menu_tab_following
            StartPage.EXPLORE -> R.string.menu_tab_explore
        }
    )

    private fun cursorColorFor(key: String): Int = when (key) {
        "cyan" -> Color.parseColor("#25F4EE")
        "white" -> Color.WHITE
        "yellow" -> Color.parseColor("#FFD60A")
        "green" -> Color.parseColor("#30D158")
        "purple" -> Color.parseColor("#BF5AF2")
        "blue" -> Color.parseColor("#0A84FF")
        else -> Color.parseColor("#FE2C55")
    }

    private fun applyAmoledMode() {
        if (::webView.isInitialized) {
            webView.setBackgroundColor(if (settings.amoledMode) Color.BLACK else Color.parseColor("#161823"))
        }
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    private fun makeSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = checked
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    // Checks the public GitHub Releases API off the UI thread.
    private fun checkForUpdates(manual: Boolean) {
        thread(name = "GitHubUpdateCheck") {
            try {
                val connection = (URL("https://api.github.com/repos/ArmancTeam/TikTokTV-Performance/releases/latest")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "TikTokTV-Android")
                }
                try {
                    if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
                    val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = release.optString("tag_name").removePrefix("v")
                    val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                    fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
                    val a = parts(tag); val b = parts(current)
                    val newer = (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { i ->
                        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                        if (x == y) null else x > y
                    } ?: false
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (newer) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Yeni sürüm: v$tag")
                                .setMessage("Mevcut sürüm: v$current\nYeni sürüm: v$tag\n\nYeni APK uygulama içinden indirilecek. İndirme tamamlanınca Android yükleme ekranı açılacak.")
                                .setPositiveButton("İndir ve yükle") { _, _ ->
                                    downloadLatestApk(release)
                                }
                                .setNegativeButton("Daha sonra", null)
                                .show()
                        } else if (manual) {
                            AlertDialog.Builder(this@MainActivity).setMessage("Uygulama güncel (v$current).")
                                .setPositiveButton("Tamam", null).show()
                        }
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (manual) runOnUiThread {
                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@MainActivity)
                        .setMessage("Güncelleme kontrol edilemedi. İnternet bağlantısını kontrol et.")
                        .setPositiveButton("Tamam", null).show()
                }
            }
        }
    }


    // Downloads the APK asset from the latest public GitHub release and opens Android's installer.
    private fun downloadLatestApk(release: JSONObject) {
        try {
            val assets = release.optJSONArray("assets")
            var apkUrl: String? = null
            var apkName = "TikTokTV-update.apk"
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                        apkName = name
                        apkUrl = url
                        break
                    }
                }
            }
            if (apkUrl.isNullOrBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("APK bulunamadı")
                    .setMessage("GitHub sürümünde indirilebilir bir .apk dosyası yok.")
                    .setPositiveButton("Tamam", null)
                    .show()
                return
            }

            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("TikTok TV güncellemesi")
                .setDescription("$apkName indiriliyor…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "TikTokTV-update.apk")

            val downloadId = manager.enqueue(request)
            showApkDownloadProgress(manager, downloadId, apkName)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start APK download", e)
            AlertDialog.Builder(this)
                .setTitle("İndirme başlatılamadı")
                .setMessage("İndirme başlatılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}")
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    private fun showApkDownloadProgress(manager: DownloadManager, downloadId: Long, apkName: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val status = TextView(this).apply {
            text = "GitHub'dan APK indiriliyor…"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        layout.addView(status)
        layout.addView(progress)
        val dialog = AlertDialog.Builder(this)
            .setTitle("TikTok TV güncellemesi")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Kapat", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        dialog.show()

        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) {
                    dialog.dismiss()
                    return
                }
                val query = DownloadManager.Query().setFilterById(downloadId)
                manager.query(query).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        if (dialog.isShowing) dialog.dismiss()
                        showUpdateError("İndirme kaydı bulunamadı.")
                        return
                    }
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val state = cursor.getInt(statusCol)
                    val downloaded = cursor.getLong(downloadedCol)
                    val total = cursor.getLong(totalCol)
                    if (total > 0) {
                        progress.isIndeterminate = false
                        progress.progress = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        status.text = "İndiriliyor: ${progress.progress}%  (${formatBytes(downloaded)} / ${formatBytes(total)})"
                    } else {
                        progress.isIndeterminate = true
                        status.text = "APK indiriliyor… ${formatBytes(downloaded)}"
                    }
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            if (dialog.isShowing) dialog.dismiss()
                            val uri = manager.getUriForDownloadedFile(downloadId)
                            if (uri == null) {
                                showUpdateError("İndirilen APK dosyasına erişilemedi.")
                            } else {
                                installDownloadedApk(uri)
                            }
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            if (dialog.isShowing) dialog.dismiss()
                            val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else -1
                            showUpdateError("APK indirilemedi. Hata kodu: $reason")
                            return
                        }
                    }
                }
                uiHandler.postDelayed(this, 500)
            }
        }
        uiHandler.post(poll)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun installDownloadedApk(uri: Uri) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstallUri = uri
            AlertDialog.Builder(this)
                .setTitle("Yükleme izni gerekli")
                .setMessage("Android, uygulama dışından indirilen APK'nın yüklenmesine izin vermiyor. Açılan ayarlarda bu uygulama için 'Bu kaynaktan izin ver' seçeneğini aç. Geri döndüğünde yükleyici otomatik açılacak.")
                .setPositiveButton("Ayarlara git") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        pendingInstallUri = null
                        showUpdateError("Bilinmeyen kaynak ayarları açılamadı.")
                    }
                }
                .setNegativeButton("İptal") { _, _ -> pendingInstallUri = null }
                .show()
            return
        }
        launchApkInstaller(uri)
    }

    private fun launchApkInstaller(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showUpdateError("Bu cihazda APK yükleyicisi bulunamadı.")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch APK installer", e)
            showUpdateError("APK yükleyicisi açılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}")
        }
    }

    private fun showUpdateError(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Güncelleme hatası")
            .setMessage(message)
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun showSettingsDialog() {
        var reloadNeeded = false
        var dialog: AlertDialog? = null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        // Mouse speed
        val speedLabel = TextView(this).apply {
            text = getString(R.string.settings_mouse_speed, String.format("%.1f", virtualMouse.speedMultiplier))
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val speedSeek = SeekBar(this).apply {
            max = 15
            progress = ((virtualMouse.speedMultiplier - 0.5f) * 10f).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 0.5f + progress / 10f
                    virtualMouse.speedMultiplier = value
                    settings.mouseSpeed = value
                    speedLabel.text = getString(R.string.settings_mouse_speed, String.format("%.1f", value))
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(speedLabel)
        container.addView(speedSeek)

        // Toggles
        container.addView(makeSwitch(getString(R.string.settings_auto_next), settings.autoNext) { value ->
            settings.autoNext = value
            bridgeCall("window.TikTokTV.setAutoNext($value)")
        })
        container.addView(makeSwitch(getString(R.string.settings_block_trackers), settings.blockTrackers) { value ->
            settings.blockTrackers = value
            blockTrackers = value
        })
        container.addView(makeSwitch(getString(R.string.settings_disable_av1), settings.disableAv1) { value ->
            settings.disableAv1 = value
            reloadNeeded = true // the AV1 switch is read when the page loads
        })
        container.addView(makeSwitch(getString(R.string.settings_edge_scroll), settings.edgeScroll) { value ->
            settings.edgeScroll = value
            virtualMouse.edgeScrollEnabled = value
        })
        container.addView(makeSwitch(getString(R.string.settings_voice_auto_open), settings.voiceAutoOpen) { value ->
            settings.voiceAutoOpen = value
        })

        container.addView(makeSwitch(getString(R.string.settings_amoled), settings.amoledMode) { value ->
            settings.amoledMode = value
            applyAmoledMode()
            reloadNeeded = true
        })

        container.addView(makeSwitch("✨ Neon efekti", settings.neonEnabled) { value ->
            settings.neonEnabled = value
            virtualMouse.neonEnabled = value
        })

        val neonLabel = TextView(this).apply {
            text = "🌈 Neon parlaklığı: ${settings.neonIntensity}%"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
        }
        val neonSeek = SeekBar(this).apply {
            max = 75
            progress = settings.neonIntensity - 25
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 25 + progress
                    settings.neonIntensity = value
                    virtualMouse.neonIntensity = value
                    neonLabel.text = "🌈 Neon parlaklığı: ${value}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(neonLabel)
        container.addView(neonSeek)

        val cursorColorButton = Button(this).apply {
            text = getString(R.string.settings_cursor_color, settings.cursorColorKey.uppercase(Locale.ROOT))
            setOnClickListener {
                val keys = arrayOf("pink", "cyan", "white", "yellow", "green", "purple", "blue")
                val labels = arrayOf(
                    getString(R.string.cursor_pink), getString(R.string.cursor_cyan),
                    getString(R.string.cursor_white), getString(R.string.cursor_yellow),
                    getString(R.string.cursor_green), getString(R.string.cursor_purple),
                    getString(R.string.cursor_blue)
                )
                val selected = keys.indexOf(settings.cursorColorKey).coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.settings_cursor_color_title)
                    .setSingleChoiceItems(labels, selected) { colorDialog, which ->
                        settings.cursorColorKey = keys[which]
                        virtualMouse.cursorColor = cursorColorFor(keys[which])
                        text = getString(R.string.settings_cursor_color, labels[which])
                        colorDialog.dismiss()
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }
        container.addView(cursorColorButton)

        container.addView(Button(this).apply {
            text = "🔄 Güncellemeleri kontrol et"
            setOnClickListener { dialog?.dismiss(); checkForUpdates(true) }
        })

        // Start page (cycles on each click)
        val startPageButton = Button(this).apply {
            text = getString(R.string.settings_start_page, startPageLabel(settings.startPage))
            setOnClickListener {
                settings.startPage = settings.startPage.next()
                text = getString(R.string.settings_start_page, startPageLabel(settings.startPage))
            }
        }
        container.addView(startPageButton)

        // Sign out / clear data
        val clearButton = Button(this).apply {
            text = getString(R.string.settings_clear_data)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.settings_clear_confirm_title)
                    .setMessage(R.string.settings_clear_confirm_message)
                    .setPositiveButton(R.string.settings_clear_action) { _, _ ->
                        reloadNeeded = false
                        dialog?.dismiss()
                        clearAllData()
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }
        container.addView(clearButton)

        val scroll = ScrollView(this).apply { addView(container) }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(scroll)
            .setPositiveButton(R.string.common_close, null)
            .setOnDismissListener { if (reloadNeeded && !webViewDead) webView.reload() }
            .show()
    }

    private fun clearAllData() {
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        closeTvMenu()
        webView.loadUrl(settings.startPage.url)
        showOsd(getString(R.string.settings_clear_done))
    }

    // ------------------------------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        applyAmoledMode()
        // LAYER_TYPE_NONE keeps GPU acceleration without re-uploading a full-screen texture every frame.
        // If the video turns black on a particular box, revert this line to LAYER_TYPE_HARDWARE.
        webView.setLayerType(View.LAYER_TYPE_NONE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

            // Desktop layout needs a wide logical viewport (fixes left bar clipping)
            useWideViewPort = true
            loadWithOverviewMode = true
            offscreenPreRaster = true

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Hardened: the app never needs local files or content:// providers.
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            // Safe Browsing is left at its default (on). If an HTTP-only CDN ever breaks playback,
            // MIXED_CONTENT_ALWAYS_ALLOW (previous behaviour) can be restored here.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // Debugging socket only in debuggable builds
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (blockTrackers && UrlPolicy.isTracker(request?.url?.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return false
                val url = request.url.toString()

                // Google blocks OAuth inside WebViews / Custom Tabs: offer alternatives instead.
                if (UrlPolicy.isGoogleAuth(url)) {
                    Log.d(TAG, "Intercepted Google sign-in URL")
                    showGoogleLoginAlternative()
                    return true
                }

                // Main-frame navigation must stay on TikTok (or a login provider); block everything else.
                if (request.isForMainFrame && !UrlPolicy.isAllowedNavigation(url)) {
                    Log.w(TAG, "Blocked navigation to ${request.url.host}")
                    showOsd(getString(R.string.osd_blocked_link))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                lastLoadFailed = false
                errorOverlay.visibility = View.GONE

                // Do not inject scripts into Google pages (in case one slips through).
                if (UrlPolicy.isGoogleAuth(url)) return

                // Head-safe viewport fix: removes TikTok's viewport and forces width=1280 (16:9, no cropping).
                val antiTouchScript = """
                    (function() {
                        try {
                            var isLoginPage = /\\/(login|passport)(\\/|\\?|$)/i.test(location.pathname);
                            if (isLoginPage) return;

                            Object.defineProperty(navigator, 'maxTouchPoints', {
                                configurable: true,
                                get: function () { return 0; }
                            });
                            Object.defineProperty(navigator, 'msMaxTouchPoints', {
                                configurable: true,
                                get: function () { return 0; }
                            });

                            function forceVP() {
                                try {
                                    var head = document.head ||
                                        document.getElementsByTagName('head')[0] ||
                                        document.documentElement;
                                    if (!head) return;
                                    var old = document.querySelectorAll('meta[name="viewport"]');
                                    for (var i = 0; i < old.length; i++) {
                                        if (old[i].parentNode) old[i].parentNode.removeChild(old[i]);
                                    }
                                    var m = document.createElement('meta');
                                    m.name = 'viewport';
                                    m.content = 'width=1280';
                                    head.appendChild(m);
                                } catch (e) {}
                            }

                            forceVP();
                            if (document.readyState === 'loading') {
                                document.addEventListener('DOMContentLoaded', forceVP, { once: true });
                            }
                        } catch (e) {}
                    })();
                """.trimIndent()
                view?.evaluateJavascript(antiTouchScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (lastLoadFailed) return
                injectBridge()
                // Keep the splash until a video is really ready (or a safety cap is hit)
                uiHandler.postDelayed({ hideLoadingWhenReady(0) }, 800)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                lastLoadFailed = true
                val code = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN
                val offline = !isOnline() ||
                        code == WebViewClient.ERROR_HOST_LOOKUP ||
                        code == WebViewClient.ERROR_CONNECT ||
                        code == WebViewClient.ERROR_TIMEOUT ||
                        code == WebViewClient.ERROR_IO
                if (offline) {
                    showError(R.string.error_offline_title, getString(R.string.error_offline_message))
                } else {
                    showError(R.string.error_generic_title, getString(R.string.error_generic_message))
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                val status = errorResponse?.statusCode ?: 0
                if (status >= 500) {
                    lastLoadFailed = true
                    showError(R.string.error_generic_title, getString(R.string.error_http_message, status))
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "WebView renderer gone (crashed=${detail?.didCrash()})")
                webViewDead = true
                val now = SystemClock.elapsedRealtime()
                val crashLoop = now - lastRendererCrashAt < RENDERER_CRASH_LOOP_MS
                lastRendererCrashAt = now
                runOnUiThread {
                    if (crashLoop) {
                        // Crashed again right after a restart: stop and let the user decide.
                        showError(R.string.error_renderer_title, getString(R.string.error_renderer_message))
                    } else {
                        recreate()
                    }
                }
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    request.addRequestHeader("Cookie", cookie)
                }
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                request.setDescription("Video indiriliyor")
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalFilesDir(
                    this,
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                showOsd("İndirme başlatıldı")
            } catch (e: Exception) {
                Log.e(TAG, "Video download failed", e)
                showOsd("İndirme başlatılamadı")
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private fun showError(titleRes: Int, message: String) {
        loadingOverlay.visibility = View.GONE
        errorTitle.setText(titleRes)
        errorMessage.text = message
        errorOverlay.visibility = View.VISIBLE
    }

    private fun retryLoad() {
        if (webViewDead) {
            recreate()
            return
        }
        errorOverlay.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        val current = webView.url
        if (current.isNullOrEmpty() || current == "about:blank") {
            webView.loadUrl(settings.startPage.url)
        } else {
            webView.reload()
        }
    }

    private fun showGoogleLoginAlternative() {
        if (googleDialog?.isShowing == true) return
        googleDialog = AlertDialog.Builder(this)
            .setTitle(R.string.google_login_title)
            .setMessage(R.string.google_login_message)
            .setPositiveButton(R.string.google_login_qr) { _, _ -> webView.loadUrl(QR_LOGIN_URL) }
            .setNeutralButton(R.string.google_login_page) { _, _ -> webView.loadUrl(LOGIN_URL) }
            .setNegativeButton(R.string.common_cancel) { dialog, _ ->
                dialog.dismiss()
                if (webView.canGoBack()) webView.goBack()
            }
            .setCancelable(false)
            .show()
    }

    private fun hideLoadingWhenReady(attempt: Int) {
        if (loadingOverlay.visibility != View.VISIBLE) return
        // Feed pages wait for a decodable video (cap ~7.8 s); other pages (search, explore, login) only ~2.3 s.
        val url = webView.url ?: ""
        val isFeed = url.contains("/foryou") || url.contains("/following") || url.trimEnd('/').endsWith("tiktok.com")
        val maxAttempts = if (isFeed) 14 else 3
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return (v && v.readyState>=2 && v.videoWidth>0)?'ready':'wait';})()"
        ) { result ->
            val ready = result != null && result.contains("ready")
            if (ready || attempt >= maxAttempts) {
                loadingOverlay.visibility = View.GONE
            } else {
                uiHandler.postDelayed({ hideLoadingWhenReady(attempt + 1) }, 500)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Network monitoring
    // ------------------------------------------------------------------------------------------

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network is back while the error screen is showing: reload by ourselves.
                runOnUiThread {
                    if (errorOverlay.visibility == View.VISIBLE && !webViewDead) retryLoad()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister network callback", e)
        }
        networkCallback = null
    }

    // ------------------------------------------------------------------------------------------
    // JS bridge helpers
    // ------------------------------------------------------------------------------------------

    private fun injectBridge() {
        if (preloadedBridgeScript.isEmpty() || webViewDead) return
        val cfg = "window.__TKTV_CFG__={disableAv1:${settings.disableAv1},autoNext:${settings.autoNext},amoled:${settings.amoledMode}};"
        try {
            webView.evaluateJavascript(cfg + preloadedBridgeScript) {
                bridgeCall("window.TikTokTV.setAutoNext(${settings.autoNext})")
                if (cleanMode) bridgeCall("window.TikTokTV.setCleanMode(true)")
                if (pendingOpenFirst && (webView.url ?: "").contains("/search")) {
                    pendingOpenFirst = false
                    bridgeCall("window.TikTokTV.openFirstResult()")
                }
                uiHandler.postDelayed({ sendBridgeCommand("playIfPaused") }, 1500)
                uiHandler.postDelayed({ runBridgeSelfTest() }, 4000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bridge injection failed", e)
        }
    }

    /** Evaluates `expr` inside a guard and passes the (unquoted) string result to [onResult]. */
    private fun bridgeCall(expr: String, onResult: ((String) -> Unit)? = null) {
        if (webViewDead) return
        val js = "(function(){try{if(window.TikTokTV){return $expr;}}catch(e){}return 'NOT_FOUND';})()"
        webView.evaluateJavascript(js) { raw -> onResult?.invoke(raw?.trim('"') ?: "") }
    }

    private fun sendBridgeCommand(command: String) = bridgeCall("window.TikTokTV.$command()")

    /** Logs how many of the elements the bridge depends on exist; helps spot TikTok DOM changes. */
    private fun runBridgeSelfTest() {
        bridgeCall("window.TikTokTV.selfTest()") { result ->
            Log.i(TAG, "bridge self-test: $result")
            if (result == "NOT_FOUND") {
                Log.w(TAG, "bridge not available on this page")
            } else if (result.contains("video=0") || result.contains("active=0")) {
                Log.w(TAG, "bridge selectors found no video/active item (normal on non-feed pages)")
            }
        }
    }

    private fun seekBy(seconds: Int) {
        bridgeCall("window.TikTokTV.seek($seconds)") { result ->
            if (result == "seek") showOsd(if (seconds > 0) "⏩ +${seconds}s" else "⏪ ${seconds}s")
        }
    }

    private fun setCleanMode(on: Boolean, announce: Boolean = true) {
        cleanMode = on
        bridgeCall("window.TikTokTV.setCleanMode($on)")
        if (announce) showOsd(getString(if (on) R.string.osd_clean_on else R.string.osd_clean_off))
    }

    private fun togglePlay() {
        bridgeCall("window.TikTokTV.togglePlay()") { result ->
            when (result) {
                "playing" -> showOsd("▶")
                "paused" -> showOsd("⏸")
            }
        }
    }

    private fun toggleMute() {
        bridgeCall("window.TikTokTV.toggleMute()") { result ->
            when (result) {
                "muted" -> showOsd(getString(R.string.osd_muted))
                "unmuted" -> showOsd(getString(R.string.osd_unmuted))
            }
        }
    }

    private fun scrollAt(x: Float, y: Float, dy: Int) {
        val expr = String.format(
            Locale.US, "window.TikTokTV.scrollAt(%.1f,%.1f,%d,%d,%d)", x, y, webView.width, webView.height, dy
        )
        bridgeCall(expr)
    }


    private fun shareCurrentVideo() {
        if (webViewDead) {
            showOsd("TikTok sayfası hazır değil")
            return
        }

        bridgeCall("window.TikTokTV.getShareUrl()") { result ->
            runOnUiThread {
                val videoUrl = result
                    ?.trim('"')
                    ?.replace("\\u003D", "=")
                    ?.replace("\\u0026", "&")
                    ?.replace("\\/", "/")
                    .orEmpty()

                if (!videoUrl.startsWith("https://www.tiktok.com/") ||
                    videoUrl.contains("/foryou") ||
                    videoUrl.contains("/login")) {
                    showOsd("Video bağlantısı bulunamadı")
                    return@runOnUiThread
                }

                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, videoUrl)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Videoyu paylaş"))
                } catch (e: Exception) {
                    Log.e(TAG, "Video share failed", e)
                    showOsd("Paylaşım açılamadı")
                }
            }
        }
    }

    private fun showOsd(message: String) {
        osdText.text = message
        osdText.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideOsdRunnable)
        uiHandler.postDelayed(hideOsdRunnable, OSD_DURATION_MS)
    }

    // ------------------------------------------------------------------------------------------
    // Voice search
    // ------------------------------------------------------------------------------------------

    /**
     * Listens inside the app (overlay + partial results). Uses the device's recognition service when there is
     * one, otherwise falls back to the system voice dialog. The video is paused while listening so TikTok's
     * own audio is not picked up by the microphone.
     */
    private fun startVoiceSearch() {
        if (voiceActive || webViewDead) return
        closeTvMenu()
        if (virtualMouse.isEnabled) virtualMouse.toggle()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoiceDialog()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startInlineRecognition()
    }

    private fun startInlineRecognition() {
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer unavailable", e)
            null
        }
        if (recognizer == null) {
            startVoiceDialog()
            return
        }

        voiceActive = true
        speechRecognizer = recognizer
        sendBridgeCommand("pauseAll")
        showVoiceOverlay(getString(R.string.voice_listening), "")

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceTitle.text = getString(R.string.voice_listening)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // Pulse the microphone with the voice level (rms is roughly -2..10).
                val scale = 1f + rmsdB.coerceIn(0f, 10f) / 10f * 0.4f
                voiceMic.animate().scaleX(scale).scaleY(scale).setDuration(80).start()
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) voicePartial.text = text
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishVoiceUi()
                if (text.isNullOrBlank()) {
                    showOsd(getString(R.string.voice_no_match))
                    resumeAfterVoice()
                } else {
                    performVoiceQuery(text)
                }
            }

            override fun onError(error: Int) {
                finishVoiceUi()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        showOsd(getString(R.string.voice_no_match))
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        showOsd(getString(R.string.voice_permission_denied))
                    else -> showOsd(getString(R.string.voice_unavailable))
                }
                resumeAfterVoice()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            finishVoiceUi()
            showOsd(getString(R.string.voice_unavailable))
            resumeAfterVoice()
        }
    }

    private fun startVoiceDialog() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            sendBridgeCommand("pauseAll")
            voiceDialogLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            showOsd(getString(R.string.voice_unavailable))
            resumeAfterVoice()
        }
    }

    private fun showVoiceOverlay(title: String, partial: String) {
        voiceTitle.text = title
        voicePartial.text = partial
        voiceMic.scaleX = 1f
        voiceMic.scaleY = 1f
        voiceOverlay.visibility = View.VISIBLE
    }

    /** Hides the overlay and releases the recognizer (safe to call more than once). */
    private fun finishVoiceUi() {
        voiceActive = false
        voiceOverlay.visibility = View.GONE
        val recognizer = speechRecognizer
        speechRecognizer = null
        try { recognizer?.destroy() } catch (e: Exception) { }
    }

    private fun cancelVoice() {
        try { speechRecognizer?.cancel() } catch (e: Exception) { }
        finishVoiceUi()
        resumeAfterVoice()
    }

    private fun performVoiceQuery(text: String) {
        val query = VoiceQuery.clean(text)
        if (query.isEmpty()) {
            resumeAfterVoice()
            return
        }
        showOsd("🔍 $query")
        navigateTo(SEARCH_URL + Uri.encode(query))
    }

    private fun resumeAfterVoice() {
        sendBridgeCommand("playIfPaused")
    }

    // ------------------------------------------------------------------------------------------
    // Key handling
    // ------------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // BACK is handled once per press; held-down repeats must not exit the app.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) performBack()
            return true
        }

        if (voiceActive) {
            // OK = "I am done talking"; BACK (above) cancels. Everything else is ignored while listening.
            if (down && event.repeatCount == 0 &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                try { speechRecognizer?.stopListening() } catch (e: Exception) { }
            }
            return true
        }

        if (tvHelpOpen) {
            if (down) {
                when (keyCode) {
                    KeyEvent.KEYCODE_MENU -> closeTvHelp()
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                        if (tvHelpCloseButton.hasFocus()) closeTvHelp()
                }
            }
            return true
        }

        if (tvMenuOpen) {
            if (down && keyCode == KeyEvent.KEYCODE_MENU) {
                closeTvMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (keyCode == KeyEvent.KEYCODE_PROG_GREEN) {
            if (down && event.repeatCount == 0) setCleanMode(!cleanMode)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_PROG_RED) {
            if (down) {
                if (event.repeatCount == 0) {
                    redLongPressed = false
                    uiHandler.postDelayed(redLongPressRunnable, RED_LONG_PRESS_MS)
                }
            } else {
                uiHandler.removeCallbacks(redLongPressRunnable)
                if (!redLongPressed) {
                    // Short press opens the settings panel.
                    showSettingsDialog()
                }
            }
            return true
        }

        if (keyCode in HANDLED_MEDIA_KEYS) {
            if (down) handleMediaKey(event)
            return true
        }

        val consumed = if (down) handleKeyDown(event) else handleKeyUp(event)
        return consumed || super.dispatchKeyEvent(event)
    }

    /** Remote microphone / search buttons (only reach the app when the system does not take them first). */
    private fun isMicKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_ASSIST

    private fun handleMediaKey(event: KeyEvent) {
        val repeat = event.repeatCount
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (repeat == 0) togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (repeat == 0) sendBridgeCommand("play")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (repeat == 0) sendBridgeCommand("pause")
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD ->
                if (repeat % 4 == 0) seekBy(SEEK_SECONDS)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD ->
                if (repeat % 4 == 0) seekBy(-SEEK_SECONDS)
            // CH+/PageUp/Previous go the same way as D-pad up (previous video); CH-/PageDown/Next go down.
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                if (repeat == 0) pageVideo(-1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT ->
                if (repeat == 0) pageVideo(1)
        }
    }

    /** Next/previous video, or scroll under the cursor while the virtual mouse is active. */
    private fun pageVideo(direction: Int) {
        if (virtualMouse.isEnabled) {
            scrollAt(virtualMouse.x, virtualMouse.y, direction * SCROLL_STEP)
        } else {
            sendBridgeCommand(if (direction < 0) "scrollUp" else "scrollDown")
        }
    }

    /** Holding left/right seeks: first repeat leaves the sidebar, then every ~8th repeat seeks 10 s. */
    private fun longPressSeek(seconds: Int, repeatCount: Int) {
        if (repeatCount == 1) sendBridgeCommand("exitSidebar")
        if (repeatCount == 1 || repeatCount % 8 == 0) seekBy(seconds)
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleTvMenu()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) {
                val delay = if (virtualMouse.isEnabled) OK_LONG_PRESS_OFF_MS else OK_LONG_PRESS_MS
                uiHandler.postDelayed(okLongPressRunnable, delay)
                virtualMouse.pressOk() // no-op unless the virtual mouse is on
            }
            return true
        }

        // Remotes that deliver their microphone button to the app (the Assistant may intercept it first).
        if (isMicKey(keyCode)) {
            if (event.repeatCount == 0) startVoiceSearch()
            return true
        }

        if (virtualMouse.isEnabled) {
            if (virtualMouse.onDirectionDown(keyCode)) {
                uiHandler.removeCallbacks(okLongPressRunnable)
                return true
            }
            return false
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> sendBridgeCommand("scrollDown")
            KeyEvent.KEYCODE_DPAD_UP -> sendBridgeCommand("scrollUp")
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (event.repeatCount == 0) {
                    if (cleanMode) setCleanMode(false, announce = false) // the buttons must be visible to use them
                    sendBridgeCommand("enterSidebar")
                }
                else longPressSeek(SEEK_SECONDS, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (event.repeatCount == 0) sendBridgeCommand("exitSidebar")
                else longPressSeek(-SEEK_SECONDS, event.repeatCount)
            else -> return false
        }
        return true
    }

    private fun handleKeyUp(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            uiHandler.removeCallbacks(okLongPressRunnable)
            if (virtualMouse.isEnabled) {
                virtualMouse.releaseOk()
            } else if (event.eventTime - event.downTime < OK_LONG_PRESS_MS) {
                if (errorOverlay.visibility == View.VISIBLE) retryButton.performClick()
                else bridgeCall("window.TikTokTV.select()") { result ->
                        if (result == "native_share") {
                            runOnUiThread { shareCurrentVideo() }
                        }
                    }
            }
            return true
        }

        if (isMicKey(keyCode)) return true

        if (virtualMouse.isEnabled) return virtualMouse.onDirectionUp(keyCode)

        // Swallow released D-pad keys so the WebView never takes native control (e.g. history back).
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }
}
