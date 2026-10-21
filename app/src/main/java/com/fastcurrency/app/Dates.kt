package com.fastcurrency.app

import java.util.TimeZone

object Dates {

    private val GEN = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    private val NOM = arrayOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    /** [год, месяц 1..12, день, час, минута] в местном времени. */
    private fun split(ms: Long, utc: Boolean = false): IntArray {
        val local = if (utc) ms else ms + TimeZone.getDefault().getOffset(ms)
        val days = Math.floorDiv(local, 86_400_000L)
        val min = (Math.floorMod(local, 86_400_000L) / 60_000L).toInt()
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val f = IntArray(5)
        f[0] = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        f[1] = m
        f[2] = (doy - (153 * mp + 2) / 5 + 1).toInt()
        f[3] = min / 60
        f[4] = min % 60
        return f
    }

    /** Полночь UTC указанной даты. */
    fun utc(y: Int, m: Int, d: Int): Long {
        val yy = (if (m <= 2) y - 1 else y).toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097 + doe - 719_468) * 86_400_000L
    }

    private fun StringBuilder.two(v: Int): StringBuilder {
        if (v < 10) append('0')
        return append(v)
    }

    fun day(ms: Long): String {
        val f = split(ms)
        return StringBuilder(10).two(f[2]).append('.').two(f[1]).append('.').append(f[0]).toString()
    }

    fun time(ms: Long): String {
        val f = split(ms)
        return StringBuilder(5).two(f[3]).append(':').two(f[4]).toString()
    }

    fun full(ms: Long, withTime: Boolean): String {
        val f = split(ms)
        val sb = StringBuilder(20).append(f[2]).append(' ').append(GEN[f[1] - 1]).append(' ').append(f[0])
        if (withTime) sb.append(", ").two(f[3]).append(':').two(f[4])
        return sb.toString()
    }

    /** Подпись оси X: 0 — время, 1 — день и месяц, 2 — месяц, 3 — год. */
    fun axis(ms: Long, kind: Int): String {
        val f = split(ms)
        return when (kind) {
            0 -> StringBuilder(5).two(f[3]).append(':').two(f[4]).toString()
            1 -> "${f[2]} ${GEN[f[1] - 1]}"
            2 -> if (f[1] == 1) f[0].toString() else NOM[f[1] - 1]
            else -> f[0].toString()
        }
    }

    /** yyyy-MM-dd (UTC). */
    fun iso(ms: Long): String {
        val f = split(ms, true)
        return StringBuilder(10).append(f[0]).append('-').two(f[1]).append('-').two(f[2]).toString()
    }

    /** dd/MM/yyyy (UTC) — формат запросов ЦБ РФ. */
    fun dmy(ms: Long): String {
        val f = split(ms, true)
        return StringBuilder(10).two(f[2]).append('/').two(f[1]).append('/').append(f[0]).toString()
    }
}
