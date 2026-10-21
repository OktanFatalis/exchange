package com.fastcurrency.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Series(@JvmField val t: LongArray, @JvmField val v: DoubleArray, @JvmField val src: String) {
    val size: Int get() = t.size

    fun inverted(): Series = Series(t, DoubleArray(v.size) { 1.0 / v[it] }, src)
}

private class Builder(cap: Int) {
    var t = LongArray(max(cap, 8))
    var v = DoubleArray(max(cap, 8))
    var n = 0

    fun add(time: Long, value: Double) {
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) return
        if (n == t.size) {
            t = t.copyOf(n * 2)
            v = v.copyOf(n * 2)
        }
        t[n] = time
        v[n] = value
        n++
    }

    fun build(src: String): Series {
        for (i in 1 until n) {
            val ti = t[i]
            val vi = v[i]
            var j = i - 1
            while (j >= 0 && t[j] > ti) {
                t[j + 1] = t[j]
                v[j + 1] = v[j]
                j--
            }
            t[j + 1] = ti
            v[j + 1] = vi
        }
        var k = 0
        for (i in 0 until n) {
            if (k > 0 && t[k - 1] == t[i]) k--
            t[k] = t[i]
            v[k] = v[i]
            k++
        }
        return Series(t.copyOf(k), v.copyOf(k), src)
    }
}

/**
 * История курса пары. Каждая валюта сводится к «цене в USD» (нога), пара = нога A / нога B.
 * Источники по убыванию предпочтения: Binance (крипто), Yahoo Finance (всё, включая часы),
 * ЕЦБ через Frankfurter (с 1999), ЦБ РФ (рубль с 1992).
 */
object History {

    @JvmField val LABELS = arrayOf("2Ч", "1Д", "1Н", "1М", "6М", "1Г", "5Л", "10Л", "15Л", "30Л")
    @JvmField val PERIODS = arrayOf(
        "за 2 часа", "за сутки", "за неделю", "за месяц", "за полгода",
        "за год", "за 5 лет", "за 10 лет", "за 15 лет", "за 30 лет"
    )

    private const val H = 3_600_000L
    private const val D = 24 * H
    @JvmField val SPAN = longArrayOf(2 * H, D, 7 * D, 30 * D, 182 * D, 365 * D, 1826 * D, 3652 * D, 5479 * D, 10957 * D)
    private val YAHOO = arrayOf("1m", "5m", "30m", "1h", "1d", "1d", "1d", "1wk", "1wk", "1wk")
    private val BINANCE = arrayOf("1m", "5m", "30m", "2h", "12h", "1d", "3d", "1w", "1w", "1w")
    private const val POINTS = 700

    private val ECB = hashSetOf(
        "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS",
        "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "ZAR"
    )

    private class Cached(@JvmField val time: Long, @JvmField val s: Series)

    private var exec: ExecutorService? = null
    private val pool: ExecutorService get() = exec ?: Executors.newCachedThreadPool().also { exec = it }
    private val cache = HashMap<String, Cached>()

    fun load(a: String, b: String, r: Int, cb: (Series?, String?) -> Unit) {
        pool.execute {
            var res: Series? = null
            var err: String? = null
            try {
                val fb = pool.submit(Callable { leg(b, r) })
                val la = leg(a, r)
                val lb = try {
                    fb.get()
                } catch (e: ExecutionException) {
                    throw (e.cause as? Exception ?: e)
                }
                res = finish(combine(la, lb), r)
            } catch (e: Exception) {
                err = describe(e)
            }
            Rates.main.post { cb(res, err) }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException -> "Нет соединения с сервером котировок"
        is SocketTimeoutException -> "Сервер котировок не отвечает"
        else -> "Нет данных для этой пары и периода"
    }

    private fun ttl(r: Int): Long = when {
        r <= 1 -> 60_000L
        r <= 3 -> 10 * 60_000L
        else -> 6 * H
    }

    /** Цена 1 единицы [code] в USD; null — это сам доллар. */
    private fun leg(code: String, r: Int): Series? {
        if (code == "USD") return null
        val key = "$code/$r"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val c = cache[key]
            if (c != null && now - c.time < ttl(r)) return c.s
        }
        val s = fetchLeg(code, r)
        synchronized(cache) { cache[key] = Cached(now, s) }
        return s
    }

