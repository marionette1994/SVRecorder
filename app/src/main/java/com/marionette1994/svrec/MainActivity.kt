package com.marionette1994.svrec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val reqCapture = 1
    private val reqExport = 2
    private val reqImportTypes = 3

    internal val bg = Color.parseColor("#14141c")
    internal val card = Color.parseColor("#1f1f2b")
    internal val gold = Color.parseColor("#c9a84c")
    internal val textC = Color.parseColor("#e6e2d6")
    internal val sub = Color.parseColor("#8a8796")
    internal val winC = Color.parseColor("#4caf7a")
    internal val lossC = Color.parseColor("#c0392b")

    private lateinit var statusTv: TextView
    private lateinit var toggleBtn: Button
    private lateinit var myClassBtn: Button
    private lateinit var summaryTv: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var diagTv: TextView
    private lateinit var recPage: LinearLayout
    private lateinit var statsPage: LinearLayout
    private lateinit var tabRec: TextView
    private lateinit var tabStats: TextView
    private var showStats = false
    private val statsState = StatsState()
    private var exportIds: Set<String> = emptySet()
    private var exportCsv = ""

    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = bg
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        Store.addListener(listener)
        Store.debugListener = { diagTv.text = it }
        diagTv.text = Store.debug.ifEmpty { "（まだ何も読み取っていません）" }
        refresh()
    }

    override fun onPause() {
        Store.removeListener(listener)
        Store.debugListener = null
        super.onPause()
    }

    // ---------- 画面 ----------

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    internal fun tv(s: String, size: Float = 14f, color: Int = textC, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(s: String, fill: Int = Color.parseColor("#2a2a38"), fg: Int = gold, onClick: () -> Unit) =
        Button(this).apply {
            text = s; setTextColor(fg); isAllCaps = false; textSize = 14f
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(10).toFloat() }
            setOnClickListener { onClick() }
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }

    internal fun section(title: String, parent: LinearLayout): LinearLayout {
        parent.addView(tv(title, 12f, sub).apply { setPadding(0, dp(18), 0, dp(6)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() }
        }
        parent.addView(box)
        return box
    }

    private fun edit(hint: String, value: String, save: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(textC); setHintTextColor(sub); textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { save(s?.toString()?.trim() ?: "") }
        })
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        root.addView(tv("SV戦績オート", 22f, gold, true))
        root.addView(tv("対戦開始とリザルト画面を自動で読み取って記録します", 12f, sub))

        // ---- タブ（記録 / 戦績） ----
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        tabRec = tabButton("記録") { showStats = false; refresh() }
        tabStats = tabButton("戦績") { showStats = true; refresh() }
        tabs.addView(tabRec, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        tabs.addView(tabStats, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        root.addView(tabs)

        statsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        root.addView(statsPage)
        recPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recPage)
        val page = recPage

        statusTv = tv("", 14f, textC, true).apply { setPadding(0, dp(14), 0, 0) }
        page.addView(statusTv)
        toggleBtn = btn("記録を開始", gold, Color.parseColor("#111111")) { toggle() }
        page.addView(toggleBtn)

        val set = section("設定（SVTに取り込むときの名前）", page)
        set.addView(tv("シリーズ名", 11f, sub))
        set.addView(edit("SVTのシリーズ名と同じにする", Store.series) { Store.series = it })
        set.addView(tv("デッキ名", 11f, sub).apply { setPadding(0, dp(8), 0, 0) })
        set.addView(edit("SVTのデッキ名と同じにする（空ならクラス名）", Store.deck) { Store.deck = it })
        myClassBtn = btn("") { chooseDefaultClass() }
        set.addView(myClassBtn)
        set.addView(btn("SVTのCSVから相手デッキタイプの候補を読み込む") { startImportTypes() })

        val rec = section("記録", page)
        summaryTv = tv("", 13f)
        rec.addView(summaryTv)
        rec.addView(btn("CSVを保存（SVTの「CSV取り込み → 追加」で読み込み）") { startExport() })
        rec.addView(btn("書き出し済みの記録を消す") { confirmRemoveExported() })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        rec.addView(listBox)

        val diag = section("診断（直近に読み取った内容）", page)
        diagTv = tv("", 11f, sub).apply { typeface = Typeface.MONOSPACE }
        diag.addView(diagTv)
        diag.addView(btn("判定ログを見る（直近300件・共有できます）") {
            showText("判定ログ", Store.logText().ifEmpty { "（まだ記録を開始していません）" })
        })
        diag.addView(btn("診断用に90秒間、画面を保存する") {
            if (!Store.running) {
                Toast.makeText(this, "先に「記録を開始」してください", Toast.LENGTH_SHORT).show()
            } else {
                Store.debugCaptureUntil = System.currentTimeMillis() + 90_000
                Toast.makeText(this, "90秒間、約1秒ごとに画面を「写真/Pictures/SVRecorder」に保存します。対戦を始めてください", Toast.LENGTH_LONG).show()
            }
        })
        diag.addView(tv("クラスの絵を覚えている数：", 11f, sub).apply { id = View.generateViewId(); tag = "icons" })

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun tabButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun styleTab(t: TextView, sel: Boolean) {
        t.setTextColor(if (sel) Color.parseColor("#111111") else textC)
        t.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        t.background = GradientDrawable().apply {
            setColor(if (sel) gold else Color.parseColor("#2a2a38")); cornerRadius = dp(10).toFloat()
        }
    }

    private fun refresh() {
        styleTab(tabRec, !showStats)
        styleTab(tabStats, showStats)
        recPage.visibility = if (showStats) View.GONE else View.VISIBLE
        statsPage.visibility = if (showStats) View.VISIBLE else View.GONE
        if (showStats) renderStats(statsPage, Store.snapshot(), statsState) { refresh() }

        val running = Store.running
        statusTv.text = if (running) "● 記録中 — シャドバを開いて対戦してください" else "○ 停止中"
        statusTv.setTextColor(if (running) winC else sub)
        toggleBtn.text = if (running) "記録を停止" else "記録を開始"
        myClassBtn.text = "クラスが読めない時の自分のクラス：${Store.defaultMyClass?.let { Classes.name(it) } ?: "なし"}"

        val all = Store.snapshot()
        val pending = all.filter { !it.exported }
        val w = pending.count { it.result == "win" }
        val check = pending.count { it.needsCheck }
        summaryTv.text = "未書き出し ${pending.size}件（${w}勝${pending.size - w}敗）" +
            (if (check > 0) "\n⚠ 要確認 ${check}件 — タップして直してください" else "")

        listBox.removeAllViews()
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
        all.take(150).forEach { m -> listBox.addView(row(m, fmt)) }
        if (all.isEmpty()) listBox.addView(tv("まだ記録はありません", 12f, sub).apply { setPadding(0, dp(8), 0, 0) })

        val known = Store.knownIconClasses().joinToString("・") { Classes.name(it) }
        (diagTv.parent as LinearLayout).findViewWithTag<TextView>("icons")?.text = "クラスの絵を覚えている：$known"
    }

    private fun row(m: Match, fmt: SimpleDateFormat): View {
        val res = if (m.result == "win") "勝" else "負"
        val line1 = "${fmt.format(Date(m.ts))}  $res  ${Analyzer.orderJa(m.order)}  " +
            "${Classes.name(m.myClass)} vs ${Classes.name(m.oppClass)}"
        val extra = listOfNotNull(
            m.oppName.takeIf { it.isNotBlank() },
            m.oppType.takeIf { it.isNotBlank() },
            if (m.exported) "書き出し済" else null,
        ).joinToString(" · ")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (m.needsCheck) "#3a2a14" else "#262634"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), if (m.result == "win") winC else lossC)
            }
            addView(tv((if (m.needsCheck) "⚠ " else "") + line1, 13f, textC, true))
            if (extra.isNotEmpty()) addView(tv(extra, 11f, sub))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { editMenu(m.id) }
            alpha = if (m.exported) 0.55f else 1f
        }
    }

    // ---------- 開始・停止 ----------

    private fun toggle() {
        if (Store.running) {
            stopService(Intent(this, CaptureService::class.java))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
                Toast.makeText(this, "記録を開始しました。シャドバを開いてください", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "画面の記録が許可されませんでした", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == reqImportTypes && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val rows = parseCsv(text)
                val header = rows.firstOrNull()?.map { it.trim() } ?: emptyList()
                val ci = header.indexOf("相手のクラス")
                val ti = header.indexOf("相手のデッキタイプ")
                if (ci < 0 || ti < 0) {
                    Toast.makeText(this, "SVTで出力したCSVを選んでください", Toast.LENGTH_LONG).show()
                    return
                }
                val nameToId = Classes.names.entries.associate { (k, v) -> v to k }
                val pairs = rows.drop(1).mapNotNull { r ->
                    val c = nameToId[r.getOrNull(ci)?.trim()] ?: return@mapNotNull null
                    val t = r.getOrNull(ti)?.trim().orEmpty()
                    if (t.isEmpty()) null else c to t
                }
                val n = Store.importTypes(pairs)
                Toast.makeText(this, "${pairs.size}試合分を読み込みました（候補 ${n}種類）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "読み込みに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == reqExport && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(exportCsv.toByteArray(Charsets.UTF_8)) }
                Store.markExported(exportIds)
                Toast.makeText(this, "${exportIds.size}件を保存しました。SVTで取り込んでください", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 書き出し（SVTのCSV形式） ----------

    private fun startExport() {
        val list = Store.snapshot().filter { !it.exported }
        if (list.isEmpty()) {
            Toast.makeText(this, "書き出していない記録はありません", Toast.LENGTH_SHORT).show()
            return
        }
        val check = list.count { it.needsCheck }
        if (check > 0) {
            AlertDialog.Builder(this)
                .setTitle("要確認が${check}件あります")
                .setMessage("読み取りに自信がない記録です。直してから書き出すのがおすすめです。\n（クラス不明の記録はSVT側で取り込めません）")
                .setPositiveButton("このまま書き出す") { _, _ -> doExport(list) }
                .setNegativeButton("先に直す", null)
                .show()
        } else {
            doExport(list)
        }
    }

    private fun doExport(list: List<Match>) {
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN)
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val header = listOf("日時", "シリーズ", "結果", "先攻後攻", "自分のデッキ", "自分のクラス", "相手のクラス", "相手のデッキタイプ")
        val rows = list.sortedBy { it.ts }.map { m ->
            listOf(
                tf.format(Date(m.ts)),
                m.series.ifBlank { Store.series },
                if (m.result == "win") "勝利" else "敗北",
                if (m.order == "first") "先攻" else "後攻",
                m.deck.ifBlank { Store.deck },
                m.myClass?.let { Classes.name(it) } ?: "",
                m.oppClass?.let { Classes.name(it) } ?: "",
                m.oppType,
            ).joinToString(",") { esc(it) }
        }
        exportCsv = "﻿" + (listOf(header.joinToString(",") { esc(it) }) + rows).joinToString("\r\n")
        exportIds = list.map { it.id }.toSet()
        val name = "sv_auto_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date()) + ".csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqExport)
    }

    private fun confirmRemoveExported() {
        AlertDialog.Builder(this)
            .setMessage("書き出し済みの記録をこのアプリから消しますか？\n「戦績」タブの集計からも消えます。（SVT側のデータは消えません）")
            .setPositiveButton("消す") { _, _ ->
                val n = Store.removeExported()
                Toast.makeText(this, "${n}件を消しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ---------- 記録の修正 ----------

    private fun editMenu(id: String) {
        val m = Store.snapshot().find { it.id == id } ?: return
        val items = mutableListOf(
            "勝敗を「${if (m.result == "win") "敗北" else "勝利"}」に変える",
            "先攻後攻：${Analyzer.orderJa(m.order)} → 変更",
            "自分のクラス：${Classes.name(m.myClass)} → 変更",
            "相手のクラス：${Classes.name(m.oppClass)} → 変更",
            "相手のデッキタイプ：${m.oppType.ifBlank { "未入力" }}",
            "自分のデッキ名：${m.deckLabel()}",
            "シリーズ：${m.series.ifBlank { "未設定" }}",
        )
        val thumb = Store.thumbFile(m.id)
        if (thumb.exists()) items.add(0, "開始画面を見る（先攻・後攻の確認）")
        if (m.evidence.isNotBlank()) items.add(0, "判定の詳細を見る")
        if (m.needsCheck) items.add("確認済みにする（⚠を外す）")
        items.add("この記録を削除")
        AlertDialog.Builder(this)
            .setTitle(if (m.evidence.isNotBlank()) m.evidence.lineSequence().first() else null)
            .setItems(items.toTypedArray()) { _, which ->
                val base = (if (thumb.exists()) 1 else 0) + (if (m.evidence.isNotBlank()) 1 else 0)
                when (if (which >= base && which - base <= 6) which - base else -1) {
                    -1 -> when (items[which]) {
                        "開始画面を見る（先攻・後攻の確認）" -> showThumb(id)
                        "判定の詳細を見る" -> showText("判定の詳細", m.evidence)
                        "確認済みにする（⚠を外す）" -> Store.update(id) { it.needsCheck = false }
                        "この記録を削除" -> AlertDialog.Builder(this)
                            .setMessage("削除しますか？")
                            .setPositiveButton("削除") { _, _ -> Store.delete(id) }
                            .setNegativeButton("やめる", null).show()
                    }
                    0 -> Store.update(id) { it.result = if (it.result == "win") "loss" else "win" }
                    1 -> chooseOrder(id)
                    2 -> chooseClass("自分のクラス") { c ->
                        Store.update(id) { it.myClass = c; recheck(it) }
                        Vision.decode(m.myIcon)?.let { Store.learnIcon(c, it, force = true) }
                    }
                    3 -> chooseClass("相手のクラス") { c ->
                        Store.update(id) { it.oppClass = c; recheck(it) }
                        Vision.decode(m.oppIcon)?.let { Store.learnIcon(c, it, force = true) }
                    }
                    4 -> chooseOppType(m)
                    5 -> inputText("自分のデッキ名", m.deck, "空ならクラス名") { v -> Store.update(id) { it.deck = v } }
                    6 -> inputText("シリーズ", m.series, "SVTのシリーズ名") { v -> Store.update(id) { it.series = v } }
                }
            }
            .show()
    }

    /** 長い文章をスクロールで表示し、LINEやメール等へ共有もできる */
    private fun showText(title: String, body: String) {
        val t = TextView(this).apply {
            text = body; textSize = 11f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val sv = ScrollView(this).apply { addView(t) }
        AlertDialog.Builder(this).setTitle(title).setView(sv)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("共有") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "【$title】\n$body")
                startActivity(Intent.createChooser(i, "共有"))
            }
            .show()
    }

    private fun showThumb(id: String) {
        val bmp = BitmapFactory.decodeFile(Store.thumbFile(id).absolutePath) ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val m = Store.snapshot().find { it.id == id }
        AlertDialog.Builder(this)
            .setTitle("開始画面（左が自分）")
            .setView(iv)
            .setPositiveButton("先攻だった") { _, _ -> Store.update(id) { it.order = "first"; recheck(it) } }
            .setNegativeButton("後攻だった") { _, _ -> Store.update(id) { it.order = "second"; recheck(it) } }
            .setNeutralButton("閉じる", null)
            .setMessage("記録：${Analyzer.orderJa(m?.order)}")
            .show()
    }

    private fun recheck(m: Match) {
        m.needsCheck = m.order == null || m.myClass == null || m.oppClass == null
    }

    private fun chooseOrder(id: String) {
        val opts = arrayOf("先攻", "後攻")
        AlertDialog.Builder(this).setTitle("先攻後攻").setItems(opts) { _, w ->
            Store.update(id) { it.order = if (w == 0) "first" else "second"; recheck(it) }
        }.show()
    }

    private fun chooseClass(title: String, done: (String) -> Unit) {
        val names = Classes.ids.map { Classes.name(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title).setItems(names) { _, w -> done(Classes.ids[w]) }.show()
    }

    private fun chooseDefaultClass() {
        val names = (Classes.ids.map { Classes.name(it) } + "なし").toTypedArray()
        AlertDialog.Builder(this).setTitle("クラスが読めない時の自分のクラス").setItems(names) { _, w ->
            Store.defaultMyClass = if (w < Classes.ids.size) Classes.ids[w] else null
            refresh()
        }.show()
    }

    private fun inputText(title: String, cur: String, hint: String, save: (String) -> Unit) {
        val e = EditText(this).apply { setText(cur); this.hint = hint }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("保存") { _, _ -> save(e.text.toString().trim()) }
            .setNegativeButton("やめる", null).show()
    }

    /** 相手のデッキタイプを選択式で。候補はそのクラスで過去に使った名前（多い順） */
    private fun chooseOppType(m: Match) {
        val cands = Store.typeCandidates(m.oppClass).take(30)
        val items = cands + listOf("＋ 新しく入力", "（未入力に戻す）")
        val title = "相手のデッキタイプ（${Classes.name(m.oppClass)}）"
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, w ->
                when {
                    w < cands.size -> Store.update(m.id) { it.oppType = cands[w] }
                    w == cands.size -> {
                        val e = EditText(this).apply { setText(m.oppType); hint = "例：財宝ネメシス" }
                        AlertDialog.Builder(this).setTitle(title).setView(e)
                            .setPositiveButton("保存") { _, _ -> Store.update(m.id) { it.oppType = e.text.toString().trim() } }
                            .setNegativeButton("やめる", null).show()
                    }
                    else -> Store.update(m.id) { it.oppType = "" }
                }
            }
            .show()
    }

    private fun startImportTypes() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqImportTypes)
    }

    private fun parseCsv(src: String): List<List<String>> {
        val text = src.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (q) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else q = false
                } else cur.append(ch)
            } else when (ch) {
                '"' -> q = true
                ',' -> { row.add(cur.toString()); cur.setLength(0) }
                '\n', '\r' -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cur.toString()); cur.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cur.append(ch)
            }
            i++
        }
        row.add(cur.toString())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }
}
