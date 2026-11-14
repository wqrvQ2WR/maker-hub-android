package com.hayoonjae.makerhub

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding

class MainActivity : ComponentActivity() {

    private class Tab(val site: Site, var web: WebView) {
        var loaded = false
        var progress = 100
        var error: String? = null
        var lastUrl: String? = null
        val popups = ArrayList<WebView>()
    }

    private lateinit var main: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var titleView: TextView
    private lateinit var hostView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView
    private lateinit var popupLayer: View
    private lateinit var popupHeader: View
    private lateinit var popupTitle: TextView
    private lateinit var popupHolder: FrameLayout
    private lateinit var bottomBar: View
    private lateinit var tabRow: LinearLayout
    private lateinit var fullscreen: FrameLayout

    private lateinit var downloads: Downloads
    private val tabs = LinkedHashMap<Site, Tab>()
    private val tabItems = HashMap<Site, View>()
    private var current = Site.RASPBERRY_PI
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val defaultUserAgent by lazy { WebSettings.getDefaultUserAgent(this) }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode != RESULT_OK || data == null) {
            null
        } else {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        downloads = Downloads(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)

        for (site in Site.entries) {
            if (site.needsOwnAddress) UrlPolicy.hostAndPath(homeOf(site))?.first?.let { UrlPolicy.ownHosts += it }
            val web = newWebView(site, popup = false)
            web.isVisible = false
            webContainer.addView(web)
            tabs[site] = Tab(site, web)
        }
        buildTabRow()
        wireChrome()

        if (savedInstanceState != null) {
            current = runCatching { Site.valueOf(savedInstanceState.getString(KEY_CURRENT)!!) }.getOrDefault(current)
            for (tab in tabs.values) {
                val state = savedInstanceState.getBundle(KEY_WEB + tab.site.name) ?: continue
                if (tab.web.restoreState(state) != null) tab.loaded = true
            }
        } else {
            current = runCatching { Site.valueOf(prefs.getString(KEY_CURRENT, null)!!) }.getOrDefault(current)
        }
        select(current)
    }

    private fun bindViews() {
        main = findViewById(R.id.main)
        topBar = findViewById(R.id.topBar)
        backButton = findViewById(R.id.back)
        titleView = findViewById(R.id.title)
        hostView = findViewById(R.id.host)
        progress = findViewById(R.id.progress)
        webContainer = findViewById(R.id.webContainer)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retry)
        popupLayer = findViewById(R.id.popupLayer)
        popupHeader = findViewById(R.id.popupHeader)
        popupTitle = findViewById(R.id.popupTitle)
        popupHolder = findViewById(R.id.popupHolder)
        bottomBar = findViewById(R.id.bottomBar)
        tabRow = findViewById(R.id.tabRow)
        fullscreen = findViewById(R.id.fullscreen)
    }

    private fun wireChrome() {
        backButton.setOnClickListener { handleBack() }
        findViewById<View>(R.id.reload).setOnClickListener {
            val tab = tabs.getValue(current)
            tab.error = null
            tab.web.reload()
            refreshChrome()
        }
        findViewById<View>(R.id.more).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.titleBlock).setOnClickListener { askAddress() }
        retryButton.setOnClickListener {
            val tab = tabs.getValue(current)
            tab.error = null
            if (homeOf(current).isBlank()) {
                askAddress()
            } else if (tab.web.url.isNullOrEmpty()) {
                tab.web.loadUrl(tab.lastUrl ?: homeOf(current))
            } else {
                tab.web.reload()
            }
            refreshChrome()
        }
        findViewById<View>(R.id.popupClose).setOnClickListener {
            tabs.getValue(current).popups.lastOrNull()?.let { closePopup(current, it) }
        }

        ViewCompat.setOnApplyWindowInsetsListener(main) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topBar.updatePadding(top = bars.top)
            main.updatePadding(left = bars.left, right = bars.right, bottom = if (imeVisible) ime.bottom else 0)
            bottomBar.updatePadding(bottom = bars.bottom)
            // 키보드가 올라오면 하단 탭을 숨겨 입력칸(코드 에디터, 원격 셸)이 가려지지 않게 한다.
            bottomBar.isVisible = !imeVisible
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // ---- 웹뷰 ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(site: Site, popup: Boolean): WebView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.WHITE)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = userAgentFor(site)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(downloads.bridge, Downloads.BRIDGE)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloads.start(this, url, userAgent, contentDisposition, mimeType)
        }
        webViewClient = SiteClient(site, popup)
        webChromeClient = SiteChrome(site, popup)
    }

    private fun isDesktop(site: Site) = prefs.getBoolean(KEY_DESKTOP + site.name, false)

    private fun userAgentFor(site: Site) =
        if (isDesktop(site)) UserAgents.desktop(defaultUserAgent) else UserAgents.mobile(defaultUserAgent)

    private inner class SiteClient(private val site: Site, private val popup: Boolean) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return navigateOut(view, request.url.toString(), popup)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // window.open 으로 열린 창은 첫 이동이 shouldOverrideUrlLoading 을 안 거칠 수 있어 여기서 한 번 더 거른다.
            if (popup && UrlPolicy.route(url) == Route.BROWSER) {
                view.stopLoading()
                openInBrowser(url)
                closePopup(site, view)
                return
            }
            if (!popup) tabs.getValue(site).error = null
            if (site == current) refreshChrome()
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(Downloads.HOOK_JS, null)
            injectConnectFix(view, url)
            if (site == current) refreshChrome()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // Connect 는 Turbo 로 페이지를 바꿔서 onPageFinished 없이 화면공유 페이지로 넘어갈 수 있다.
            injectConnectFix(view, url)
            if (!popup) tabs.getValue(site).lastUrl = url
            if (site == current) refreshChrome()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (popup || !request.isForMainFrame) return
            val description = error.description?.toString().orEmpty()
            if ("ERR_ABORTED" in description) return
            tabs.getValue(site).error = description.ifEmpty { "네트워크 오류" }
            if (site == current) refreshChrome()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recoverAfterCrash(site, view)
            return true
        }
    }

    private inner class SiteChrome(private val site: Site, private val popup: Boolean) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (popup) return
            tabs.getValue(site).progress = newProgress
            if (site == current) refreshProgress()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (site == current) refreshChrome()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // target="_blank" 링크는 새 창 대신 지금 화면에서 연다. 새 창이 필요한 건 window.open(로그인 팝업)뿐이다.
            val hit = view.hitTestResult
            val link = hit.extra
            if (isUserGesture && hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && link != null && isRealLink(link, view.url)) {
                when (UrlPolicy.route(link)) {
                    Route.IN_APP, Route.WEBVIEW -> view.loadUrl(link)
                    Route.BROWSER -> openInBrowser(link)
                    Route.SYSTEM -> openSystem(view, link)
                }
                return false
            }
            val child = newWebView(site, popup = true)
            tabs.getValue(site).popups += child
            if (site == current) refreshChrome()
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            closePopup(site, window)
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            // accept=".ino,.zip" 처럼 확장자만 오면 MIME 으로 못 바꿔서 선택기가 비어 버린다. 전부 보여준다.
            val mimes = params.acceptTypes.orEmpty().map { it.trim() }.filter { '/' in it }.distinct()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (mimes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                filePicker.launch(Intent.createChooser(intent, null))
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                toast("파일을 고를 앱이 없어요")
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.isVisible = true
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        // 기본값은 회색 재생 버튼이라 포스터 없는 배경 영상(arduino.cc 첫 화면) 위에 크게 뜬다.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private val connectFixJs by lazy { assets.open("connect_fix.js").bufferedReader().use { it.readText() } }

    /** 화면공유 해상도 보정. 스크립트가 스스로 중복 실행을 막으니 여러 번 넣어도 된다. */
    private fun injectConnectFix(view: WebView, url: String) {
        if (UrlPolicy.hostAndPath(url)?.first == "connect.raspberrypi.com") view.evaluateJavascript(connectFixJs, null)
    }

    /** href="#" 나 javascript: 링크에서 window.open 을 부르는 경우는 진짜 링크가 아니다. */
    private fun isRealLink(link: String, pageUrl: String?): Boolean {
        if (link.startsWith("javascript:")) return false
        val page = pageUrl?.substringBefore('#') ?: return true
        return link.substringBefore('#') != page
    }

    /** true 면 웹뷰가 이 주소를 열지 않는다. */
    private fun navigateOut(view: WebView, url: String, popup: Boolean): Boolean =
        when (UrlPolicy.route(url)) {
            Route.IN_APP, Route.WEBVIEW -> false
            Route.BROWSER -> {
                openInBrowser(url)
                if (popup && view.copyBackForwardList().size == 0) closePopupOf(view)
                true
            }
            Route.SYSTEM -> {
                openSystem(view, url)
                true
            }
        }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            toast("열 수 있는 브라우저가 없어요")
        }
    }

    private fun openSystem(view: WebView, url: String) {
        val intent = if (url.startsWith("intent:")) {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        try {
            if (intent == null) throw ActivityNotFoundException()
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback = intent?.getStringExtra("browser_fallback_url")
            when {
                fallback == null -> toast("열 수 있는 앱이 없어요")
                UrlPolicy.route(fallback) == Route.IN_APP -> view.loadUrl(fallback)
                else -> openInBrowser(fallback)
            }
        }
    }

    // ---- 팝업(로그인 창) ----

    private fun closePopupOf(view: WebView) {
        tabs.values.firstOrNull { view in it.popups }?.let { closePopup(it.site, view) }
    }

    private fun closePopup(site: Site, view: WebView) {
        val tab = tabs.getValue(site)
        if (!tab.popups.remove(view)) return
        (view.parent as? ViewGroup)?.removeView(view)
        // 웹뷰 콜백 안에서 바로 destroy 하면 크래시가 난다. 떼어 낸 뒤라 view.post 는 안 돌므로 핸들러로 미룬다.
        handler.post { view.destroy() }
        if (site == current) refreshChrome()
    }

    private fun recoverAfterCrash(site: Site, dead: WebView) {
        val tab = tabs.getValue(site)
        if (dead in tab.popups) {
            closePopup(site, dead)
            return
        }
        if (tab.web !== dead) return
        val index = webContainer.indexOfChild(dead)
        webContainer.removeView(dead)
        handler.post { dead.destroy() }
        val fresh = newWebView(site, popup = false)
        fresh.isVisible = site == current
        webContainer.addView(fresh, if (index < 0) webContainer.childCount else index)
        tab.web = fresh
        tab.progress = 100
        if (tab.loaded) fresh.loadUrl(tab.lastUrl ?: homeOf(site))
        if (site == current) refreshChrome()
    }

    // ---- 화면 갱신 ----

    private fun buildTabRow() {
        for (site in Site.entries) {
            val item = layoutInflater.inflate(R.layout.item_tab, tabRow, false)
            item.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            item.findViewById<ImageView>(R.id.icon).setImageResource(site.icon)
            item.findViewById<TextView>(R.id.label).text = site.label
            item.contentDescription = site.label
            item.setOnClickListener {
                if (site == current) {
                    // 지금 탭을 한 번 더 누르면 그 사이트 첫 화면으로.
                    openHome(site)
                    refreshChrome()
                } else {
                    select(site)
                }
            }
            tabRow.addView(item)
            tabItems[site] = item
        }
    }

    /** 주소를 직접 넣는 탭이면 저장해 둔 주소, 아니면 사이트 첫 화면. */
    private fun homeOf(site: Site): String =
        if (site.needsOwnAddress) prefs.getString(KEY_HOME + site.name, "").orEmpty() else site.home

    private fun saveHome(site: Site, url: String) {
        prefs.edit().putString(KEY_HOME + site.name, url).apply()
        UrlPolicy.hostAndPath(url)?.first?.let { UrlPolicy.ownHosts += it }
    }

    private fun openHome(site: Site) {
        val tab = tabs.getValue(site)
        val home = homeOf(site)
        tab.popups.toList().forEach { closePopup(site, it) }
        if (home.isBlank()) {
            tab.error = "${site.label} 주소를 아직 안 넣었어"
            if (site == current) refreshChrome()
            askAddress()
            return
        }
        tab.loaded = true
        tab.error = null
        tab.web.loadUrl(home)
    }

    private fun select(site: Site) {
        current = site
        prefs.edit().putString(KEY_CURRENT, site.name).apply()
        val tab = tabs.getValue(site)
        for (t in tabs.values) t.web.isVisible = t.site == site
        if (!tab.loaded) openHome(site)
        for ((s, item) in tabItems) {
            val selected = s == site
            val pill = item.findViewById<View>(R.id.pill)
            val icon = item.findViewById<ImageView>(R.id.icon)
            val label = item.findViewById<TextView>(R.id.label)
            pill.background = if (selected) gradient(s, dp(15f)) else null
            icon.imageTintList = ColorStateList.valueOf(if (selected) Color.WHITE else 0xFF8A8FA3.toInt())
            label.setTextColor(if (selected) s.colorStart else 0xFF6B7085.toInt())
            label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            item.isSelected = selected
        }
        topBar.background = gradient(site, 0f)
        popupHeader.background = gradient(site, 0f)
        retryButton.background = gradient(site, dp(24f))
        progress.progressTintList = ColorStateList.valueOf(site.colorEnd)
        refreshChrome()
    }

    private fun refreshChrome() {
        val tab = tabs.getValue(current)
        val web = tab.web
        val url = web.url ?: tab.lastUrl ?: homeOf(current)
        titleView.text = web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") && it != "about:blank" } ?: current.label
        hostView.text = displayUrl(url)
        val canBack = web.canGoBack() || tab.popups.isNotEmpty()
        backButton.isEnabled = canBack
        backButton.alpha = if (canBack) 1f else 0.45f

        errorView.isVisible = tab.error != null
        errorText.text = tab.error

        val top = tab.popups.lastOrNull()
        if (top == null) {
            popupHolder.removeAllViews()
            popupLayer.isVisible = false
        } else {
            if (top.parent !== popupHolder) {
                popupHolder.removeAllViews()
                (top.parent as? ViewGroup)?.removeView(top)
                popupHolder.addView(top)
            }
            popupLayer.isVisible = true
            popupTitle.text = top.title?.takeIf { it.isNotBlank() && it != "about:blank" } ?: displayUrl(top.url ?: "")
        }
        refreshProgress()
    }

    private fun refreshProgress() {
        val p = tabs.getValue(current).progress
        progress.progress = p
        progress.isVisible = p in 0..99
    }

    private fun displayUrl(url: String) = url.substringAfter("://").removeSuffix("/")

    private fun gradient(site: Site, radius: Float) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(site.colorStart, site.colorEnd)).apply {
            cornerRadius = radius
        }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    // ---- 메뉴 ----

    private fun showMenu(anchor: View) {
        val tab = tabs.getValue(current)
        val web = tab.web
        val url = web.url ?: tab.lastUrl ?: homeOf(current)
        PopupMenu(this, anchor).apply {
            menu.add(0, M_FORWARD, 0, "앞으로").isEnabled = web.canGoForward()
            menu.add(0, M_HOME, 1, "${current.label} 첫 화면")
            menu.add(0, M_ADDRESS, 2, "주소 입력")
            menu.add(0, M_COPY, 3, "링크 복사")
            menu.add(0, M_SHARE, 4, "공유")
            menu.add(0, M_BROWSER, 5, "외부 브라우저로 열기")
            menu.add(0, M_DESKTOP, 6, "데스크톱 사이트").apply {
                isCheckable = true
                isChecked = isDesktop(current)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    M_FORWARD -> web.goForward()
                    M_HOME -> openHome(current)
                    M_ADDRESS -> askAddress()
                    M_COPY -> {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", url))
                        if (Build.VERSION.SDK_INT < 33) toast("링크를 복사했어요")
                    }
                    M_SHARE -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                            null,
                        ),
                    )
                    M_BROWSER -> openInBrowser(url)
                    M_DESKTOP -> {
                        val site = current
                        prefs.edit().putBoolean(KEY_DESKTOP + site.name, !isDesktop(site)).apply()
                        val ua = userAgentFor(site)
                        tabs.getValue(site).web.settings.userAgentString = ua
                        tabs.getValue(site).popups.forEach { it.settings.userAgentString = ua }
                        tabs.getValue(site).web.reload()
                    }
                }
                true
            }
            show()
        }
    }

    private fun askAddress() {
        val tab = tabs.getValue(current)
        val input = EditText(this).apply {
            setText(tab.web.url ?: tab.lastUrl ?: homeOf(current))
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val box = FrameLayout(this).apply {
            val pad = dp(20f).toInt()
            setPadding(pad, dp(8f).toInt(), pad, 0)
            addView(input)
        }
        val own = current.needsOwnAddress
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (own) current.label + " 주소" else "주소 입력")
            .setMessage(if (own) "맥에서 start.sh 가 알려 준 주소를 넣어줘. 이 탭의 첫 화면으로 저장된다." else null)
            .setView(box)
            .setPositiveButton(if (own) "저장하고 열기" else "이동") { _, _ -> goTo(input.text.toString()) }
            .setNegativeButton("취소", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            goTo(input.text.toString())
            dialog.dismiss()
            true
        }
        dialog.show()
        input.requestFocus()
    }

    private fun goTo(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val url = if ("://" in text) text else "https://$text"
        val tab = tabs.getValue(current)
        // 주소를 직접 넣는 탭이면 어떤 호스트든 앱 안에서 열고, 그 주소를 첫 화면으로 저장한다.
        if (current.needsOwnAddress) {
            if (UrlPolicy.hostAndPath(url) == null) {
                toast("주소를 다시 확인해줘")
                return
            }
            saveHome(current, url)
            tab.loaded = true
            tab.error = null
            tab.web.loadUrl(url)
            refreshChrome()
            return
        }
        when (UrlPolicy.route(url)) {
            Route.IN_APP -> tab.web.loadUrl(url)
            Route.BROWSER -> openInBrowser(url)
            else -> toast("열 수 없는 주소예요")
        }
    }

    // ---- 뒤로 가기 / 생명주기 ----

    private fun handleBack(): Boolean {
        if (customView != null) {
            hideCustomView()
            return true
        }
        val tab = tabs.getValue(current)
        tab.popups.lastOrNull()?.let { popup ->
            if (popup.canGoBack()) popup.goBack() else closePopup(current, popup)
            return true
        }
        if (tab.web.canGoBack()) {
            tab.web.goBack()
            return true
        }
        return false
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreen.removeView(view)
        fullscreen.isVisible = false
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT, current.name)
        for (tab in tabs.values) {
            if (!tab.loaded) continue
            val state = Bundle()
            tab.web.saveState(state)
            outState.putBundle(KEY_WEB + tab.site.name, state)
        }
    }

    override fun onStart() {
        super.onStart()
        tabs.values.forEach { t -> t.web.onResume(); t.popups.forEach { it.onResume() } }
    }

    override fun onStop() {
        tabs.values.forEach { t -> t.web.onPause(); t.popups.forEach { it.onPause() } }
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        for (tab in tabs.values) {
            (tab.popups + tab.web).forEach { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            tab.popups.clear()
        }
        super.onDestroy()
    }

    companion object {
        private const val KEY_CURRENT = "current"
        private const val KEY_WEB = "web_"
        private const val KEY_DESKTOP = "desktop_"
        private const val KEY_HOME = "home_"

        private const val M_FORWARD = 1
        private const val M_HOME = 2
        private const val M_ADDRESS = 3
        private const val M_COPY = 4
        private const val M_SHARE = 5
        private const val M_BROWSER = 6
        private const val M_DESKTOP = 7
    }
}