    private fun fetchLeg(code: String, r: Int): Series {
        val tries = ArrayList<() -> Series>(4)
        if (Currencies.isCrypto(code)) {
            when (code) {
                "USDT" -> {
                    tries.add { yahoo("USDT-USD", r, false) }
                    tries.add { binance("USDCUSDT", r, true) }
                }
                "USDC" -> {
                    tries.add { yahoo("USDC-USD", r, false) }
                    tries.add { binance("USDCUSDT", r, false) }
                }
                else -> {
                    if (r >= 6) tries.add { yahoo("$code-USD", r, false) }
                    tries.add { binance(code + "USDT", r, false) }
                    if (r < 6) tries.add { yahoo("$code-USD", r, false) }
                }
            }
        } else {
            val ecb = code in ECB
            if (code == "RUB" && r >= 3) tries.add { cbr(r) }
            if (ecb && r >= 7) tries.add { frankfurter(code, r) }
            tries.add { yahoo("$code=X", r, true) }
            if (ecb && r in 2..6) tries.add { frankfurter(code, r) }
            if (code == "RUB" && r == 2) tries.add { cbr(r) }
        }
        var last: Exception? = null
        for (t in tries) {
            try {
                val s = t()
                if (s.size >= 2) return s
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException()
    }


    private fun yahoo(sym: String, r: Int, invert: Boolean): Series {
        val now = System.currentTimeMillis()
        val s = yahooRange(sym, now - SPAN[r], now, r, invert)
        // рынок закрыт (выходные): берём последние торговые часы
        return if (s.size < 2 && r <= 2) yahooRange(sym, now - SPAN[r] - 4 * D, now, r, invert) else s
    }

    private fun yahooRange(sym: String, from: Long, to: Long, r: Int, invert: Boolean): Series {
        var err: Exception = IOException()
        for (host in arrayOf("query1", "query2")) {
            try {
                val body = Net.get(
                    "https://$host.finance.yahoo.com/v8/finance/chart/$sym?period1=${from / 1000}" +
                        "&period2=${to / 1000}&interval=${YAHOO[r]}&includePrePost=false&events="
                )
                return parseYahoo(body, invert)
            } catch (e: Exception) {
                err = e
            }
        }
        throw err
    }

    fun parseYahoo(body: String, invert: Boolean): Series {
        val res = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = res.optJSONArray("timestamp") ?: return Series(LongArray(0), DoubleArray(0), "")
        val close = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
        val n = min(ts.length(), close.length())
        val b = Builder(n)
        for (i in 0 until n) {
            if (close.isNull(i)) continue
            val v = close.getDouble(i)
            b.add(ts.getLong(i) * 1000L, if (invert) 1.0 / v else v)
        }
        return b.build("Yahoo Finance")
    }

    private fun binance(sym: String, r: Int, invert: Boolean): Series {
        val from = System.currentTimeMillis() - SPAN[r]
        var err: Exception = IOException()
        for (host in arrayOf("data-api.binance.vision", "api.binance.com")) {
            try {
                val arr = JSONArray(Net.get("https://$host/api/v3/klines?symbol=$sym&interval=${BINANCE[r]}&startTime=$from&limit=1000"))
                val b = Builder(arr.length())
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONArray(i)
                    val v = row.getString(4).toDouble()
                    b.add(row.getLong(0), if (invert) 1.0 / v else v)
                }
                return b.build("Binance")
            } catch (e: Exception) {
                err = e
            }
        }
        throw err
    }

    private fun frankfurter(code: String, r: Int): Series {
        val from = Dates.iso(System.currentTimeMillis() - SPAN[r])
        val body = try {
            Net.get("https://api.frankfurter.dev/v1/$from..?base=USD&symbols=$code")
        } catch (e: IOException) {
            Net.get("https://api.frankfurter.app/$from..?from=USD&to=$code")
        }
        return parseFrankfurter(body, code)
    }

    fun parseFrankfurter(body: String, code: String): Series {
        val rates = JSONObject(body).getJSONObject("rates")
        val b = Builder(rates.length())
        val keys = rates.keys()
        while (keys.hasNext()) {
            val day = keys.next()
            val v = rates.getJSONObject(day).optDouble(code)
            if (day.length >= 10) {
                val t = Dates.utc(day.substring(0, 4).toInt(), day.substring(5, 7).toInt(), day.substring(8, 10).toInt())
                b.add(t, 1.0 / v)
            }
        }
        return b.build("ЕЦБ")
    }

    private fun cbr(r: Int): Series {
        val now = System.currentTimeMillis()
        val from = max(now - SPAN[r], Dates.utc(1992, 7, 1))
        val body = Net.get(
            "https://www.cbr.ru/scripts/XML_dynamic.asp?date_req1=${Dates.dmy(from)}&date_req2=${Dates.dmy(now)}&VAL_NM_RQ=R01235",
            "windows-1251"
        )
        return parseCbr(body)
    }

    /** USD в рублях по ЦБ → цена рубля в USD. Значения до деноминации 1998 года приводятся к новым рублям. */
    fun parseCbr(body: String): Series {
        val b = Builder(256)
        var i = 0
        while (true) {
            i = body.indexOf("<Record Date=\"", i)
            if (i < 0) break
            val d = i + 14
            val t = Dates.utc(body.substring(d + 6, d + 10).toInt(), body.substring(d + 3, d + 5).toInt(), body.substring(d, d + 2).toInt())
            val nominal = tag(body, "Nominal", d)
            val value = tag(body, "Value", d)
            if (nominal > 0 && value > 0) b.add(t, value / nominal)
            i = d
        }
        val s = b.build("ЦБ РФ")
        val denom = Dates.utc(1998, 1, 1)
        var cut = 0
        while (cut < s.size && s.t[cut] < denom) cut++
        val scale = if (cut in 1 until s.size && s.v[cut - 1] / s.v[cut] > 100) 1000.0 else 1.0
        for (k in 0 until s.size) s.v[k] = 1.0 / (if (k < cut) s.v[k] / scale else s.v[k])
        return s
    }

    private fun tag(s: String, name: String, from: Int): Double {
        val a = s.indexOf("<$name>", from)
        if (a < 0) return 0.0
        val e = s.indexOf("</$name>", a)
        if (e < 0) return 0.0
        val sb = StringBuilder(16)
        for (k in a + name.length + 2 until e) {
            val ch = s[k]
            if (ch in '0'..'9') sb.append(ch) else if (ch == ',' || ch == '.') sb.append('.')
        }
        val v = Money.num(sb.toString())
        return if (v > 0) v else 0.0
    }


    private fun combine(a: Series?, b: Series?): Series {
        if (a == null && b == null) throw IOException()
        if (b == null) return a!!
        if (a == null) return b.inverted()
        val out = Builder(a.size + b.size)
        var i = 0
        var j = 0
        var va = Double.NaN
        var vb = Double.NaN
        while (i < a.size || j < b.size) {
            val ta = if (i < a.size) a.t[i] else Long.MAX_VALUE
            val tb = if (j < b.size) b.t[j] else Long.MAX_VALUE
            val t: Long
            if (ta <= tb) {
                va = a.v[i++]
                t = ta
                if (ta == tb) vb = b.v[j++]
            } else {
                vb = b.v[j++]
                t = tb
            }
            if (!va.isNaN() && !vb.isNaN()) out.add(t, va / vb)
        }
        val src = if (a.src == b.src) a.src else a.src + " · " + b.src
        return out.build(src)
    }

    private fun finish(s: Series, r: Int): Series {
        if (s.size < 2) throw IOException()
        val start = s.t[s.size - 1] - SPAN[r]
        var k = 0
        while (k < s.size - 2 && s.t[k] < start) k++
        val t = s.t.copyOfRange(k, s.size)
        val v = s.v.copyOfRange(k, s.size)
        return lttb(t, v, POINTS, s.src)
    }

    /** Largest-Triangle-Three-Buckets: прореживание с сохранением пиков. */
    fun lttb(t: LongArray, v: DoubleArray, target: Int, src: String): Series {
        val n = t.size
        if (n <= target) return Series(t, v, src)
        val ot = LongArray(target)
        val ov = DoubleArray(target)
        ot[0] = t[0]; ov[0] = v[0]
        val every = (n - 2).toDouble() / (target - 2)
        var a = 0
        for (i in 0 until target - 2) {
            val ns = ((i + 1) * every).toInt() + 1
            val ne = min(((i + 2) * every).toInt() + 1, n)
            var ax = 0.0
            var ay = 0.0
            for (k in ns until ne) { ax += k; ay += v[k] }
            val cnt = max(1, ne - ns)
            ax /= cnt; ay /= cnt
            val rs = (i * every).toInt() + 1
            val re = ((i + 1) * every).toInt() + 1
            var best = -1.0
            var pick = rs
            for (k in rs until re) {
                val area = abs((a - ax) * (v[k] - v[a]) - (a - k) * (ay - v[a]))
                if (area > best) { best = area; pick = k }
            }
            ot[i + 1] = t[pick]; ov[i + 1] = v[pick]
            a = pick
        }
        ot[target - 1] = t[n - 1]; ov[target - 1] = v[n - 1]
        return Series(ot, ov, src)
    }
}
