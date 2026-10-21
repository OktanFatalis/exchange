package com.fastcurrency.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Состояние и кеш курсов: один текстовый файл, строки «ключ\tзначение». */
object Store {

    private val map = HashMap<String, String>(16)
    private lateinit var file: File
    private var dirty = false
    private var writer: ExecutorService? = null

    fun load(ctx: Context) {
        file = File(ctx.filesDir, "state")
        val bytes: ByteArray
        try {
            val f = FileInputStream(file)
            try {
                bytes = ByteArray(f.available())
                var off = 0
                while (off < bytes.size) {
                    val n = f.read(bytes, off, bytes.size - off)
                    if (n < 0) break
                    off += n
                }
            } finally {
                f.close()
            }
        } catch (e: Exception) {
            migrate(ctx)
            return
        }
        val s = String(bytes, StandardCharsets.UTF_8)
        var start = 0
        val n = s.length
        while (start < n) {
            var end = s.indexOf('\n', start)
            if (end < 0) end = n
            val tab = s.indexOf('\t', start)
            if (tab in (start + 1) until end) map[s.substring(start, tab)] = s.substring(tab + 1, end)
            start = end + 1
        }
    }

    operator fun get(key: String): String? = map[key]

    fun int(key: String, def: Int): Int {
        val v = map[key] ?: return def
        return try {
            v.toInt()
        } catch (e: NumberFormatException) {
            def
        }
    }

    operator fun set(key: String, value: String) {
        if (map.put(key, value) != value) dirty = true
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        val sb = StringBuilder(4096)
        for (e in map) sb.append(e.key).append('\t').append(e.value).append('\n')
        val data = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val target = file
        val w = writer ?: Executors.newSingleThreadExecutor().also { writer = it }
        w.execute {
            val tmp = File(target.path + ".tmp")
            try {
                val o = FileOutputStream(tmp)
                try {
                    o.write(data)
                    o.fd.sync()
                } finally {
                    o.close()
                }
                tmp.renameTo(target)
            } catch (e: Exception) {
            }
        }
    }

    private fun migrate(ctx: Context) {
        if (!File(ctx.applicationInfo.dataDir, "shared_prefs/fastcurrency.xml").exists()) return
        val sp = ctx.getSharedPreferences("fastcurrency", Context.MODE_PRIVATE)
        for (e in sp.all) {
            val v = e.value ?: continue
            val k = when (e.key) {
                "active" -> "act"
                "amount" -> "amt"
                "source" -> "src"
                else -> e.key
            }
            map[k] = v.toString().replace('\n', ' ').replace('\t', ' ')
        }
        dirty = true
        flush()
        ctx.deleteSharedPreferences("fastcurrency")
    }
}
