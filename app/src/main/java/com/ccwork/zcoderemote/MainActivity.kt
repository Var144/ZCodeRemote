package com.ccwork.zcoderemote

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.math.abs

/**
 * ZCode Remote 主界面：WebView 加载桌面端 ZCode 提供的远程控制 URL。
 *
 * v4（app_version>=3.16.1）远控链路新增登录：远控页检测到未登录时经
 * window.open(_blank) 弹出 Z.ai / BigModel OAuth 窗口（chat.z.ai、bigmodel.cn
 * 域族），登录态由全局 Cookie 共享，主页轮询到会话后自动继续远控。本类相应提供：
 *  - 多窗口支持（onCreateWindow）承接弹出的登录窗口，页面自关/✕/返回键均可关闭；
 *  - z.ai / bigmodel.cn 域族跳转留在应用内完成，其余跨域仍交系统浏览器；
 *  - 启用第三方 Cookie，保证 OAuth 跨站会话建立；
 *  - WebView 跟随系统暗色/亮色（prefers-color-scheme），页面「跟随系统」主题选项生效。
 *
 * 悬浮 FAB 手势：
 *  - 单击：弹出操作菜单（扫码/手动/刷新/查看URL/清缓存/关闭悬浮按钮）
 *  - 双击：隐藏 30 秒后自动恢复
 *  - 拖动：自由移动并吸附屏幕边缘
 *  - 关闭悬浮按钮：菜单内直接关闭，仅"完全重启 App"(进程死亡)后恢复
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // 进程级标志：FAB 被关闭后置 true，随进程死亡自动重置（完全重启 App 后恢复）
        @JvmStatic
        private var fabKilled = false
        private const val DOUBLE_CLICK_MS = 350L
        private const val UA_SUFFIX = " ZCodeRemote/1.2"

        // v4 登录链路涉及的域族：Z.ai OAuth(chat.z.ai) 与 BigModel(bigmodel.cn)，
        // 这些域的跳转必须留在应用内，否则登录 Cookie 无法回到本应用
        private val LOGIN_HOST_FAMILIES = arrayOf("z.ai", "bigmodel.cn")
    }

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private var fab: FloatingActionButton? = null
    private var currentUrl = ""
    private var loginOverlay: FrameLayout? = null
    private var loginWebView: WebView? = null
    private val fabHideHandler = Handler(Looper.getMainLooper())
    private val fabShowRunnable = Runnable { showFab() }

    private val configLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val url = UrlStore.read(this)
                if (url.isNotEmpty()) loadUrl(url)
            }
        }

    private val scanLauncher =
        registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
            result.contents?.let { contents ->
                val scanned = contents.trim()
                if (scanned.startsWith("http", ignoreCase = true)) {
                    UrlStore.write(this, scanned)
                    loadUrl(scanned)
                } else {
                    showInvalidUrlDialog(scanned)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 返回键：登录窗口打开时优先处理登录窗口（可回退则回退，否则关闭）；
        // 主 WebView 能回退则回退，否则结束
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val loginView = loginWebView
                if (loginView != null) {
                    if (loginView.canGoBack()) loginView.goBack() else closeLoginWindow()
                    return
                }
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout = FrameLayout(this).apply { addView(webView) }

        // 需求1：若 FAB 已被关闭(进程级静态标志)，则不再创建，仅保留 WebView
        if (!fabKilled) {
            val density = resources.displayMetrics.density
            val margin = (20 * density).toInt()
            fab = FloatingActionButton(this).apply {
                contentDescription = "重新扫码或配置（单击菜单/双击隐藏/可拖动）"
                setImageResource(android.R.drawable.ic_menu_camera)
                elevation = 12f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { setMargins(margin, margin, margin, margin) }
            }
            fab?.let { rootLayout.addView(it); setupFabGestures(it) }
        }

        setContentView(rootLayout)
        configureWebView()

        val savedUrl = UrlStore.read(this)
        if (savedUrl.isEmpty()) openConfig() else loadUrl(savedUrl)
    }

    private fun configureWebView() {
        baseWebViewSettings(webView.settings)

        // v4 登录：OAuth 在 chat.z.ai / bigmodel.cn 与 zcode.z.ai 之间建立会话，须允许跨站 Cookie
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (shouldLoadInApp(request.url)) false
                else {
                    openExternally(request.url)
                    true
                }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showLoadErrorDialog()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .show()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true

            // v4 登录：承接远控页 window.open 弹出的 OAuth 登录窗口
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                showLoginWindow(resultMsg)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
    }

    private fun loadUrl(url: String) {
        currentUrl = url
        webView.loadUrl(url)
    }

    /**
     * v4 登录跳转策略：http(s) 且同站（含互为前后缀的主机族）或属于
     * 登录域族(z.ai / bigmodel.cn)的跳转留在应用内加载；其余交系统浏览器。
     */
    private fun shouldLoadInApp(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        val currentHost = Uri.parse(currentUrl).host ?: ""
        if (host == currentHost || host.endsWith(currentHost) || currentHost.endsWith(host)) return true
        return LOGIN_HOST_FAMILIES.any { host == it || host.endsWith(".$it") }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
        }
    }

    /** 主/子 WebView 共用设置：脚本与存储、登录弹窗多窗口、暗色跟随、UA 标识 */
    private fun baseWebViewSettings(settings: WebSettings) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // v4 登录：OAuth 窗口经 window.open(_blank, noopener) 打开，
            // 必须开启多窗口支持并由 onCreateWindow 承接，否则 window.open 返回 null、登录无法发起
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString + UA_SUFFIX
            applyDarkMode(this)
        }
    }

    /**
     * 跟随系统暗色/亮色：WebView 默认始终向页面报告 prefers-color-scheme: light，
     * 普通浏览器随系统切换网页主题的能力因此失效。API 33+ 用算法暗色开关
     * （自带暗色适配的页面仅切换 prefers-color-scheme，不做强制改色）；
     * API 29–32 退回 forceDark；更早版本系统无此能力，维持 light。
     * 系统切换时 uiMode 配置变化会重建 Activity，onCreate 自动重评，无需监听。
     */
    private fun applyDarkMode(settings: WebSettings) {
        if (Build.VERSION.SDK_INT >= 33) {
            settings.isAlgorithmicDarkeningAllowed = true
        } else if (Build.VERSION.SDK_INT >= 29) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            settings.forceDark = if (night) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    /**
     * v4 登录窗口：OAuth 页在子 WebView 内完成（Cookie 全应用共享），登录成功后
     * 主页面轮询到会话即自动继续远控。页面自关(window.close→onCloseWindow)、
     * 点右上角 ✕、返回键均可关闭本窗口。
     */
    private fun showLoginWindow(resultMsg: Message) {
        closeLoginWindow()
        val density = resources.displayMetrics.density
        val margin = (12 * density).toInt()

        val child = WebView(this)
        baseWebViewSettings(child.settings)
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (shouldLoadInApp(request.url)) false
                else {
                    openExternally(request.url)
                    true
                }
        }
        child.webChromeClient = object : WebChromeClient() {
            // OAuth 完成后页面通常调用 window.close() 自关
            override fun onCloseWindow(window: WebView) {
                closeLoginWindow()
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .show()
                return true
            }
        }

        val overlay = FrameLayout(this).apply {
            elevation = 16f  // 盖过 FAB(elevation=12f)，登录期间遮住远控画面
            setBackgroundColor(0xFF161616.toInt())
        }
        overlay.addView(
            child,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        val closeBtn = Button(this).apply {
            text = "✕"
            minWidth = (48 * density).toInt()
            minHeight = (48 * density).toInt()
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xB3000000.toInt())
            elevation = 17f
            isAllCaps = false
            setOnClickListener { closeLoginWindow() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, margin, margin, 0) }
        }
        overlay.addView(closeBtn)

        rootLayout.addView(
            overlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        loginOverlay = overlay
        loginWebView = child

        (resultMsg.obj as? WebView.WebViewTransport)?.let { transport ->
            transport.webView = child
            resultMsg.sendToTarget()
        }
        Toast.makeText(this, "登录窗口已打开，完成登录后点右上角 ✕ 返回", Toast.LENGTH_LONG).show()
    }

    private fun closeLoginWindow() {
        val overlay = loginOverlay ?: return
        loginOverlay = null
        val child = loginWebView
        loginWebView = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        child?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
    }

    private fun openConfig() {
        configLauncher.launch(Intent(this, ConfigActivity::class.java))
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将 ZCode 二维码对准框内")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun showInvalidUrlDialog(scanned: String) {
        AlertDialog.Builder(this)
            .setTitle("不是有效的链接")
            .setMessage("扫码结果：\n$scanned\n\n需要 http/https 开头的链接。")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLoadErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("加载失败")
            .setMessage("ZCode Remote 链接可能已失效。\n是否重新扫码或手动填写？")
            .setPositiveButton("扫码") { _, _ -> startScan() }
            .setNeutralButton("手动填写") { _, _ -> openConfig() }
            .setNegativeButton("重试") { _, _ -> loadUrl(currentUrl) }
            .show()
    }

    // ==================== FAB 手势（单击菜单 / 双击隐藏 / 拖动）====================
    private fun setupFabGestures(fab: FloatingActionButton) {
        val density = resources.displayMetrics.density
        val touchSlop = 8f * density
        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false
        var lastClickTime = 0L

        fab.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        fab.x = ev.rawX - fab.width / 2f
                        fab.y = ev.rawY - fab.height / 2f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        clampFabToScreen(fab)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < DOUBLE_CLICK_MS) {
                            hideFab()  // 双击：隐藏30秒
                            lastClickTime = 0L
                        } else {
                            lastClickTime = now
                            fab.postDelayed({ showActionMenu() }, 360)
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun clampFabToScreen(fab: FloatingActionButton) {
        val density = resources.displayMetrics.density
        val margin = 8f * density
        val dm = resources.displayMetrics
        val maxX = dm.widthPixels - fab.width - margin
        val maxY = dm.heightPixels - fab.height - margin
        fab.x = fab.x.coerceIn(margin, maxX)
        fab.y = fab.y.coerceIn(margin, maxY)
    }

    private fun hideFab() {
        val fab = fab ?: return
        fab.animate().alpha(0f).setDuration(200).withEndAction {
            fab.isClickable = false
            fab.visibility = View.INVISIBLE
        }.start()
        fabHideHandler.removeCallbacks(fabShowRunnable)
        fabHideHandler.postDelayed(fabShowRunnable, 30000)
        Toast.makeText(this, "已隐藏 30 秒", Toast.LENGTH_SHORT).show()
    }

    private fun showFab() {
        val fab = fab ?: return
        fab.visibility = View.VISIBLE
        fab.isClickable = true
        fab.animate().alpha(1f).setDuration(200).start()
    }

    /** 需求1：彻底关闭悬浮按钮，仅完全重启 App(进程死亡)后恢复 */
    private fun killFab() {
        val fab = this.fab ?: return
        fabKilled = true
        (fab.parent as? ViewGroup)?.removeView(fab)
        fabHideHandler.removeCallbacks(fabShowRunnable)
        Toast.makeText(this, "悬浮按钮已关闭，完全重启应用后恢复", Toast.LENGTH_LONG).show()
    }

    private fun showActionMenu() {
        val items = arrayOf("重新扫码", "手动填写 URL", "刷新当前页", "查看当前 URL", "清除缓存", "关闭悬浮按钮")
        AlertDialog.Builder(this)
            .setTitle("操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startScan()
                    1 -> openConfig()
                    2 -> webView.reload()
                    3 -> AlertDialog.Builder(this).setTitle("当前 URL")
                        .setMessage(if (currentUrl.isEmpty()) "(未配置)" else currentUrl).show()
                    4 -> { webView.clearCache(true); webView.clearHistory() }
                    5 -> killFab()
                }
            }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        fabHideHandler.removeCallbacks(fabShowRunnable)
        loginWebView?.stopLoading()
        loginWebView?.destroy()
        loginWebView = null
        loginOverlay = null
    }
}
