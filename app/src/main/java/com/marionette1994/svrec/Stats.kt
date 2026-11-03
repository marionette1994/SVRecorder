package com.marionette1994.svrec

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 戦績タブの絞り込み状態 */
class StatsState {
    var series: String? = null          // null = すべて
    var period = 0                      // 0=すべて / 1=今日 / 7 / 30（日）
    val open = mutableSetOf<String>()   // 開いている明細
}

private fun pct(w: Int, n: Int): Int? = if (n == 0) null else Math.round(w * 100f / n)

private fun wrColor(p: Int?): Int = when {
    p == null -> Color.parseColor("#555555")
    p >= 60 -> Color.parseColor("#4caf7a")
    p >= 50 -> Color.parseColor("#c9a84c")
    p >= 40 -> Color.parseColor("#d48a3a")
    else -> Color.parseColor("#c0392b")
}

private fun List<Match>.wins() = count { it.result == "win" }

private fun startOfDay(daysAgo: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.DAY_OF_YEAR, -daysAgo)
    return c.timeInMillis
}

/** 戦績タブを box に描く（SVTの統計画面と同じ観点：全体・直近・先後・相手クラス・デッキ・日別） */
internal fun MainActivity.renderStats(box: LinearLayout, all: List<Match>, st: StatsState, rerender: () -> Unit) {
    box.removeAllViews()

    // ---- 絞り込み ----
    val seriesList = all.map { it.series }.distinct()
    box.addView(tv("シリーズ", 11f, sub))
    box.addView(chips(
        listOf<Pair<String, String?>>("すべて" to null) + seriesList.map { (it.ifBlank { "未設定" }) to it },
        st.series,
    ) { st.series = it; rerender() })
    box.addView(tv("期間", 11f, sub).apply { setPadding(0, dp(8), 0, 0) })
    box.addView(chips(
        listOf<Pair<String, Int?>>("すべて" to 0, "今日" to 1, "7日" to 7, "30日" to 30),
        st.period,
    ) { st.period = it ?: 0; rerender() })

    var ms = all
    st.series?.let { s -> ms = ms.filter { it.series == s } }
    if (st.period > 0) {
        val from = startOfDay(st.period - 1)
        ms = ms.filter { it.ts >= from }
    }
    if (ms.isEmpty()) {
        box.addView(tv("この条件の記録はまだありません", 13f, sub).apply { setPadding(0, dp(24), 0, 0) })
        return
    }

    // ---- 全体 ----
    val w = ms.wins(); val n = ms.size; val p = pct(w, n)
    val top = section("全体", box)
    top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(tv(p?.let { "$it%" } ?: "—", 34f, wrColor(p), true))
        addView(LinearLayout(this@renderStats).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(tv("${n}戦  ${w}勝 ${n - w}敗", 15f, textC, true))
            addView(tv(streakText(ms), 12f, sub))
        })
    })
    top.addView(bar(p))
    val first = ms.filter { it.order == "first" }
    val second = ms.filter { it.order == "second" }
    top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        addView(miniBox("先攻", first), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        addView(miniBox("後攻", second), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    })

    // ---- 直近 ----
    val recent = section("直近の勝率", box)
    recent.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(5, 10, 20).forEachIndexed { i, k ->
            addView(miniBox("直近${k}戦", ms.take(k)), LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
    })

    // ---- 相手クラス別 ----
    val opp = section("相手クラス別（タップで先後・デッキタイプ）", box)
    ms.groupBy { it.oppClass }.entries.sortedByDescending { it.value.size }.forEach { (cls, list) ->
        val key = "opp:$cls"
        opp.addView(statRow(Classes.name(cls), list, key in st.open) { toggleOpen(st, key, rerender) })
        if (key in st.open) {
            val box2 = detailBox()
            box2.addView(subRow("先攻", list.filter { it.order == "first" }))
            box2.addView(subRow("後攻", list.filter { it.order == "second" }))
            val types = list.filter { it.oppType.isNotBlank() }.groupBy { it.oppType }
            types.entries.sortedByDescending { it.value.size }.forEach { (t, l) -> box2.addView(subRow("・$t", l)) }
            if (types.isEmpty()) box2.addView(tv("デッキタイプは記録をタップして入力できます", 11f, sub))
            opp.addView(box2)
        }
    }

    // ---- 自分のデッキ別 ----
    val deck = section("自分のデッキ別（タップで相手クラス別）", box)
    ms.groupBy { it.deckLabel() }.entries.sortedByDescending { it.value.size }.forEach { (name, list) ->
        val key = "deck:$name"
        deck.addView(statRow(name, list, key in st.open) { toggleOpen(st, key, rerender) })
        if (key in st.open) {
            val box2 = detailBox()
            box2.addView(subRow("先攻", list.filter { it.order == "first" }))
            box2.addView(subRow("後攻", list.filter { it.order == "second" }))
            list.groupBy { it.oppClass }.entries.sortedByDescending { it.value.size }
                .forEach { (c, l) -> box2.addView(subRow("vs ${Classes.name(c)}", l)) }
            deck.addView(box2)
        }
    }

    // ---- 日別 ----
    val daily = section("日別", box)
    val df = SimpleDateFormat("M/d（E）", Locale.JAPAN)
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
    ms.groupBy { dayKey.format(Date(it.ts)) }.entries.sortedByDescending { it.key }.take(14).forEach { (_, list) ->
        daily.addView(statRow(df.format(Date(list.first().ts)), list, null, null))
    }

    val unchecked = ms.count { it.needsCheck }
    if (unchecked > 0) {
        box.addView(tv("⚠ 要確認の記録が${unchecked}件含まれています（「記録」タブで直せます）", 11f, Color.parseColor("#d48a3a"))
            .apply { setPadding(0, dp(12), 0, 0) })
    }
}

