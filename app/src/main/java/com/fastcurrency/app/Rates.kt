package com.fastcurrency.app

import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Курсы как «единиц валюты за 1 USD». */
class RateTable(
    @JvmField val rates: HashMap<String, Double>,
    @JvmField val source: String,
    @JvmField val fetchedAt: Long,
    @JvmField val dateLabel: String
) {
    fun encode(): String {
        val sb = StringBuilder(rates.size * 16 + 32)
        sb.append(dateLabel).append('|').append(fetchedAt).append('|')
        for (e in rates) sb.append(e.key).append(':').append(e.value).append(';')
        return sb.toString()
    }

    companion object {
        fun decode(raw: String?, source: String): RateTable? {
            if (raw == null) return null
            val p1 = raw.indexOf('|')
            val p2 = raw.indexOf('|', p1 + 1)
            if (p1 < 0 || p2 < 0) return null
            val rates = parseRates(raw, p2 + 1)
            if (rates.isEmpty()) return null
            return RateTable(rates, source, Money.long(raw.substring(p1 + 1, p2)), raw.substring(0, p1))
        }

        fun parseRates(data: String, from: Int): HashMap<String, Double> {
            val out = HashMap<String, Double>(256)
            val n = data.length
            var i = from
            while (i < n) {
                val colon = data.indexOf(':', i)
                if (colon < 0) break
                var end = data.indexOf(';', colon + 1)
                if (end < 0) end = n
                val v = Money.num(data.substring(colon + 1, end))
                if (v > 0.0 && !v.isInfinite()) out[data.substring(i, colon)] = v
                i = end + 1
            }
            return out
        }
    }
}

object Rates {

    const val ER = "er"
    const val CBR = "cbr"
    const val SEED = "seed"
    const val CRYPTO = "cr"

    const val FIAT_FRESH_MS = 3L * 3600_000L
    const val CRYPTO_FRESH_MS = 5L * 60_000L

    private var exec: ExecutorService? = null
    private val io: ExecutorService get() = exec ?: Executors.newSingleThreadExecutor().also { exec = it }
    @JvmField val main = Handler(Looper.getMainLooper())

    fun other(source: String): String = if (source == CBR) ER else CBR

    fun title(source: String): String = when (source) {
        CBR -> "ЦБ РФ"
        SEED -> "встроенные"
        CRYPTO -> "крипто"
        else -> "ER-API"
    }

    fun refreshFiat(source: String, cb: (RateTable?) -> Unit) = io.execute {
        val t = attempt { fetchFiat(source) } ?: attempt { fetchFiat(other(source)) }
        main.post { cb(t) }
    }

    fun refreshCrypto(cb: (RateTable?) -> Unit) = io.execute {
        val t = attempt { binance() } ?: attempt { coinbase() }
        main.post { cb(t) }
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private fun fetchFiat(source: String): RateTable = if (source == CBR) cbr() else er()

    private fun er(): RateTable {
        val root = JSONObject(Net.get("https://open.er-api.com/v6/latest/USD"))
        val obj = root.getJSONObject("rates")
        val map = HashMap<String, Double>(obj.length() * 2)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optDouble(k)
            if (v > 0.0) map[k] = v
        }
        if (map.size < 10) throw IOException()
        map["USD"] = 1.0
        val now = System.currentTimeMillis()
        val unix = root.optLong("time_last_update_unix") * 1000L
        return RateTable(map, ER, now, Dates.day(if (unix > 0) unix else now))
    }

    private fun cbr(): RateTable {
        val p = Xml.newPullParser()
        p.setInput(StringReader(Net.get("https://www.cbr.ru/scripts/XML_daily.asp", "windows-1251")))
        val rub = HashMap<String, Double>(64)
        val buf = StringBuilder(16)
        var date = ""
        var code = ""
        var nominal = 1.0
        var value = 0.0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    buf.setLength(0)
                    if (p.name == "ValCurs") date = p.getAttributeValue(null, "Date") ?: ""
                }
                XmlPullParser.TEXT -> buf.append(p.text)
                XmlPullParser.END_TAG -> {
                    when (p.name) {
                        "CharCode" -> code = buf.toString().trim()
                        "Nominal" -> nominal = num(buf)
                        "Value" -> value = num(buf)
                        "Valute" -> if (code.length == 3 && value > 0 && nominal > 0) rub[code] = value / nominal
                    }
                    buf.setLength(0)
                }
            }
            ev = p.next()
        }
        val usd = rub["USD"] ?: throw IOException()
        val map = HashMap<String, Double>(rub.size * 2)
        for (e in rub) map[e.key] = usd / e.value
        map["RUB"] = usd
        map["USD"] = 1.0
        val now = System.currentTimeMillis()
        return RateTable(map, CBR, now, date.ifEmpty { Dates.day(now) })
    }

    private fun binance(): RateTable {
        val codes = Currencies.cryptoCodes()
        val sb = StringBuilder("%5B%22USDCUSDT%22")
        for (c in codes) if (c != "USDT" && c != "USDC") sb.append("%2C%22").append(c).append("USDT%22")
        sb.append("%5D")
        val body = try {
            Net.get("https://data-api.binance.vision/api/v3/ticker/price?symbols=$sb")
        } catch (e: IOException) {
            Net.get("https://data-api.binance.vision/api/v3/ticker/price")
        }
        val arr = JSONArray(body)
        val price = HashMap<String, Double>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            price[o.getString("symbol")] = o.optDouble("price")
        }
        val usdt = price["USDCUSDT"]?.takeIf { it > 0 }?.let { 1.0 / it } ?: 1.0
        val map = HashMap<String, Double>(64)
        for (c in codes) {
            val usd = when (c) {
                "USDT" -> usdt
                "USDC" -> 1.0
                else -> (price[c + "USDT"] ?: continue) * usdt
            }
            if (usd > 0) map[c] = 1.0 / usd
        }
        if (map.size < 5) throw IOException()
        val now = System.currentTimeMillis()
        return RateTable(map, CRYPTO, now, Dates.time(now))
    }

    private fun coinbase(): RateTable {
        val r = JSONObject(Net.get("https://api.coinbase.com/v2/exchange-rates?currency=USD"))
            .getJSONObject("data").getJSONObject("rates")
        val map = HashMap<String, Double>(64)
        for (c in Currencies.cryptoCodes()) {
            val v = Money.num(r.optString(c))
            if (v > 0) map[c] = v
        }
        if (map.size < 5) throw IOException()
        val now = System.currentTimeMillis()
        return RateTable(map, CRYPTO, now, Dates.time(now))
    }

    private fun num(s: CharSequence): Double {
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch in '0'..'9') sb.append(ch) else if (ch == ',' || ch == '.') sb.append('.')
        val v = Money.num(sb.toString())
        return if (v > 0) v else 0.0
    }
}

object Net {
    fun get(url: String, charset: String = "UTF-8"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FastCurrency/2")
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val out = ByteArrayOutputStream(16384)
            val s = c.inputStream
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } finally {
                s.close()
            }
            return String(out.toByteArray(), Charset.forName(charset))
        } finally {
            c.disconnect()
        }
    }
}
