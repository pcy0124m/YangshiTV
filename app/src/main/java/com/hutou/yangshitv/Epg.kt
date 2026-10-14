package com.hutou.yangshitv

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * 节目单（EPG）。
 *
 * 数据来自央视频自己的接口：
 *
 *     GET https://capi.yangshipin.cn/api/yspepg/program/{pid}/{yyyymmdd}
 *
 * ⚠️ 它返回的是 **protobuf 二进制，不是 JSON**。格式已逆出来（别再当成 JSON 解）：
 *
 *     顶层 = 重复的 field 2，每一条节目是一个长度前缀子消息：
 *       1  string  节目 id
 *       2  string  节目名            ← 要的就是这个
 *       3  varint  开始时间（Unix 秒）
 *       4  varint  结束时间（Unix 秒）
 *       5  string  开始 HH:MM
 *       6  string  结束 HH:MM
 *       7  varint  时长（秒）
 *
 * 「现在正在播」= field3 <= now < field4。
 * 实测一天约 38 条、单次响应约 3 KB，接口不要签名，但要带桌面浏览器 UA。
 */
object Epg {

    private const val BASE = "https://capi.yangshipin.cn/api/yspepg/program/"

    /* 不带这个 UA 会被挡 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class Prog(val name: String, val start: Long, val end: Long)

    /* pid/日期 → 节目表。同一天不重复请求，电视上少一次网络就少一次失败机会 */
    private val cache = ConcurrentHashMap<String, List<Prog>>()

    /** 服务器认的是北京时间 */
    fun today(): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
        f.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return f.format(Date())
    }

    /** 当前正在播的节目名；拿不到返回 null（调用方保持原样即可） */
    fun currentName(pid: String, date: String): String? {
        val list = list(pid, date) ?: return null
        val now = System.currentTimeMillis() / 1000L
        for (p in list) {
            if (now >= p.start && now < p.end) return p.name
        }
        return null
    }

    private fun list(pid: String, date: String): List<Prog>? {
        val key = "$pid/$date"
        cache[key]?.let { return it }

        val raw = httpGet(BASE + pid + "/" + date) ?: return null
        val parsed = parse(raw)
        if (parsed.isEmpty()) return null

        cache[key] = parsed
        return parsed
    }

    private fun httpGet(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://www.yangshipin.cn/")
                setRequestProperty("Accept", "*/*")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes() }
        } catch (e: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Throwable) {}
        }
    }

    /* ------------------------------------------------------------------ */
    /*  极简 protobuf 读取器                                               */
    /*  只覆盖这个接口用得到的 wire type：varint / length-delimited /      */
    /*  32 位 / 64 位。够用，且不引第三方依赖。                            */
    /* ------------------------------------------------------------------ */

    private class Pb(private val b: ByteArray) {
        var i = 0

        fun eof() = i >= b.size

        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (i < b.size) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
                if (shift > 63) break
            }
            return r
        }

        fun lenBytes(): ByteArray {
            val n = varint().toInt()
            if (n <= 0 || i >= b.size) return ByteArray(0)
            val end = minOf(i + n, b.size)
            val out = b.copyOfRange(i, end)
            i = end
            return out
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                2 -> lenBytes()
                5 -> i += 4
                1 -> i += 8
            }
        }
    }

    /** 顶层：挑出所有 field 2 子消息 */
    fun parse(data: ByteArray): List<Prog> {
        val out = ArrayList<Prog>()
        val r = Pb(data)
        while (!r.eof()) {
            val tag = r.varint()
            val fn = (tag ushr 3).toInt()
            val wt = (tag and 7L).toInt()
            if (wt == 0 || wt == 1 || wt == 2 || wt == 5) {
                if (wt == 2 && fn == 2) {
                    parseItem(r.lenBytes())?.let { out.add(it) }
                } else {
                    r.skip(wt)
                }
            } else {
                break
            }
        }
        return out
    }

    private fun parseItem(b: ByteArray): Prog? {
        var name: String? = null
        var start = 0L
        var end = 0L

        val r = Pb(b)
        while (!r.eof()) {
            val tag = r.varint()
            val fn = (tag ushr 3).toInt()
            val wt = (tag and 7L).toInt()
            if (wt != 0 && wt != 1 && wt != 2 && wt != 5) break

            if (wt == 2) {
                val s = String(r.lenBytes(), Charsets.UTF_8)
                if (fn == 2) name = s
            } else if (wt == 0) {
                val v = r.varint()
                if (fn == 3) start = v else if (fn == 4) end = v
            } else {
                r.skip(wt)
            }
        }

        val n = name
        if (n.isNullOrEmpty() || start <= 0 || end <= 0) return null
        return Prog(n, start, end)
    }
}
