package com.fastcurrency.app

import android.app.Activity
import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class MainActivity : Activity() {

    private class Cell(
        @JvmField val root: LinearLayout,
        @JvmField val flag: TextView,
        @JvmField val code: TextView,
        @JvmField val name: TextView,
        @JvmField val input: EditText
    ) {
        @JvmField var subtitle = ""
    }

    private lateinit var pal: Palette
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var column: Column
    private lateinit var cells: Array<Cell>
    private lateinit var codes: Array<String>
    private lateinit var fiat: RateTable
    private var crypto: RateTable? = null
    private lateinit var status: TextView
    private lateinit var chip: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var hint: TextView

    private var source = Rates.ER
    private var active = 0
    private var silent = false
    private var fiatJobs = 0
    private var cryptoJobs = 0
    private var offline = false
    private var drawn = false
    private var pickFirst = -1
    private var sheet: Sheet? = null
    private var insetTop = 0
    private var insetBottom = 0
    private var pendingDelete = -1
    private val cursor = IntArray(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        Store.load(this)
        pal = Ui.init(this)

        codes = loadCodes()
        source = Store["src"] ?: Rates.ER
        fiat = RateTable.decode(Store["t_$source"], source)
            ?: RateTable.decode(Store["t_" + Rates.other(source)], Rates.other(source))
            ?: Seed.table()
        crypto = RateTable.decode(Store["t_" + Rates.CRYPTO], Rates.CRYPTO)
        active = Store.int("act", 0).coerceIn(0, codes.size - 1)

        buildUi()
        setContentView(root)
        setupWindow()

        val amount = Store["amt"] ?: ""
        if (amount.isNotEmpty()) {
            silent = true
            cells[active].input.setText(amount)
            silent = false
        }
        recalc(amount)
        updateStatus()

        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                root.post {
                    drawn = true
                    maybeRefresh()
                }
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (drawn) maybeRefresh()
    }

    override fun onPause() {
        super.onPause()
        Store["act"] = active.toString()
        Store["amt"] = cells[active].input.text.toString()
        Store.flush()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = sheet
        when {
            s != null -> s.dismiss()
            pickFirst >= 0 -> cancelPick()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val w = window
        val decor = w.decorView
        val light = !pal.night
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (light) m else 0, m)
        } else {
            var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (light) {
                f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            decor.systemUiVisibility = f
        }
        w.statusBarColor = 0
        w.navigationBarColor = if (light && Build.VERSION.SDK_INT < 26) 0x55000000 else 0
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
    }

    private fun loadCodes(): Array<String> {
        val def = Currencies.DEFAULT_CODES
        val raw = Store["codes"] ?: return def.copyOf()
        val parts = TextUtils.split(raw, ",")
        if (parts.size != def.size) return def.copyOf()
        for (c in parts) {
            if (c.length < 2 || c.length > 5) return def.copyOf()
            for (ch in c) if (ch !in 'A'..'Z') return def.copyOf()
        }
        return parts
    }


    private fun buildUi() {
        root = FrameLayout(this)
        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        column = Column(this)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL
        column.isFocusableInTouchMode = true
        column.setPadding(dp(14), dp(12), dp(14), dp(12))
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        status = text(this, 12f, pal.text2).oneLine()
        column.addView(status, lp(MATCH, WRAP).apply { setMargins(dp(6), 0, dp(6), dp(10)) })

        cells = Array(codes.size) { makeCell(it) }
        for (i in codes.indices) bindCurrency(i)

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        chip = text(this, 13f, pal.accent, Ui.MEDIUM)
        chip.background = ripple(round(pal.accentSoft, 16), 16)
        chip.setPadding(dp(14), dp(9), dp(14), dp(9))
        chip.setOnClickListener { switchSource() }
        bar.addView(chip)
        refreshBtn = text(this, 20f, pal.accent)
        refreshBtn.text = "⟳"
        refreshBtn.gravity = Gravity.CENTER
        refreshBtn.contentDescription = "Обновить курсы"
        refreshBtn.background = ripple(null, 20)
        refreshBtn.setOnClickListener { refreshAll() }
        bar.addView(refreshBtn, lp(dp(40), dp(40)).apply { leftMargin = dp(4) })
        hint = text(this, 12f, pal.text2)
        hint.gravity = Gravity.END
        hint.maxLines = 2
        bar.addView(hint, lp(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(6) })
        column.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(2) })
        updateHint()

        root.setOnApplyWindowInsetsListener { _, ins -> applyInsets(ins); ins }
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(ins: WindowInsets) {
        val l: Int
        val t: Int
        val r: Int
        val b: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            l = bars.left; t = bars.top; r = bars.right
            b = max(bars.bottom, ins.getInsets(WindowInsets.Type.ime()).bottom)
        } else {
            l = ins.systemWindowInsetLeft; t = ins.systemWindowInsetTop
            r = ins.systemWindowInsetRight; b = ins.systemWindowInsetBottom
        }
        scroll.setPadding(l, t, r, b)
        insetTop = t
        insetBottom = b
        sheet?.insets(t, b)
    }

    private fun cellBackground(): StateListDrawable {
        val d = StateListDrawable()
        d.setEnterFadeDuration(120)
        d.setExitFadeDuration(120)
        d.addState(intArrayOf(android.R.attr.state_selected), round(pal.accentSoft, 18, 2, pal.accent))
        d.addState(intArrayOf(android.R.attr.state_activated), round(pal.card, 18, 2, pal.accent))
        d.addState(intArrayOf(), round(pal.card, 18, 1, pal.stroke))
        return d
    }

    private fun makeCell(i: Int): Cell {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = cellBackground()
        row.minimumHeight = dp(66)
        row.setPadding(dp(8), dp(8), dp(14), dp(8))

        val pick = LinearLayout(this)
        pick.gravity = Gravity.CENTER_VERTICAL
        pick.background = ripple(null, 14)
        pick.minimumWidth = dp(128)
        pick.setPadding(dp(6), dp(6), dp(6), dp(6))
        val flag = text(this, 24f, -1)
        flag.gravity = Gravity.CENTER
        pick.addView(flag, lp(dp(34), dp(34)))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val code = text(this, 15f, pal.text, Ui.MEDIUM)
        val name = text(this, 11f, pal.text2).oneLine()
        texts.addView(code)
        texts.addView(name, lp(MATCH, WRAP).apply { topMargin = dp(3) })
        pick.addView(texts, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        row.addView(pick, lp(WRAP, WRAP))

        val input = EditText(this)
        input.background = null
        input.setPadding(dp(4), dp(10), 0, dp(10))
        input.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        input.setTextColor(pal.text)
        input.setHintTextColor(pal.text2)
        input.hint = "0"
        input.fontFeatureSettings = "tnum"
        input.isSingleLine = true
        input.keyListener = DigitsKeyListener.getInstance("0123456789., ")
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setSelectAllOnFocus(true)
        input.isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= 26) input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        row.addView(input, lp(0, WRAP, 1f).apply { leftMargin = dp(2) })

        column.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        val cell = Cell(row, flag, code, name, input)

        pick.setOnClickListener { showPicker(i) }
        row.setOnClickListener {
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }
        val long = View.OnLongClickListener { startPick(i); true }
        row.setOnLongClickListener(long)
        pick.setOnLongClickListener(long)
        input.setOnLongClickListener(long)
        input.setOnFocusChangeListener { _, has ->
            if (has && active != i) {
                setActive(i)
                recalcFromActive()
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                pendingDelete = if (!silent && count == 1 && after == 0 && s[start] == Money.SP) start - 1 else -1
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(e: Editable) {
                if (silent) return
                setActive(i)
                silent = true
                val del = pendingDelete
                pendingDelete = -1
                if (del >= 0 && del < e.length) {
                    e.delete(del, del + 1)
                    Selection.setSelection(e, del)
                }
                val formatted = Money.typing(e, Selection.getSelectionEnd(e), cursor)
                if (!TextUtils.equals(formatted, e)) {
                    e.replace(0, e.length, formatted)
                    Selection.setSelection(e, cursor[0].coerceAtMost(e.length))
                }
                silent = false
                recalc(e.toString())
            }
        })
        row.isActivated = i == active
        return cell
    }

    private fun bindCurrency(i: Int) {
        val c = Currencies.get(codes[i])
        val cell = cells[i]
        cell.flag.text = c.flag
        if (c.crypto) {
            cell.flag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            cell.flag.setTextColor(-1)
            cell.flag.typeface = android.graphics.Typeface.DEFAULT_BOLD
            cell.flag.background = round(c.color, 17)
        } else {
            cell.flag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            cell.flag.background = null
        }
        cell.code.text = c.code
    }

    private fun setActive(i: Int) {
        if (active == i) return
        cells[active].root.isActivated = false
        active = i
        cells[i].root.isActivated = true
    }


    private fun rateOf(code: String): Double {
        val t = if (Currencies.isCrypto(code)) crypto else fiat
        return t?.rates?.get(code) ?: Double.NaN
    }

    private fun recalc(text: String) {
        val amount = Money.parse(text)
        val from = active
        val fromCode = codes[from]
        val fromRate = rateOf(fromCode)
        for (j in cells.indices) {
            val cell = cells[j]
            val code = codes[j]
            val rate = rateOf(code)
            if (j != from) {
                val out = if (amount.isNaN() || fromRate.isNaN() || rate.isNaN()) "" else Money.amount(amount / fromRate * rate, code)
                if (!TextUtils.equals(cell.input.text, out)) {
                    silent = true
                    cell.input.setText(out)
                    silent = false
                }
            }
            val sub = when {
                rate.isNaN() -> if (Currencies.isCrypto(code)) "нет курса — нужна сеть" else "нет курса в «" + Rates.title(fiat.source) + "»"
                j == from || fromRate.isNaN() -> Currencies.get(code).name
                else -> "1 $fromCode = " + Money.rate(rate / fromRate)
            }
            if (cell.subtitle != sub) {
                cell.subtitle = sub
                cell.name.text = sub
            }
        }
    }

    private fun recalcFromActive() = recalc(cells[active].input.text.toString())


    private fun startPick(i: Int) {
        if (sheet != null) return
        if (pickFirst >= 0) {
            pickSecond(i)
            return
        }
        pickFirst = i
        hideKeyboard()
        column.requestFocus()
        cells[i].root.isSelected = true
        bump(cells[i].root)
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        column.onTap = { y ->
            var hit = -1
            for (k in cells.indices) if (y >= cells[k].root.top && y <= cells[k].root.bottom) hit = k
            if (hit < 0) cancelPick() else pickSecond(hit)
        }
        updateHint()
    }

    private fun pickSecond(i: Int) {
        val a = pickFirst
        if (i == a) {
            cancelPick()
            return
        }
        if (codes[i] == codes[a]) {
            Toast.makeText(this, "Выберите другую валюту", Toast.LENGTH_SHORT).show()
            return
        }
        cells[i].root.isSelected = true
        bump(cells[i].root)
        root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
        openChart(codes[a], codes[i])
        root.postDelayed({ cancelPick() }, 350)
    }

    private fun cancelPick() {
        if (pickFirst < 0) return
        for (c in cells) c.root.isSelected = false
        pickFirst = -1
        column.onTap = null
        updateHint()
    }

    private fun bump(v: View) {
        v.scaleX = 0.96f
        v.scaleY = 0.96f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    private fun updateHint() {
        if (pickFirst >= 0) {
            hint.text = codes[pickFirst] + " → выберите вторую валюту"
            hint.setTextColor(pal.accent)
        } else {
            hint.text = "Удерживайте две валюты — откроется график"
            hint.setTextColor(pal.text2)
        }
    }

    private fun openChart(a: String, b: String) {
        val r = rateOf(b) / rateOf(a)
        val chart = ChartSheet(this, root, a, b, r) { sheet = null }
        sheet = chart.sheet
        chart.sheet.show(insetTop, insetBottom)
    }


    private fun showPicker(index: Int) {
        if (sheet != null || pickFirst >= 0) return
        hideKeyboard()
        val s = Sheet(root, 64) { sheet = null }
        val search = EditText(this)
        search.background = round(pal.bg, 14)
        search.setPadding(dp(14), dp(11), dp(14), dp(11))
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        search.setTextColor(pal.text)
        search.setHintTextColor(pal.text2)
        search.hint = "Поиск: код или название"
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        search.isSaveEnabled = false
        s.panel.addView(search, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(2), dp(16), dp(8)) })

        val adapter = PickAdapter(pickerItems())
        val list = ListView(this)
        list.divider = null
        list.isVerticalScrollBarEnabled = false
        list.selector = ripple(null, 0)
        list.adapter = adapter
        s.panel.addView(list, lp(MATCH, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: Editable) = adapter.filter(e.toString())
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val c = adapter.getItem(pos) as? Currency ?: return@setOnItemClickListener
            hideKeyboard()
            choose(index, c)
            s.dismiss()
        }
        sheet = s
        s.show(insetTop, insetBottom)
    }

    private fun choose(index: Int, c: Currency) {
        if (c.code == codes[index]) return
        codes[index] = c.code
        Store["codes"] = TextUtils.join(",", codes)
        Store.flush()
        bindCurrency(index)
        recalcFromActive()
        updateStatus()
        if (c.crypto) maybeRefresh()
    }

    private fun pickerItems(): List<Any> {
        val out = ArrayList<Any>(220)
        val seen = HashSet<String>()
        out.add("Выбранные и популярные")
        for (code in codes) if (seen.add(code)) out.add(Currencies.get(code))
        for (code in POPULAR) if (seen.add(code)) out.add(Currencies.get(code))
        out.add("Криптовалюты")
        for (c in Currencies.crypto()) if (c.code !in seen) out.add(c)
        out.add("Все валюты")
        for (c in Currencies.fiat()) if (c.code !in seen) out.add(c)
        return out
    }

    private inner class PickAdapter(private val all: List<Any>) : BaseAdapter() {
        private var items: List<Any> = all

        fun filter(raw: String) {
            val q = raw.trim().lowercase()
            items = if (q.isEmpty()) all else {
                val head = ArrayList<Any>()
                val tail = ArrayList<Any>()
                for (o in all) {
                    val c = o as? Currency ?: continue
                    val code = c.code.lowercase()
                    val name = c.name.lowercase()
                    val en = c.nameEn.lowercase()
                    if (code.startsWith(q) || name.startsWith(q) || en.startsWith(q)) head.add(c)
                    else if (name.indexOf(q) >= 0 || en.indexOf(q) >= 0) tail.add(c)
                }
                head.addAll(tail)
                head
            }
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is String) 0 else 1
        override fun isEnabled(p: Int) = items[p] !is String
        override fun areAllItemsEnabled() = false

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val o = items[p]
            if (o is String) {
                val h = convert as? TextView ?: text(this@MainActivity, 12f, pal.text2, Ui.MEDIUM).also {
                    it.setPadding(dp(22), dp(16), dp(22), dp(6))
                    it.isAllCaps = true
                    it.letterSpacing = 0.06f
                }
                h.text = o
                return h
            }
            val c = o as Currency
            val row = convert as? LinearLayout ?: makeRow()
            val flag = row.getChildAt(0) as TextView
            val texts = row.getChildAt(1) as LinearLayout
            flag.text = c.flag
            if (c.crypto) {
                flag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                flag.background = round(c.color, 16)
            } else {
                flag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
                flag.background = null
            }
            (texts.getChildAt(0) as TextView).text = c.code
            val has = !rateOf(c.code).isNaN()
            (texts.getChildAt(1) as TextView).text = if (has) c.name else c.name + " · нет курса"
            return row
        }

        private fun makeRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity)
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = dp(56)
            row.setPadding(dp(20), dp(6), dp(20), dp(6))
            val flag = text(this@MainActivity, 24f, -1, android.graphics.Typeface.DEFAULT_BOLD)
            flag.gravity = Gravity.CENTER
            row.addView(flag, lp(dp(32), dp(32)))
            val texts = LinearLayout(this@MainActivity)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(text(this@MainActivity, 16f, pal.text, Ui.MEDIUM))
            texts.addView(text(this@MainActivity, 12f, pal.text2).oneLine(), lp(MATCH, WRAP).apply { topMargin = dp(3) })
            row.addView(texts, lp(0, WRAP, 1f).apply { leftMargin = dp(14) })
            return row
        }
    }


    private fun hasCrypto(): Boolean {
        for (c in codes) if (Currencies.isCrypto(c)) return true
        return false
    }

    private fun maybeRefresh() {
        val now = System.currentTimeMillis()
        if (now - fiat.fetchedAt > Rates.FIAT_FRESH_MS) refreshFiat(false)
        val c = crypto
        if (hasCrypto() && (c == null || now - c.fetchedAt > Rates.CRYPTO_FRESH_MS)) refreshCrypto()
    }

    private fun refreshAll() {
        refreshFiat(true)
        refreshCrypto()
    }

    private fun refreshFiat(manual: Boolean) {
        if (fiatJobs > 0 && !manual) return
        fiatJobs++
        spin()
        val requested = source
        Rates.refreshFiat(requested) { t ->
            fiatJobs--
            if (isDestroyed) return@refreshFiat
            spin()
            if (t != null) {
                Store["t_" + t.source] = t.encode()
                Store.flush()
            }
            if (requested != source) return@refreshFiat
            offline = t == null
            if (t != null) {
                fiat = t
                recalcFromActive()
            } else if (manual) {
                Toast.makeText(this, "Нет сети — показаны сохранённые курсы", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }
    }

    private fun refreshCrypto() {
        if (cryptoJobs > 0) return
        cryptoJobs++
        spin()
        Rates.refreshCrypto { t ->
            cryptoJobs--
            if (isDestroyed) return@refreshCrypto
            spin()
            if (t != null) {
                crypto = t
                Store["t_" + Rates.CRYPTO] = t.encode()
                Store.flush()
                recalcFromActive()
                updateStatus()
            }
        }
    }

    private fun spin() {
        val busy = fiatJobs > 0 || cryptoJobs > 0
        if (busy && refreshBtn.animation == null && refreshBtn.tag == null) {
            refreshBtn.tag = true
            refreshBtn.animate().rotationBy(360f * 20).setDuration(20_000)
                .setInterpolator(android.view.animation.LinearInterpolator()).start()
        } else if (!busy && refreshBtn.tag != null) {
            refreshBtn.tag = null
            refreshBtn.animate().cancel()
            refreshBtn.animate().rotation(((refreshBtn.rotation / 360f).toInt() + 1) * 360f).setDuration(300).start()
        }
    }

    private fun switchSource() {
        source = Rates.other(source)
        Store["src"] = source
        RateTable.decode(Store["t_$source"], source)?.let {
            fiat = it
            offline = false
            recalcFromActive()
        }
        updateStatus()
        refreshFiat(true)
    }

    private fun updateStatus() {
        chip.text = "Источник: " + Rates.title(source)
        val sb = StringBuilder(64)
        sb.append(Rates.title(fiat.source)).append(" · ").append(fiat.dateLabel)
        if (offline) sb.append(" · нет сети")
        if (hasCrypto()) {
            val c = crypto
            sb.append(" · крипто ").append(c?.dateLabel ?: "—")
        }
        status.text = sb
        val old = System.currentTimeMillis() - fiat.fetchedAt > 48L * 3600_000L
        status.setTextColor(if (old) pal.warn else pal.text2)
    }

    private fun hideKeyboard() {
        val v = currentFocus ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private companion object {
        val POPULAR = arrayOf("USD", "EUR", "RUB", "KRW", "CNY", "JPY", "GBP", "KZT", "TRY", "AED", "THB", "BTC", "ETH", "USDT")
    }
}
