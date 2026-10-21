package com.fastcurrency.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class ChartView(ctx: Context) : View(ctx) {

    /** Индекс точки под пальцем или -1, когда палец отпущен. */
    var onScrub: ((Int) -> Unit)? = null
    var onRetry: (() -> Unit)? = null

    private val pal = Ui.pal
    private var t = LongArray(0)
    private var v = DoubleArray(0)
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var range = 3
    private var lo = 0.0
    private var hi = 1.0
    private var step = 1.0
    private var minI = 0
    private var maxI = 0
    private var color = pal.up
    private var shaderColor = 0
    private var shaderTop = 0f

    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2.2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, dpf(0.7f))
        color = pal.stroke
        pathEffect = DashPathEffect(floatArrayOf(dpf(3f), dpf(4f)), 0f)
    }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        color = pal.text2
        alpha = 110
        pathEffect = DashPathEffect(floatArrayOf(dpf(1.5f), dpf(3.5f)), 0f)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpf(10.5f)
        color = pal.text2
        fontFeatureSettings = "tnum"
    }
    private val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpf(10.5f)
        typeface = Ui.MEDIUM
        fontFeatureSettings = "tnum"
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text }
    private val bubbleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpf(11.5f)
        typeface = Ui.MEDIUM
        color = pal.card
    }
    private val msg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpf(14f)
        color = pal.text2
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val area = Path()
    private val tmp = Path()
    private val rect = RectF()

    private var reveal = 1f
    private var morph = 1f
    private var fromY: FloatArray? = null
    private var toY: FloatArray? = null
    private var fromColor = 0
    private var anim: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var phase = 0f
    private var scrub = -1
    private var message: String? = null
    var loading = false
        set(value) {
            field = value
            if (value) message = null
            syncPulse()
            invalidate()
        }

    fun setData(s: Series, r: Int) {
        val old = if (ys.size > 1 && width > 0 && message == null) sample(xs, ys) else null
        val oldColor = color
        t = s.t
        v = s.v
        range = r
        message = null
        scrub = -1
        var mn = 0
        var mx = 0
        for (i in v.indices) {
            if (v[i] < v[mn]) mn = i
            if (v[i] > v[mx]) mx = i
        }
        minI = mn
        maxI = mx
        color = if (v[v.size - 1] >= v[0]) pal.up else pal.down
        layoutPoints()
        anim?.cancel()
        if (old != null && xs.size > 1) {
            fromY = old
            toY = sample(xs, ys)
            fromColor = oldColor
            reveal = 1f
            run(480, PathInterpolator(0.3f, 0f, 0.1f, 1f)) { morph = it }
        } else {
            morph = 1f
            run(900, DecelerateInterpolator(1.6f)) { reveal = it }
        }
        syncPulse()
    }

    fun setMessage(text: String) {
        message = text
        loading = false
        t = LongArray(0)
        v = DoubleArray(0)
        xs = FloatArray(0)
        ys = FloatArray(0)
        invalidate()
    }

    private fun run(ms: Long, interp: android.animation.TimeInterpolator, set: (Float) -> Unit) {
        set(0f)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ms
        a.interpolator = interp
        a.addUpdateListener {
            set(it.animatedValue as Float)
            invalidate()
        }
        anim = a
        a.start()
    }

    private fun syncPulse() {
        val want = isAttachedToWindow && (loading && v.isEmpty() || (range <= 1 && v.isNotEmpty()))
        if (want && pulse == null) {
            pulse = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!want && pulse != null) {
            pulse?.cancel()
            pulse = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layoutPoints()
    }

    private fun layoutPoints() {
        val n = v.size
        if (n < 2 || width == 0) return
        var mn = v[minI]
        var mx = v[maxI]
        if (mx - mn < abs(mx) * 1e-9) {
            val d = max(abs(mx) * 0.01, 1e-9)
            mn -= d
            mx += d
        }
        val pad = (mx - mn) * 0.14
        lo = mn - pad
        hi = mx + pad
        step = nice((hi - lo) / 4)

        var widest = 0f
        var g = ceil(lo / step) * step
        while (g <= hi) {
            widest = max(widest, label.measureText(Money.axis(g, step)))
            g += step
        }
        left = dpf(4f)
        right = width - widest - dpf(10f)
        top = dpf(30f)
        bottom = height - dpf(26f)

        xs = FloatArray(n)
        ys = FloatArray(n)
        val w = right - left
        val h = bottom - top
        for (i in 0 until n) {
            xs[i] = left + w * i / (n - 1)
            ys[i] = (top + (hi - v[i]) / (hi - lo) * h).toFloat()
        }
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
        shaderColor = 0
    }

    private fun nice(raw: Double): Double {
        if (raw <= 0) return 1.0
        val e = 10.0.pow(floor(log10(raw)))
        val f = raw / e
        return e * when {
            f < 1.5 -> 1.0
            f < 3.0 -> 2.0
            f < 7.0 -> 5.0
            else -> 10.0
        }
    }

    private fun sample(x: FloatArray, y: FloatArray): FloatArray {
        val out = FloatArray(SAMPLES)
        val n = y.size
        for (k in 0 until SAMPLES) {
            val pos = k.toFloat() / (SAMPLES - 1) * (n - 1)
            val i = min(pos.toInt(), n - 2)
            val f = pos - i
            out[k] = y[i] + (y[i + 1] - y[i]) * f
        }
        return out
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val ia = 1 - f
        return (0xFF shl 24) or
            (((a shr 16 and 0xFF) * ia + (b shr 16 and 0xFF) * f).toInt() shl 16) or
            (((a shr 8 and 0xFF) * ia + (b shr 8 and 0xFF) * f).toInt() shl 8) or
            ((a and 0xFF) * ia + (b and 0xFF) * f).toInt()
    }

    override fun onDraw(c: Canvas) {
        val n = v.size
        val m = message
        if (m != null) {
            c.drawText(m, width / 2f, height / 2f, msg)
            msg.textSize = dpf(12f)
            c.drawText("Нажмите, чтобы повторить", width / 2f, height / 2f + dpf(22f), msg)
            msg.textSize = dpf(14f)
            return
        }
        if (n < 2) {
            if (loading) drawSkeleton(c)
            return
        }
        if (xs.size != n) {
            layoutPoints()
            if (xs.size != n) return
        }
        val dim = loading
        if (dim) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 90)

        var g = ceil(lo / step) * step
        val h = bottom - top
        while (g <= hi) {
            val y = (top + (hi - g) / (hi - lo) * h).toFloat()
            c.drawLine(left, y, right, y, grid)
            c.drawText(Money.axis(g, step), right + dpf(8f), y + label.textSize * 0.35f, label)
            g += step
        }

        val col = if (morph < 1f) blend(fromColor, color, morph) else color
        line.color = col
        if (shaderColor != col || shaderTop != top) {
            shaderColor = col
            shaderTop = top
            fill.shader = LinearGradient(
                0f, top, 0f, bottom,
                (col and 0xFFFFFF) or 0x55000000, col and 0xFFFFFF, Shader.TileMode.CLAMP
            )
        }

        val y0 = ys[0]
        c.drawLine(left, y0, right, y0, base)

        val curve: Path
        val headX: Float
        val headY: Float
        val fy = fromY
        val ty = toY
        if (morph < 1f && fy != null && ty != null) {
            curve = tmp
            curve.reset()
            val w = right - left
            for (k in 0 until SAMPLES) {
                val x = left + w * k / (SAMPLES - 1)
                val y = fy[k] + (ty[k] - fy[k]) * morph
                if (k == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
            }
            headX = right
            headY = fy[SAMPLES - 1] + (ty[SAMPLES - 1] - fy[SAMPLES - 1]) * morph
        } else {
            curve = path
            val pos = reveal * (n - 1)
            val i = min(pos.toInt(), n - 2)
            val f = pos - i
            headX = xs[i] + (xs[i + 1] - xs[i]) * f
            headY = ys[i] + (ys[i + 1] - ys[i]) * f
        }
        area.set(curve)
        area.lineTo(right, bottom)
        area.lineTo(left, bottom)
        area.close()

        c.save()
        c.clipRect(0f, 0f, if (morph < 1f) width.toFloat() else headX + line.strokeWidth, height.toFloat())
        c.drawPath(area, fill)
        c.drawPath(curve, line)
        c.restore()

        val done = reveal >= 1f && morph >= 1f
        if (done) drawExtremes(c)

        dot.color = col
        if (range <= 1 && done) {
            dot.alpha = ((1f - phase) * 90).toInt()
            c.drawCircle(headX, headY, dpf(4f) + dpf(10f) * phase, dot)
        }
        dot.alpha = 255
        c.drawCircle(headX, headY, dpf(4f), dot)
        dot.color = pal.card
        c.drawCircle(headX, headY, dpf(1.8f), dot)

        drawAxisX(c)
        if (scrub >= 0 && done) drawScrub(c, col)
        if (dim) c.restore()
    }

    private fun drawExtremes(c: Canvas) {
        if (minI == maxI) return
        mark.color = pal.text2
        for (k in 0..1) {
            val i = if (k == 0) maxI else minI
            val s = Money.rate(v[i])
            val w = mark.measureText(s)
            var x = xs[i] - w / 2
            x = x.coerceIn(left, right - w)
            val y = if (k == 0) ys[i] - dpf(8f) else ys[i] + dpf(16f)
            c.drawText(s, x, y, mark)
            dot.color = pal.text2
            c.drawCircle(xs[i], ys[i], dpf(2.2f), dot)
        }
    }

    private fun drawAxisX(c: Canvas) {
        val n = t.size
        val kind = when {
            range <= 1 -> 0
            range <= 3 -> 1
            range <= 5 -> 2
            else -> 3
        }
        val y = height - dpf(8f)
        val count = 4
        var prev = ""
        for (k in 0 until count) {
            val i = ((n - 1) * (k + 0.5f) / count).toInt()
            val s = Dates.axis(t[i], kind)
            if (s == prev) continue
            prev = s
            val w = label.measureText(s)
            c.drawText(s, (xs[i] - w / 2).coerceIn(left, right - w), y, label)
        }
    }

    private fun drawScrub(c: Canvas, col: Int) {
        val i = scrub
        val x = xs[i]
        val y = ys[i]
        dot.color = pal.text2
        dot.alpha = 140
        c.drawRect(x - dpf(0.5f), top - dpf(6f), x + dpf(0.5f), bottom, dot)
        dot.alpha = 255
        dot.color = col
        dot.alpha = 60
        c.drawCircle(x, y, dpf(11f), dot)
        dot.alpha = 255
        c.drawCircle(x, y, dpf(5f), dot)
        dot.color = pal.card
        c.drawCircle(x, y, dpf(2.2f), dot)

        val s = Dates.full(t[i], range <= 3)
        val w = bubbleText.measureText(s) + dpf(16f)
        val bx = (x - w / 2).coerceIn(0f, width - w)
        rect.set(bx, dpf(2f), bx + w, dpf(24f))
        c.drawRoundRect(rect, dpf(12f), dpf(12f), bubble)
        c.drawText(s, bx + dpf(8f), dpf(17f), bubbleText)
    }

    private fun drawSkeleton(c: Canvas) {
        val w = width.toFloat()
        val mid = height * 0.5f
        val amp = height * 0.12f
        line.color = pal.stroke
        val p = tmp
        p.reset()
        var x = 0f
        while (x <= w) {
            val y = mid + amp * sin((x / w * 6.28f * 1.5f) + phase * 6.28f)
            if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y)
            x += dpf(4f)
        }
        c.drawPath(p, line)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (message != null) {
            if (e.actionMasked == MotionEvent.ACTION_UP) onRetry?.invoke()
            return true
        }
        val n = xs.size
        if (n < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((e.x - left) / (right - left) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
                if (i != scrub) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    else if (i == minI || i == maxI) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    scrub = i
                    onScrub?.invoke(i)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrub = -1
                onScrub?.invoke(-1)
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val SAMPLES = 120
    }
}
