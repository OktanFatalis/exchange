package com.fastcurrency.app

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

object Money {

    const val SP = ' '

    private const val KEEP = 0
    private const val DROP_ZERO = 1
    private const val TRIM = 2

    private const val MAX_INT_DIGITS = 15
    private const val MAX_FRAC_DIGITS = 10

    private val POW = LongArray(19).also {
        var p = 1L
        for (i in it.indices) { it[i] = p; p *= 10 }
    }

    private val ZERO_DECIMAL = hashSetOf(
        "BIF", "CLP", "COP", "DJF", "GNF", "IDR", "IRR", "ISK", "JPY", "KHR", "KMF", "KRW",
        "LAK", "LBP", "MGA", "MMK", "MNT", "PYG", "RWF", "UGX", "UZS", "VND", "VUV",
        "XAF", "XOF", "XPF"
    )

    fun parse(s: CharSequence): Double {
        val n = s.length
        if (n == 0) return Double.NaN
        val sb = StringBuilder(n)
        var dot = false
        var digit = false
        for (i in 0 until n) {
            val ch = s[i]
            if (ch in '0'..'9') {
                sb.append(ch)
                digit = true
            } else if ((ch == ',' || ch == '.') && !dot) {
                sb.append('.')
                dot = true
            }
        }
        if (!digit) return Double.NaN
        val v = num(sb.toString())
        return if (v.isInfinite()) Double.NaN else v
    }

    fun num(s: String): Double = try {
        s.toDouble()
    } catch (e: NumberFormatException) {
        Double.NaN
    }

    fun long(s: String): Long = try {
        s.toLong()
    } catch (e: NumberFormatException) {
        0L
    }

    fun amount(v: Double, code: String): String {
        if (v.isNaN() || v.isInfinite()) return ""
        val a = abs(v)
        if (a == 0.0) return "0"
        val crypto = Currencies.isCrypto(code)
        if (a < 1.0) return small(v, if (crypto) 4 else 3, if (crypto) 10 else 8, TRIM)
        return when {
            crypto -> fixed(v, if (a >= 1000.0) 2 else 6, TRIM)
            a >= 1e9 || code in ZERO_DECIMAL -> fixed(v, 0, KEEP)
            else -> fixed(v, 2, DROP_ZERO)
        }
    }

    fun rate(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return ""
        val a = abs(v)
        return when {
            a == 0.0 -> "0"
            a >= 1000.0 -> fixed(v, 2, KEEP)
            a >= 1.0 -> fixed(v, 4, KEEP)
            else -> small(v, 4, 12, KEEP)
        }
    }

    /** Подпись оси: столько знаков, сколько нужно, чтобы различать шаг сетки. */
    fun axis(v: Double, step: Double): String {
        val d = if (step >= 1.0) 0 else min(12, max(0, -floor(log10(step)).toInt()))
        return fixed(v, d, KEEP)
    }

    fun signed(v: Double, body: String): String = if (v > 0) "+$body" else body.replace('-', '−')

    private fun small(v: Double, sig: Int, cap: Int, mode: Int): String {
        val a = abs(v)
        val d = min(cap, -floor(log10(a)).toInt() + sig - 1)
        if (a * POW[cap] < 0.5) {
            val sb = StringBuilder(cap + 3).append("<0,")
            for (i in 1 until cap) sb.append('0')
            return sb.append('1').toString()
        }
        return fixed(v, d, mode)
    }

    fun fixed(value: Double, decimals: Int, mode: Int): String {
        var d = decimals
        val a = abs(value)
        val sb = StringBuilder(28)
        if (a * POW[d] >= 9.0e17) {
            if (value < 0) sb.append('-')
            group(sb, BigDecimal(a).setScale(0, RoundingMode.HALF_UP).toPlainString())
            return sb.toString()
        }
        val scaled = Math.round(a * POW[d])
        val ip = scaled / POW[d]
        var fp = scaled % POW[d]
        when (mode) {
            DROP_ZERO -> if (fp == 0L) d = 0
            TRIM -> while (d > 0 && fp % 10 == 0L) { fp /= 10; d-- }
        }
        if (value < 0 && scaled != 0L) sb.append('-')
        group(sb, ip.toString())
        if (d > 0) {
            sb.append(',')
            val f = fp.toString()
            for (i in f.length until d) sb.append('0')
            sb.append(f)
        }
        return sb.toString()
    }

    private fun group(sb: StringBuilder, digits: String) {
        val n = digits.length
        for (i in 0 until n) {
            if (i > 0 && (n - i) % 3 == 0) sb.append(SP)
            sb.append(digits[i])
        }
    }

    /**
     * Нормализует ввод: разряды через пробел, один разделитель «,», без ведущих нулей.
     * Возвращает новый текст и позицию курсора.
     */
    fun typing(s: CharSequence, cursor: Int, out: IntArray): String {
        val digits = StringBuilder(s.length)
        var sep = -1
        var before = 0
        for (i in 0 until s.length) {
            val ch = s[i]
            val kept = if (ch in '0'..'9') {
                digits.append(ch); true
            } else if ((ch == ',' || ch == '.') && sep < 0) {
                sep = digits.length; true
            } else false
            if (kept && i < cursor) before++
        }
        var ip = if (sep < 0) digits.toString() else digits.substring(0, sep)
        var fp = if (sep < 0) "" else digits.substring(sep)

        var strip = 0
        while (strip < ip.length - 1 && ip[strip] == '0') strip++
        if (strip > 0) {
            ip = ip.substring(strip)
            before = max(0, before - strip)
        }
        if (sep >= 0 && ip.isEmpty()) {
            ip = "0"
            before++
        }
        if (ip.length > MAX_INT_DIGITS) ip = ip.substring(0, MAX_INT_DIGITS)
        if (fp.length > MAX_FRAC_DIGITS) fp = fp.substring(0, MAX_FRAC_DIGITS)

        val sb = StringBuilder(ip.length + ip.length / 3 + fp.length + 1)
        var pos = if (before == 0) 0 else -1
        var count = 0
        val n = ip.length
        for (k in 0 until n) {
            if (k > 0 && (n - k) % 3 == 0) sb.append(SP)
            sb.append(ip[k])
            if (++count == before && pos < 0) pos = sb.length
        }
        if (sep >= 0) {
            sb.append(',')
            if (++count == before && pos < 0) pos = sb.length
            for (k in fp.indices) {
                sb.append(fp[k])
                if (++count == before && pos < 0) pos = sb.length
            }
        }
        out[0] = if (pos < 0) sb.length else pos
        return sb.toString()
    }
}
