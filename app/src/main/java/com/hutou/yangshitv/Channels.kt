package com.hutou.yangshitv

import android.content.Context
import android.webkit.WebSettings
import org.json.JSONObject

/**
 * 一个频道。
 *
 * @param no      台号，1 开始。频道列表里显示成 01、02 …
 * @param name    显示名，如「CCTV-1 综合」
 * @param pid     央视频直播页的 pid 参数，换台靠它
 * @param backup  主源卡死 20 秒后自动切过去的备用地址，可能为空
 */
data class Channel(
    val no: Int,
    val name: String,
    val pid: String,
    val backup: String
) {
    /**
     * 央视频直播页的完整地址。
     *
     * ⚠️ 必须是 **query 形态** `?pid=`，不能用 hash 形态 `#/tv/home?pid=`。
     * 实测（桌面 Chrome 探针）：这个站点是 history 路由，
     *   - `/tv/home?pid=x` → 3~6 秒后播放器起来，video 正常播放
     *   - `/#/tv/home?pid=x` → 路由匹配不上，落到首页 `pc-index`，**页面里一个 video 都没有**，
     *     注入脚本永远等不到 ready，最后表现为「黑屏 + 主源超时」
     */
    val homeUrl: String
        get() = "https://www.yangshipin.cn/tv/home?pid=$pid"
}

/** 频道表来自 assets/channels.json，要加台改那个文件就行，不用动代码 */
object ChannelStore {
    private var cache: List<Channel>? = null

    fun channels(ctx: Context): List<Channel> {
        cache?.let { return it }

        val list = ArrayList<Channel>()
        try {
            val text = ctx.assets.open("channels.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val arr = JSONObject(text).getJSONArray("channels")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pid = o.optString("pid", "")
                if (pid.isEmpty()) continue
                list.add(
                    Channel(
                        no = o.optInt("no", i + 1),
                        name = o.optString("name", "频道 ${i + 1}"),
                        pid = pid,
                        backup = o.optString("backup", "")
                    )
                )
            }
        } catch (e: Throwable) {
            // 解析失败不致命，下面会兜个底
        }

        if (list.isEmpty()) {
            list.add(Channel(1, "CCTV-1 综合", "600001859", "https://tv.cctv.com/live/cctv1/"))
        }

        cache = list
        return list
    }
}

/**
 * 系统网页内核（Android System WebView）相关。
 *
 * 特别重要的一点：伪装桌面 UA 时，Chrome 版本号必须用**设备真实内核版本**，
 * 写死一个高版本号反而会被央视频识别成异常客户端。这里直接从系统默认 UA
 * 里把真实的 Chrome 版本抠出来复用。
 */
object WebEngine {

    /** 低于这个主版本号就认为内核太旧，直接提示用户 */
    const val MIN_CHROME_MAJOR = 60

    private val CHROME_RE = Regex("Chrome/([\\d.]+)")

    /** 系统默认 UA 里真实的 Chrome 版本号，如 "74.0.3729.157"；取不到返回空串 */
    fun chromeVersion(ctx: Context): String {
        val ua = try {
            WebSettings.getDefaultUserAgent(ctx) ?: ""
        } catch (e: Throwable) {
            ""
        }
        return CHROME_RE.find(ua)?.groupValues?.get(1) ?: ""
    }

    /** 真实内核主版本号，取不到返回 0 */
    fun chromeMajor(ctx: Context): Int {
        val v = chromeVersion(ctx)
        if (v.isEmpty()) return 0
        return v.substringBefore('.').toIntOrNull() ?: 0
    }

    /**
     * 拼一个桌面端 Chrome 的 UA。
     * 目的是让央视频认定我们是电脑浏览器，不走「请下载 App」那套。
     * 版本号取设备真实内核版本 —— 不写死。
     */
    fun desktopUserAgent(ctx: Context): String {
        val real = chromeVersion(ctx)
        val ver = if (real.isNotEmpty()) real else "120.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$ver Safari/537.36"
    }
}
