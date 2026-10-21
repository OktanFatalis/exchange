package com.fastcurrency.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class ChartSheet(
    private val ctx: Context,
    host: FrameLayout,
    private var a: String,
    private var b: String,
    private var current: Double,
    onClosed: () -> Unit
) {
    @JvmField val sheet = Sheet(host, 36, onClosed)
    private val pal = Ui.pal
    private var range = Store.int("range", 3).coerceIn(0, History.LABELS.size - 1)
    private var data: Series? = null
    private var gen = 0
    private var shown = Double.NaN
    private var counter: ValueAnimator? = null

    private val title = text(ctx, 21f, pal.text, Ui.MEDIUM)
    private val subtitle = text(ctx, 12f, pal.text2).oneLine()
    private val unitLeft = text(ctx, 13f, pal.text2)
    private val price = text(ctx, 34f, pal.text, Ui.MEDIUM)
    private val unitRight = text(ctx, 16f, pal.text2, Ui.MEDIUM)
    private val change = text(ctx, 14f, pal.text2, Ui.MEDIUM).oneLine()
    private val chart = ChartView(ctx)
    private val stats = Array(3) { text(ctx, 14f, pal.text, Ui.MEDIUM) }
    private val note = text(ctx, 11f, pal.text2)
    private val swap = text(ctx, 18f, pal.accent)

    init {
        val p = sheet.panel
        val side = dp(20)

        val head = LinearLayout(ctx)
        head.gravity = Gravity.CENTER_VERTICAL
        val names = LinearLayout(ctx)
        names.orientation = LinearLayout.VERTICAL
        names.addView(title)
        names.addView(subtitle, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        head.addView(names, lp(0, WRAP, 1f))
        swap.text = "⇄"
        swap.gravity = Gravity.CENTER
        swap.contentDescription = "Поменять местами"
        swap.background = ripple(round(pal.accentSoft, 20), 20)
        swap.setOnClickListener { swap() }
        head.addView(swap, lp(dp(40), dp(40)))
        val close = text(ctx, 17f, pal.text2)
        close.text = "✕"
        close.gravity = Gravity.CENTER
        close.contentDescription = "Закрыть"
        close.background = ripple(null, 20)
        close.setOnClickListener { sheet.dismiss() }
        head.addView(close, lp(dp(40), dp(40)).apply { leftMargin = dp(6) })
        p.addView(head, lp(MATCH, WRAP).apply { setMargins(side, dp(2), dp(12), 0) })
        sheet.draggable(head)

        p.addView(unitLeft, lp(WRAP, WRAP).apply { setMargins(side, dp(18), side, 0) })
        val row = LinearLayout(ctx)
        row.isBaselineAligned = true
        price.fontFeatureSettings = "tnum"
        row.addView(price)
        row.addView(unitRight, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        p.addView(row, lp(MATCH, WRAP).apply { setMargins(side, dp(4), side, 0) })
        change.fontFeatureSettings = "tnum"
        p.addView(change, lp(MATCH, WRAP).apply { setMargins(side, dp(8), side, 0) })

        chart.onScrub = { show(it) }
        chart.onRetry = { load() }
        p.addView(chart, lp(MATCH, 0, 1f).apply { setMargins(dp(12), dp(14), dp(8), 0) })

        val st = LinearLayout(ctx)
        val names3 = arrayOf("Минимум", "Максимум", "Среднее")
        for (i in 0..2) {
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = if (i == 0) Gravity.START else if (i == 1) Gravity.CENTER_HORIZONTAL else Gravity.END
            col.addView(text(ctx, 11f, pal.text2).also { it.text = names3[i] })
            stats[i].fontFeatureSettings = "tnum"
            col.addView(stats[i], lp(WRAP, WRAP).apply { topMargin = dp(4) })
            st.addView(col, lp(0, WRAP, 1f))
        }
        p.addView(st, lp(MATCH, WRAP).apply { setMargins(side, dp(14), side, 0) })

        val bar = RangeBar(ctx, range) { pickRange(it) }
        p.addView(bar, lp(MATCH, dp(44)).apply { setMargins(dp(12), dp(14), dp(12), 0) })

        note.gravity = Gravity.CENTER
        p.addView(note, lp(MATCH, WRAP).apply { setMargins(side, dp(10), side, dp(14)) })

        bindTitles()
        setPrice(current, false)
        change.text = History.PERIODS[range]
        load()
    }

    private fun bindTitles() {
        val ca = Currencies.get(a)
        val cb = Currencies.get(b)
        title.text = "$a → $b"
        subtitle.text = ca.name + " → " + cb.name
        unitLeft.text = "1 $a ="
        unitRight.text = b
    }

    private fun pickRange(r: Int) {
        range = r
        Store["range"] = r.toString()
        load()
    }

    private fun load() {
        val g = ++gen
        chart.loading = true
        note.text = "Загрузка…"
        History.load(a, b, range) { s, err ->
            if (g != gen) return@load
            chart.loading = false
            if (s != null) {
                data = s
                chart.setData(s, range)
                show(-1)
                fillStats(s)
                note.text = describe(s)
            } else {
                data = null
                chart.setMessage(err ?: "Нет данных")
                change.text = History.PERIODS[range]
                change.setTextColor(pal.text2)
                for (t in stats) t.text = "—"
                note.text = "Источники: Yahoo Finance, Binance, ЕЦБ, ЦБ РФ"
            }
        }
    }

    private fun describe(s: Series): String {
        val sb = StringBuilder("Источник: ").append(s.src)
        val wanted = System.currentTimeMillis() - History.SPAN[range]
        if (range >= 5 && s.t[0] - wanted > History.SPAN[range] / 20) {
            sb.append(" · данные с ").append(Dates.full(s.t[0], false))
        }
        return sb.toString()
    }

    private fun fillStats(s: Series) {
        var mn = Double.MAX_VALUE
        var mx = -Double.MAX_VALUE
        var sum = 0.0
        for (x in s.v) {
            if (x < mn) mn = x
            if (x > mx) mx = x
            sum += x
        }
        stats[0].text = Money.rate(mn)
        stats[1].text = Money.rate(mx)
        stats[2].text = Money.rate(sum / s.size)
    }

    private fun show(i: Int) {
        val s = data ?: return
        val k = if (i < 0) s.size - 1 else i
        val value = s.v[k]
        val first = s.v[0]
        setPrice(value, i < 0)
        val diff = value - first
        val pct = if (first != 0.0) diff / first * 100 else 0.0
        val sb = StringBuilder(48)
        sb.append(if (diff > 0) "▲ " else if (diff < 0) "▼ " else "")
        sb.append(Money.signed(diff, Money.rate(diff)))
        sb.append("  (").append(Money.signed(pct, Money.fixed(pct, 2, 0))).append(" %)  ·  ")
        sb.append(if (i < 0) History.PERIODS[range] else Dates.full(s.t[k], range <= 3))
        change.text = sb
        change.setTextColor(if (diff > 0) pal.up else if (diff < 0) pal.down else pal.text2)
    }

    private fun setPrice(value: Double, animate: Boolean) {
        counter?.cancel()
        val from = shown
        shown = value
        if (!animate || from.isNaN() || from == value) {
            price.text = Money.rate(value)
            return
        }
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 450
        va.interpolator = DecelerateInterpolator()
        va.addUpdateListener { price.text = Money.rate(from + (value - from) * (it.animatedValue as Float)) }
        counter = va
        va.start()
    }

    private fun swap() {
        val t = a
        a = b
        b = t
        swap.animate().rotationBy(180f).setDuration(300).start()
        bindTitles()
        val s = data
        if (s != null) {
            val inv = s.inverted()
            data = inv
            chart.setData(inv, range)
            show(-1)
            fillStats(inv)
        } else {
            if (!current.isNaN()) current = 1.0 / current
            setPrice(current, false)
            load()
        }
    }
}

/** Переключатель периода: подложка плавно переезжает к выбранному пункту. */
class RangeBar(ctx: Context, private var sel: Int, private val onPick: (Int) -> Unit) : View(ctx) {
    private val labels = History.LABELS
    private val pal = Ui.pal
    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpf(13f)
        typeface = Ui.MEDIUM
        textAlign = Paint.Align.CENTER
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accentSoft }
    private val rect = RectF()
    private var pos = sel.toFloat()

    override fun onDraw(c: Canvas) {
        val cw = width.toFloat() / labels.size
        val inset = dpf(2f)
        rect.set(pos * cw + inset, inset, (pos + 1) * cw - inset, height - inset)
        c.drawRoundRect(rect, height / 2f, height / 2f, pill)
        val y = height / 2f + txt.textSize * 0.36f
        for (i in labels.indices) {
            val near = 1f - minOf(1f, abs(pos - i))
            txt.color = if (near > 0.5f) pal.accent else pal.text2
            c.drawText(labels[i], cw * (i + 0.5f), y, txt)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / width * labels.size).toInt().coerceIn(0, labels.size - 1)
            if (i != sel) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val from = pos
                sel = i
                val va = ValueAnimator.ofFloat(from, i.toFloat())
                va.duration = 280
                va.interpolator = DecelerateInterpolator(1.8f)
                va.addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                va.start()
                onPick(i)
            }
        }
        return true
    }
}
