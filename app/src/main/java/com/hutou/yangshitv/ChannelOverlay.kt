package com.hutou.yangshitv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 频道列表浮层：遥控器按 OK 从左侧滑出。
 *
 * 刻意不走 androidx 的焦点系统 —— 电视上焦点抢来抢去最容易出玄学问题。
 * 这里自己维护一个 selectedIndex，上下键直接改它，所见即所得。
 */
class ChannelOverlay(context: Context) : FrameLayout(context) {

    /** 以 1920x1080 为基准的缩放系数：电视盒子密度五花八门，按屏宽等比放大 */
    private val s: Float
        get() = (resources.displayMetrics.widthPixels / 1920f).coerceIn(0.55f, 2.2f)

    private fun px(base: Int): Int = (base * s).toInt()

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val listBox = LinearLayout(context)
    private val scroller = ScrollView(context)

    private val rows = ArrayList<LinearLayout>()
    private val noViews = ArrayList<TextView>()
    private val nameViews = ArrayList<TextView>()
    private val progViews = ArrayList<TextView>()

    private var channels: List<Channel> = emptyList()
    private var programs = HashMap<Int, String>()

    val panelWidth: Int get() = px(PANEL_W)

    var selectedIndex: Int = 0
        private set

    var isOpen: Boolean = false
        private set

    private var onPick: ((Int) -> Unit)? = null

    companion object {
        private const val PANEL_W = 560
        private const val COLOR_BG = 0xF21A1A1E.toInt()
        private const val COLOR_ACCENT = 0xFFE8342B.toInt()
        private const val COLOR_TEXT = 0xFFEDEDED.toInt()
        private const val COLOR_DIM = 0xFF9A9A9A.toInt()
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        visibility = GONE

        /* 半透明蒙层 */
        scrim.setBackgroundColor(0x99000000.toInt())
        addView(
            scrim,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        /* 左侧面板 */
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(COLOR_BG)
            cornerRadius = px(14).toFloat()
        }
        val lpPanel = LayoutParams(panelWidth, LayoutParams.MATCH_PARENT)
        lpPanel.gravity = Gravity.START
        panel.layoutParams = lpPanel
        addView(panel)

        /* 标题 */
        val title = TextView(context)
        title.text = "频道列表"
        title.setTextColor(COLOR_TEXT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32).toFloat())
        title.setPadding(px(28), px(26), px(28), px(18))
        panel.addView(title)

        /* 分隔线 */
        val divider = View(context)
        divider.setBackgroundColor(0x33FFFFFF)
        panel.addView(divider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(1)))

        /* 列表 */
        listBox.orientation = LinearLayout.VERTICAL
        scroller.addView(
            listBox,
            ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        scroller.isVerticalScrollBarEnabled = false
        panel.addView(
            scroller,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /* ------------------------------------------------------------------ */

    fun bind(channels: List<Channel>, onPick: (Int) -> Unit) {
        if (this.channels.size == channels.size && rows.isNotEmpty()) {
            this.onPick = onPick
            return
        }
        this.channels = channels
        this.onPick = onPick

        panel.post {
            listBox.removeAllViews()
            rows.clear(); noViews.clear(); nameViews.clear(); progViews.clear()

            for ((i, ch) in channels.withIndex()) {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(px(28), px(16), px(22), px(16))

                val noTv = TextView(context)
                noTv.text = String.format("%02d", ch.no)
                noTv.setTextColor(COLOR_DIM)
                noTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30).toFloat())
                noTv.width = px(82)
                row.addView(noTv)

                val col = LinearLayout(context)
                col.orientation = LinearLayout.VERTICAL

                val nameTv = TextView(context)
                nameTv.text = ch.name
                nameTv.setTextColor(COLOR_TEXT)
                nameTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32).toFloat())
                nameTv.maxLines = 1
                col.addView(nameTv)

                val progTv = TextView(context)
                progTv.text = "—"
                progTv.setTextColor(COLOR_DIM)
                progTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22).toFloat())
                progTv.maxLines = 1
                progTv.ellipsize = TextUtils.TruncateAt.END
                progTv.setPadding(0, px(6), 0, 0)
                col.addView(progTv)

                row.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

                val idx = i
                row.setOnClickListener { pick(idx) }

                listBox.addView(
                    row,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                )

                rows.add(row); noViews.add(noTv); nameViews.add(nameTv); progViews.add(progTv)
            }
            refreshHighlight()

            /*
             * 行是 panel.post 里才真正建出来的，而节目单是从网络来的 —— 谁先到不一定。
             * 要是节目名比行先到，setProgram 那会儿 progViews 还是空的，名字就丢了。
             * 这里补写一遍。
             */
            for ((idx, p) in programs) {
                if (idx in progViews.indices && !p.isNullOrEmpty()) {
                    progViews[idx].text = p
                }
            }
        }
    }

    /** 页面回传 / 网络查到的「正在播什么」写进对应行 */
    fun setProgram(index: Int, program: String) {
        programs[index] = program
        if (index in progViews.indices) {
            progViews[index].text = if (program.isEmpty()) "—" else program
        }
    }

    fun markSelected(index: Int) {
        if (index in channels.indices) {
            selectedIndex = index
            refreshHighlight()
        }
    }

    /* ------------------------------------------------------------------ */

    fun open(from: Int) {
        if (isOpen) return
        isOpen = true
        selectedIndex = from.coerceIn(0, (channels.size - 1).coerceAtLeast(0))

        visibility = VISIBLE
        panel.animate().cancel()
        scrim.animate().cancel()

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(150).start()

        panel.translationX = -panelWidth.toFloat()
        panel.animate().translationX(0f).setDuration(190).start()

        refreshHighlight()
        post { scrollToSelected(true) }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        panel.animate().cancel()
        scrim.animate().cancel()
        panel.animate().translationX(-panelWidth.toFloat()).setDuration(150)
            .withEndAction { visibility = GONE }.start()
        scrim.animate().alpha(0f).setDuration(150).start()
    }

    /** 上下移动选择。返回是否真的动了 */
    fun move(delta: Int): Boolean {
        if (channels.isEmpty()) return false
        val next = (selectedIndex + delta).coerceIn(0, channels.size - 1)
        if (next == selectedIndex) return false
        selectedIndex = next
        refreshHighlight()
        scrollToSelected(false)
        return true
    }

    private fun pick(index: Int) {
        markSelected(index)
        close()
        onPick?.invoke(index)
    }

    private fun refreshHighlight() {
        for (i in rows.indices) {
            val on = i == selectedIndex
            val bg = if (on) {
                GradientDrawable().apply {
                    setColor(COLOR_ACCENT)
                    cornerRadius = px(10).toFloat()
                }
            } else null
            rows[i].background = bg
            noViews[i].setTextColor(if (on) 0xFFFFFFFF.toInt() else COLOR_DIM)
            nameViews[i].setTextColor(if (on) 0xFFFFFFFF.toInt() else COLOR_TEXT)
            progViews[i].setTextColor(if (on) 0xE6FFFFFF.toInt() else COLOR_DIM)
        }
    }

    private fun scrollToSelected(immediate: Boolean) {
        val i = selectedIndex
        if (i !in rows.indices) return
        val row = rows[i]
        val viewH = scroller.height
        if (viewH <= 0) return
        val target = (row.top - viewH / 2 + row.height / 2).coerceAtLeast(0)
        if (immediate) scroller.scrollTo(0, target) else scroller.smoothScrollTo(0, target)
    }
}
