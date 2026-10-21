package com.fastcurrency.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

class Palette(@JvmField val night: Boolean) {
    @JvmField val bg = if (night) 0xFF0E1116.toInt() else 0xFFF3F5F9.toInt()
    @JvmField val card = if (night) 0xFF171C24.toInt() else 0xFFFFFFFF.toInt()
    @JvmField val stroke = if (night) 0xFF262E3A.toInt() else 0xFFE1E6EF.toInt()
    @JvmField val text = if (night) 0xFFF2F5FA.toInt() else 0xFF10131A.toInt()
    @JvmField val text2 = if (night) 0xFF9AA4B2.toInt() else 0xFF6B7280.toInt()
    @JvmField val accent = if (night) 0xFF5B8DEF.toInt() else 0xFF2563EB.toInt()
    @JvmField val accentSoft = if (night) 0xFF1B2740.toInt() else 0xFFE7EEFD.toInt()
    @JvmField val warn = if (night) 0xFFE9A25A.toInt() else 0xFFB4530A.toInt()
    @JvmField val up = if (night) 0xFF22C55E.toInt() else 0xFF16A34A.toInt()
    @JvmField val down = if (night) 0xFFF87171.toInt() else 0xFFDC2626.toInt()
    @JvmField val ripple = if (night) 0x33FFFFFF else 0x1F000000
}

object Ui {
    @JvmField var density = 1f
    lateinit var pal: Palette

    fun init(ctx: Context): Palette {
        density = ctx.resources.displayMetrics.density
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        pal = Palette(night)
        return pal
    }

    @JvmField val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun dp(v: Int): Int = (v * Ui.density + 0.5f).toInt()

fun dpf(v: Float): Float = v * Ui.density

fun round(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = dpf(radius.toFloat())
    if (stroke > 0) d.setStroke(dp(stroke), strokeColor)
    return d
}

fun ripple(content: Drawable?, radius: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(Ui.pal.ripple), content, round(-1, radius))

fun text(ctx: Context, sp: Float, color: Int, face: Typeface? = null): TextView {
    val t = TextView(ctx)
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.setTextColor(color)
    t.includeFontPadding = false
    if (face != null) t.typeface = face
    return t
}

fun TextView.oneLine(): TextView {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    return this
}

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Столбец ячеек: в режиме выбора пары забирает касания себе. */
class Column(ctx: Context) : LinearLayout(ctx) {
    var onTap: ((Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = onTap != null || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val tap = onTap ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tracking = true
            }
            MotionEvent.ACTION_UP -> {
                if (tracking && abs(ev.x - downX) < slop * 2 && abs(ev.y - downY) < slop * 2) tap(ev.y)
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }
}

/** Нижняя панель поверх экрана: выезжает снизу, закрывается свайпом вниз, тапом по фону и «Назад». */
class Sheet(private val host: FrameLayout, private val gap: Int, private val onClosed: () -> Unit) {

    private val ctx = host.context
    private val scrim = View(ctx)
    @JvmField val panel = LinearLayout(ctx)
    private var closing = false

    init {
        scrim.setBackgroundColor(0x8C000000.toInt())
        scrim.alpha = 0f
        scrim.setOnClickListener { dismiss() }
        panel.orientation = LinearLayout.VERTICAL
        val r = dpf(24f)
        panel.background = GradientDrawable().apply {
            setColor(Ui.pal.card)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.isClickable = true
        val box = FrameLayout(ctx)
        val handle = View(ctx)
        handle.background = round(Ui.pal.stroke, 2)
        box.addView(handle, FrameLayout.LayoutParams(dp(40), dp(4), android.view.Gravity.CENTER))
        panel.addView(box, lp(MATCH, dp(24)))
        draggable(box)
    }

    fun show(top: Int, bottom: Int) {
        host.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        host.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
        insets(top, bottom)
        panel.translationY = max(host.height, dp(600)).toFloat()
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.animate().translationY(0f).setDuration(340).setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    fun insets(top: Int, bottom: Int) {
        (panel.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = top + dp(gap)
            panel.layoutParams = it
        }
        panel.setPadding(0, 0, 0, bottom)
    }

    fun dismiss() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                host.removeView(scrim)
                host.removeView(panel)
            }.start()
        onClosed()
    }

    /** Свайп вниз по этой области закрывает панель. */
    fun draggable(v: View) {
        var startY = 0f
        var lastY = 0f
        var lastT = 0L
        var speed = 0f
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY; lastY = startY; lastT = ev.eventTime; speed = 0f
                    panel.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dt = (ev.eventTime - lastT).coerceAtLeast(1)
                    speed = (ev.rawY - lastY) / dt
                    lastY = ev.rawY; lastT = ev.eventTime
                    panel.translationY = max(0f, ev.rawY - startY)
                    scrim.alpha = 1f - panel.translationY / max(1, panel.height)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (panel.translationY > dp(120) || speed > 1.2f * Ui.density) dismiss()
                    else {
                        panel.animate().translationY(0f).setDuration(200).start()
                        scrim.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
            true
        }
    }
}