private fun toggleOpen(st: StatsState, key: String, rerender: () -> Unit) {
    if (!st.open.remove(key)) st.open.add(key)
    rerender()
}

private fun streakText(ms: List<Match>): String {
    if (ms.isEmpty()) return ""
    val r = ms.first().result
    val k = ms.takeWhile { it.result == r }.size
    return if (r == "win") "現在 ${k}連勝中" else "現在 ${k}連敗中"
}

private fun <T> MainActivity.chips(opts: List<Pair<String, T?>>, cur: T?, onPick: (T?) -> Unit): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    opts.forEach { (label, v) ->
        val sel = v == cur
        row.addView(TextView(this).apply {
            text = label; textSize = 13f
            setTextColor(if (sel) Color.parseColor("#111111") else textC)
            if (sel) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(if (sel) gold else Color.parseColor("#2a2a38"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { onPick(v) }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
}

private fun MainActivity.bar(p: Int?): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) }
    background = GradientDrawable().apply { setColor(Color.parseColor("#2e2e3c")); cornerRadius = dp(3).toFloat() }
    val v = p ?: 0
    addView(View(this@bar).apply {
        background = GradientDrawable().apply { setColor(wrColor(p)); cornerRadius = dp(3).toFloat() }
    }, LinearLayout.LayoutParams(0, -1, v.toFloat()))
    addView(View(this@bar), LinearLayout.LayoutParams(0, -1, (100 - v).toFloat()))
}

private fun MainActivity.miniBox(label: String, list: List<Match>): View {
    val w = list.wins(); val n = list.size; val p = pct(w, n)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = GradientDrawable().apply { setColor(Color.parseColor("#262634")); cornerRadius = dp(8).toFloat() }
        addView(tv(label, 11f, sub))
        addView(tv(p?.let { "$it%" } ?: "—", 20f, wrColor(p), true))
        addView(tv(if (n == 0) "記録なし" else "${w}勝${n - w}敗", 11f, sub))
    }
}

private fun MainActivity.statRow(label: String, list: List<Match>, opened: Boolean?, onTap: (() -> Unit)?): View {
    val w = list.wins(); val n = list.size; val p = pct(w, n)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        addView(LinearLayout(this@statRow).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val mark = when (opened) { true -> "▾ "; false -> "▸ "; null -> "" }
            addView(tv(mark + label, 14f, textC, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv("${w}勝${n - w}敗  ", 12f, sub))
            addView(tv(p?.let { "$it%" } ?: "—", 15f, wrColor(p), true))
        })
        addView(bar(p))
        if (onTap != null) setOnClickListener { onTap() }
    }
}

private fun MainActivity.subRow(label: String, list: List<Match>): View {
    val w = list.wins(); val n = list.size; val p = pct(w, n)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        addView(tv(label, 12f, textC), LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv(if (n == 0) "—" else "${w}勝${n - w}敗  ", 11f, sub))
        addView(tv(p?.let { "$it%" } ?: "", 12f, wrColor(p), true))
    }
}

private fun MainActivity.detailBox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(12), dp(6), dp(10), dp(8))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
    background = GradientDrawable().apply { setColor(Color.parseColor("#262634")); cornerRadius = dp(8).toFloat() }
}
