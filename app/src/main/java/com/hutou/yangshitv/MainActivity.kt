package com.hutou.yangshitv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 电视直播壳。
 *
 * 架构很朴素，但每一条都对着一个具体的坑：
 *   - 系统 WebView 全屏加载央视频网页，无地址栏无控件
 *   - UA 伪装成桌面 Chrome（否则央视频弹「请下载 App」），版本号读系统真实内核
 *   - 遥控器 OK 呼出频道列表，▲▼ 直接换台
 *   - 换台优先走前端路由（不刷新整页），失败才整页加载，全程用黑遮罩盖住
 *   - 20 秒出不来画面 → 自动切备用源
 *   - 启动时检查内核版本，太旧直接告知，不让用户对着黑屏发呆
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var overlay: ChannelOverlay
    private lateinit var curtain: FrameLayout
    private lateinit var curtainBig: TextView
    private lateinit var curtainSub: TextView
    private lateinit var curtainHint: TextView

    private var channels: List<Channel> = emptyList()
    private var currentIndex = 0

    private val ui = Handler(Looper.getMainLooper())

    /** 正在等画面出来（黑遮罩挂着） */
    private var awaitingReady = false

    /** 冷却结束、真的可以接受 ready 信号了 */
    private var readyArmed = false

    /** 换台序号：用来丢弃过期回调 */
    private var switchSeq = 0

    private var backupTask: Runnable? = null
    private var epgTask: Runnable? = null

    /** 当前节目单是哪一天拉的，用来识别跨天 */
    private var epgDate = ""

    private var engineTooOld = false
    private var failState = false
    private var lastBackAt = 0L

    private var scriptCache: String? = null

    private val MP = FrameLayout.LayoutParams.MATCH_PARENT
    private val WC = FrameLayout.LayoutParams.WRAP_CONTENT

    /** 以 1920x1080 为基准的 UI 缩放：电视盒子密度千奇百怪，按屏宽等比走 */
    private val uiScale: Float
        get() = (resources.displayMetrics.widthPixels / 1920f).coerceIn(0.55f, 2.2f)

    private fun px(base: Int): Int = (base * uiScale).toInt()

    /* ================================================================== */
    /*  生命周期                                                          */
    /* ================================================================== */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channels = ChannelStore.channels(this)
        currentIndex = 0

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(MP, MP))

        curtain = buildCurtain()
        root.addView(curtain, FrameLayout.LayoutParams(MP, MP))

        overlay = ChannelOverlay(this)
        overlay.bind(channels) { idx -> switchTo(idx) }
        root.addView(overlay, FrameLayout.LayoutParams(MP, MP))

        setContentView(root)

        enterFullscreen()

        checkEngineThenStart()
        loadAllPrograms()
        scheduleProgramRefresh()
    }

    /**
     * 后台把 48 个台的「正在播什么」拉回来，填进频道列表。
     *
     * 为什么要单独走接口：网页一次只加载一个频道，从页面里根本拿不到别的台的节目。
     * 数据源是央视频自己的 EPG 接口（细节见 Epg.kt），不带签名、一次 3 KB。
     *
     * 4 个并发、单个失败就跳过，全程不阻塞界面 —— 电视上首屏出画面比什么都重要。
     */
    private fun loadAllPrograms() {
        if (channels.isEmpty()) return
        val date = Epg.today()
        epgDate = date
        val list = channels

        val pool = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "epg").apply { isDaemon = true }
        }
        for ((i, ch) in list.withIndex()) {
            val idx = i
            val pid = ch.pid
            pool.execute {
                val name = Epg.currentName(pid, date) ?: return@execute
                ui.post { overlay.setEpgProgram(idx, name) }
            }
        }
        pool.shutdown()
    }

    /**
     * 定时刷新节目名。
     *
     * 节目是会换场的（19:00 的《新闻联播》过点就该变成下一档），
     * 只在启动时拉一次的话，名字会一直停在打开 App 那一刻。
     *
     * 但也不能每分钟打 48 个请求 —— 节目单一天只拉一次，之后全部在本地按时间重算，
     * 所以这个定时任务绝大多数时候是零网络开销的纯本地计算。
     * 只有跨天（缓存 key 变了）才重新走一次网络。
     */
    private fun scheduleProgramRefresh() {
        epgTask?.let { ui.removeCallbacks(it) }
        epgTask = Runnable {
            val d = Epg.today()
            if (d != epgDate) {
                loadAllPrograms()          // 跨天了，重新拉
            } else {
                for ((i, ch) in channels.withIndex()) {
                    val n = Epg.cachedName(ch.pid, d) ?: continue
                    overlay.setEpgProgram(i, n)
                }
            }
            scheduleProgramRefresh()
        }.also { ui.postDelayed(it, 60_000) }
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
    }

    override fun onDestroy() {
        cancelBackup()
        epgTask?.let { ui.removeCallbacks(it) }
        epgTask = null
        try {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        } catch (e: Throwable) {
            /* 退出时 WebView 内部偶发异常，忽略 */
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /* ================================================================== */
    /*  WebView                                                           */
    /* ================================================================== */

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.keepScreenOn = true

        val st = w.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.databaseEnabled = true
        st.setSupportZoom(false)
        st.builtInZoomControls = false
        st.displayZoomControls = false
        st.loadWithOverviewMode = true
        st.useWideViewPort = true
        st.javaScriptCanOpenWindowsAutomatically = false
        st.allowFileAccess = true
        st.allowContentAccess = true
        st.cacheMode = WebSettings.LOAD_DEFAULT

        /* 关键：伪装桌面 Chrome，版本号用系统真实内核，绝不写死 */
        st.userAgentString = WebEngine.desktopUserAgent(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            st.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            st.mediaPlaybackRequiresUserGesture = false
        }

        w.addJavascriptInterface(NativeBridge(), "HTVNative")

        w.webViewClient = object : WebViewClient() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                blockUrl(url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = blockUrl(request.url?.toString() ?: "")

            override fun onPageFinished(view: WebView, url: String) {
                injectPageScript()
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                /* 不等 onPageFinished，进度过半就先把脚本塞进去，抢时间 */
                if (newProgress >= 70) injectPageScript()
            }
        }

        w.requestFocus()
        return w
    }

    /**
     * 拦掉会跳出体验的导航：APK 直链、应用市场、各种 intent:// 广播。
     * 普通 http(s) 导航一律放行（备用源是各卫视官网，域名不固定）。
     */
    private fun blockUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (lower.contains(".apk")) return true
            return false
        }
        /* intent:// market:// mqqapi:// taobao:// ... 全部拦掉 */
        return true
    }

    private fun pageScript(): String {
        scriptCache?.let { return it }
        val s = try {
            assets.open("inject.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Throwable) {
            ""
        }
        scriptCache = s
        return s
    }

    private fun injectPageScript() {
        val js = pageScript()
        if (js.isEmpty()) return
        evalJs(js)
    }

    private fun evalJs(js: String, cb: ValueCallback<String>? = null) {
        try {
            web.evaluateJavascript(js, cb)
        } catch (e: Throwable) {
            /* WebView 可能正在销毁 */
        }
    }

    /** JS 字符串字面量转义 */
    private fun jsString(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    /* ================================================================== */
    /*  JS 桥                                                             */
    /* ================================================================== */

    private inner class NativeBridge {
        @JavascriptInterface
        fun onEvent(type: String, payload: String) {
            val t = type ?: ""
            val p = payload ?: ""
            ui.post { handlePageEvent(t, p) }
        }
    }

    private fun handlePageEvent(type: String, payload: String) {
        when (type) {
            "ready" -> {
                if (awaitingReady && readyArmed) {
                    awaitingReady = false
                    failState = false
                    cancelBackup()
                    hideCurtain()
                }
            }
            "program" -> {
                /*
                 * 网页回传的节目名只做补充，且空值一律丢弃。
                 *
                 * 注入脚本每 4 秒从 DOM 里启发式猜一次节目名，猜不到就发空串过来。
                 * 页面被剪枝成只剩播放器之后，它基本永远猜不到 —— 也就是说
                 * 每换一个台，4 秒内就会有一个空串到达。旧版直接写进界面，
                 * 于是每换一个台就抹掉一个已经查好的节目名（点一个消一个）。
                 */
                if (payload.isNotEmpty()) {
                    overlay.setPageProgram(currentIndex, payload)
                }
            }
        }
    }

    /* ================================================================== */
    /*  黑遮罩                                                            */
    /* ================================================================== */

    private fun buildCurtain(): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(Color.BLACK)
        f.isClickable = false
        f.visibility = View.GONE

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER

        curtainBig = TextView(this)
        curtainBig.setTextColor(Color.WHITE)
        curtainBig.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(150).toFloat())
        curtainBig.gravity = Gravity.CENTER
        curtainBig.setTypeface(null, android.graphics.Typeface.BOLD)
        col.addView(curtainBig)

        curtainSub = TextView(this)
        curtainSub.setTextColor(0xFFEDEDED.toInt())
        curtainSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(52).toFloat())
        curtainSub.gravity = Gravity.CENTER
        col.addView(curtainSub)

        curtainHint = TextView(this)
        curtainHint.setTextColor(0xFF8A8A8A.toInt())
        /* 32 不是随便定的：这行还要显示诊断信息，太小了用户截图看不清 */
        curtainHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32).toFloat())
        curtainHint.gravity = Gravity.CENTER
        curtainHint.setLineSpacing(px(10).toFloat(), 1f)
        val lp = LinearLayout.LayoutParams(WC, WC)
        lp.topMargin = px(40)
        col.addView(curtainHint, lp)

        val lpCol = FrameLayout.LayoutParams(WC, WC)
        lpCol.gravity = Gravity.CENTER
        f.addView(col, lpCol)
        return f
    }

    private fun showCurtain(big: String, sub: String, hint: String, bigColor: Int = Color.WHITE) {
        curtain.animate().cancel()
        curtain.alpha = 1f
        curtain.visibility = View.VISIBLE
        curtainBig.text = big
        curtainBig.setTextColor(bigColor)
        curtainSub.text = sub
        curtainHint.text = hint
    }

    private fun showCurtainFor(ch: Channel) {
        showCurtain(
            String.format("%02d", ch.no),
            ch.name,
            "正在切换…"
        )
    }

    private fun hideCurtain() {
        if (curtain.visibility != View.VISIBLE) return
        curtain.animate().cancel()
        curtain.animate().alpha(0f).setDuration(190).withEndAction {
            curtain.visibility = View.GONE
            curtain.alpha = 1f
        }.start()
    }

    /* ================================================================== */
    /*  启动 / 内核检查                                                    */
    /* ================================================================== */

    private fun checkEngineThenStart() {
        val major = WebEngine.chromeMajor(this)

        if (major in 1 until WebEngine.MIN_CHROME_MAJOR) {
            engineTooOld = true
            showCurtain(
                "!",
                "系统网页内核版本过旧",
                "当前内核 Chrome $major，央视频至少需要 Chrome ${WebEngine.MIN_CHROME_MAJOR}。\n" +
                        "建议在盒子应用商店里更新「Android System WebView」，\n" +
                        "或换一台系统较新的电视盒子。\n\n" +
                        "按【OK】仍要尝试   ·   按【返回】退出",
                0xFFFFC107.toInt()
            )
            return
        }

        startFirstChannel()
    }

    private fun startFirstChannel() {
        engineTooOld = false
        currentIndex = 0
        val ch = channels[currentIndex]

        showCurtain(String.format("%02d", ch.no), ch.name, "正在启动…")

        switchSeq++
        val seq = switchSeq
        awaitingReady = true
        readyArmed = false
        failState = false

        /*
         * 这行冷却不能少。
         *
         * readyArmed 是「现在允许接受 ready 信号」的闸门：换台前先关掉，
         * 600ms 后再开，免得换台途中残留的 ready 把遮罩提前撤了。
         *
         * v1.0.2 之前这里漏了下面这一行 —— 首次启动时 readyArmed 永远是 false，
         * 页面发来的 ready 全被丢弃。视频其实已经在播（真机诊断显示
         * 「video 1个 就绪4/播放 1920x1080」），遮罩却一直挂着，
         * 20 秒后还判超时去切备用源。这就是「地址修对了仍然黑屏」的真凶。
         */
        ui.postDelayed({ if (seq == switchSeq) readyArmed = true }, 600)

        web.loadUrl(ch.homeUrl)
        scheduleBackup(ch)
    }

    /* ================================================================== */
    /*  换台引擎                                                          */
    /* ================================================================== */

    private fun switchTo(index: Int) {
        if (channels.isEmpty()) return

        val n = channels.size
        val i = ((index % n) + n) % n
        currentIndex = i
        val ch = channels[i]

        overlay.markSelected(i)
        showCurtainFor(ch)

        cancelBackup()

        switchSeq++
        val seq = switchSeq
        awaitingReady = true
        readyArmed = false
        failState = false

        /* 冷却 600ms：丢掉换台前残留的 ready 信号 */
        ui.postDelayed({ if (seq == switchSeq) readyArmed = true }, 600)

        /*
         * 换台就是整页加载，没有更快的路。
         *
         * 实测（桌面 Chrome 探针）排除掉了两条「不刷新」的尝试：
         *   ① 改 location.hash      —— 这站是 history 路由，hash 变了播放器纹丝不动
         *   ② pushState + popstate  —— URL 变了，但 video 的 blob src 没变、
         *                              currentTime 照常往前走，还是同一个流
         * 播放器地址是带 token 二次请求拿的，只有整页重走一遍才会换台。
         * 好在有黑遮罩盖着，用户看不到刷新过程。
         */
        web.loadUrl(ch.homeUrl)

        /* 20 秒总超时 → 备用源 */
        scheduleBackup(ch)
    }

    private fun scheduleBackup(ch: Channel) {
        cancelBackup()
        val seq = switchSeq

        backupTask = Runnable {
            if (seq != switchSeq || !awaitingReady) return@Runnable

            /*
             * 双保险：超时了 ≠ 真的没画面。
             *
             * 真机上遇到过 ready 信号丢在路上、而视频其实已经在播的情况，
             * 这时候还去切备用源，等于把好好的直播打断。
             * 所以先问一句「现在到底在不在播」，在播就撤遮罩收工。
             */
            askPlaying { playing ->
                if (seq != switchSeq || !awaitingReady) return@askPlaying
                if (playing) {
                    awaitingReady = false
                    failState = false
                    hideCurtain()
                } else {
                    doBackup(ch, seq)
                }
            }
        }.also { ui.postDelayed(it, 20_000) }
    }

    /** 页面上现在是不是真的有画面在动 */
    private fun askPlaying(onResult: (Boolean) -> Unit) {
        evalJs(
            "(function(){try{return window.__HTV__?window.__HTV__.isPlaying():false}" +
                    "catch(e){return false}})()"
        ) { r ->
            val s = r?.trim() ?: ""
            onResult(s == "true" || s == "\"true\"")
        }
    }

    private fun doBackup(ch: Channel, seq: Int) {
        if (ch.backup.isEmpty()) {
            failState = true
            captureDiagnose("加载超时。\n按【OK】重试   ·   按【返回】换台")
            return
        }

        web.loadUrl(ch.backup)
        captureDiagnose("主源超时，正在切换备用源…")

        /* 备用源再给 20 秒 */
        backupTask = Runnable {
            if (seq == switchSeq && awaitingReady && !failState) {
                failState = true
                captureDiagnose("备用源也没能出画面。\n按【OK】重试   ·   按【返回】换台")
            }
        }.also { ui.postDelayed(it, 20_000) }
    }

    /**
     * 抓一次页面诊断，显示在黑遮罩上。
     *
     * 为什么要有这个：v1.0.0 那次黑屏排查全靠猜 —— 电视上既没有 logcat，
     * 也连不上调试器，用户能给的只有「一张黑屏截图」。
     * 这里把页面的 URL、video 个数与状态、注入脚本在不在、有没有 JS 报错
     * 直接打到屏幕上，截图一发就知道问题出在哪一环。
     */
    private fun captureDiagnose(base: String) {
        curtainHint.text = base
        evalJs(
            "(function(){try{return window.__HTV__?window.__HTV__.diagnose():" +
                    "'脚本未注入 | video='+document.querySelectorAll('video').length+" +
                    "' | '+document.title}catch(e){return '诊断失败:'+e.message}})()"
        ) { r ->
            val d = unquoteJs(r)
            if (d.isNotEmpty()) curtainHint.text = base + "\n\n" + d
        }
    }

    /** evaluateJavascript 回调给的是 JSON 编码过的字符串，去掉外层引号与转义 */
    private fun unquoteJs(s: String?): String {
        if (s == null) return ""
        var t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1)
        }
        return t.replace("\\u003C", "<")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun cancelBackup() {
        backupTask?.let { ui.removeCallbacks(it) }
        backupTask = null
    }

    /* ================================================================== */
    /*  遥控器                                                            */
    /* ================================================================== */

    private fun isOurKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE -> true
        else -> false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isOurKey(code)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                handleOurKey(code)
            }
            /* 按下和抬起都吃掉，避免页面收到半截按键 */
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleOurKey(code: Int) {
        /* —— 内核过旧警告态 —— */
        if (engineTooOld) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> finish()
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    engineTooOld = false
                    startFirstChannel()
                }
            }
            return
        }

        /* —— 频道列表打开态 —— */
        if (overlay.isOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> overlay.move(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> overlay.move(1)
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val i = overlay.selectedIndex
                    overlay.close()
                    switchTo(i)
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> overlay.close()
            }
            return
        }

        /* —— 正常播放态 —— */
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> switchTo(currentIndex - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> switchTo(currentIndex + 1)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (failState) {
                    switchTo(currentIndex)      // 失败态：OK 当重试
                } else {
                    overlay.open(currentIndex)  // OK 从左边滑出频道列表
                }
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2200) {
                    finish()
                } else {
                    lastBackAt = now
                    toast("再按一次【返回】退出")
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        handleOurKey(KeyEvent.KEYCODE_BACK)
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
        }
    }
}
