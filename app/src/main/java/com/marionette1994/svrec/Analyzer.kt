package com.marionette1994.svrec

import android.graphics.Bitmap

/** OCRで読めた1行（位置はゲーム画面に対する割合） */
data class Line(val text: String, val cx: Float, val cy: Float)

enum class Kind { VS, RESULT, BATTLE, NONE }

class Obs(
    val kind: Kind,
    val order: String? = null,
    val orderByGlyph: Boolean = false,
    val glyphStrength: Float = 0f,
    val myClass: String? = null,
    val myByIcon: Boolean = false,
    val oppClass: String? = null,
    val oppByIcon: Boolean = false,
    val myIcon: FloatArray? = null,
    val oppIcon: FloatArray? = null,
    val oppName: String? = null,
    val outcome: String? = null,
    val confident: Boolean = false,
    val debug: String = "",
    val extraPP: Boolean? = null,   // BATTLE: true=自分にエクストラPPあり（後攻）/ false=無し（先攻）/ null=判断できず
    val extraByText: Boolean = false, // 「PP+1」の文字まで読めた（色だけより確か）
)

object Analyzer {
    private val RESULT_WORDS = listOf("TOTAL", "再戦", "ボーナス", "累積", "NEXT", "MASTER", "ランク")

    /**
     * OCRなしの高速判定（0.2秒ごと）。カードが出揃ったVS画面なら先後とアイコンを返す。
     * それ以外は null（=このフレームでは何も言えない）。
     */
    fun fast(f: Frame, g: Vision.GlyphResult): Obs? {
        if (g.order == null) return null
        return vsObs(f, g, g.order, emptyList(), "VS（画像照合）")
    }

    /** OCR付きの判定（約1秒ごと） */
    fun analyze(f: Frame, lines: List<Line>, g: Vision.GlyphResult): Obs {
        // ---- 対戦開始（VS）画面 ----
        if (g.order != null) return vsObs(f, g, g.order, lines, "VS（画像照合）")

        // 画像照合で決まらないときだけ、文字を補助的に使う（演出途中の誤読を避けるため、左右とも読めた場合のみ）
        var ocrLeft: String? = null
        var ocrRight: String? = null
        for (l in lines) {
            if (l.cy !in 0.3f..0.7f) continue
            val o = when {
                l.text.contains("先攻") || l.text == "先" -> "first"
                l.text.contains("後攻") || l.text == "後" -> "second"
                else -> null
            } ?: continue
            if (l.cx in 0.2f..0.42f) ocrLeft = o else if (l.cx in 0.58f..0.8f) ocrRight = o
        }
        val groupL = lines.any { it.text.contains("グループ") && it.cx < 0.5f && it.cy > 0.78f }
        val groupR = lines.any { it.text.contains("グループ") && it.cx > 0.5f && it.cy > 0.78f }
        val ocrOrder = if (ocrLeft != null && ocrRight != null && ocrLeft != ocrRight) ocrLeft else null
        if (ocrOrder != null || (groupL && groupR)) {
            return vsObs(f, g, ocrOrder, lines, if (ocrOrder != null) "VS（文字）" else "VS（先後まだ）")
        }

        // ---- リザルト画面 ----
        val up = lines.map { it.copy(text = it.text.uppercase().replace(" ", "").replace("　", "")) }
        val hits = RESULT_WORDS.count { k -> up.any { it.text.contains(k) } }
        val (lum, rb) = Vision.topStats(f)
        if (hits >= 2) {
            val top = up.filter { it.cy < 0.24f && it.cx in 0.28f..0.72f }
            val textWin = top.any { it.text.contains("WIN") || it.text.contains("W1N") || it.text.contains("VVIN") } ||
                up.any { it.text.contains("勝利") }
            val textLoss = top.any { t -> listOf("RESULT", "RESUL", "ESULT", "SULT").any { t.text.contains(it) } }
            val colorWin = lum >= 115f && rb >= 45f
            val colorLoss = lum <= 100f
            val outcome: String
            val confident: Boolean
            when {
                textWin && !textLoss -> { outcome = "win"; confident = !colorLoss }
                textLoss && !textWin -> { outcome = "loss"; confident = !colorWin }
                colorWin -> { outcome = "win"; confident = true }
                colorLoss -> { outcome = "loss"; confident = true }
                else -> { outcome = if (lum >= 108f) "win" else "loss"; confident = false }
            }
            val dbg = "RESULT ${if (outcome == "win") "勝利" else "敗北"}${if (confident) "" else "（自信なし）"} | " +
                "文字 勝:$textWin 負:$textLoss | 明るさ${lum.toInt()} 赤み${rb.toInt()} | 語数$hits"
            return Obs(Kind.RESULT, outcome = outcome, confident = confident, debug = dbg)
        }

        // ---- 対戦中：後攻だけに出る柿色の「PP+1」ボタンで先後を見る ----
        val inBattle = lines.any { it.cx in 0.76f..0.92f && it.cy in 0.52f..0.68f && it.text.uppercase().contains("PP") } ||
            lines.any { it.cx in 0.76f..0.92f && it.cy in 0.3f..0.55f && it.text.contains("ターン") }
        if (inBattle) {
            val orange = Vision.orangeFrac(f)
            val btnText = lines.any { l ->
                val t = l.text.uppercase().replace(" ", "")
                l.cx in 0.76f..0.93f && l.cy in 0.65f..0.8f &&
                    (t.contains("PP+1") || t.contains("PP十1") || t.contains("PP＋1") || t.contains("再使用"))
            }
            val extra: Boolean? = when {
                btnText -> true
                orange >= 0.45f -> true
                orange < 0.08f -> false
                else -> null
            }
            val dbg = "対戦中 | エクストラPP:${when (extra) { true -> "あり→後攻"; false -> "なし→先攻"; null -> "不明" }}" +
                " | 柿色${(orange * 100).toInt()}% 文字:$btnText"
            return Obs(Kind.BATTLE, extraPP = extra, extraByText = btnText, debug = dbg)
        }

        val sample = lines.take(6).joinToString(" / ") { it.text }
        return Obs(Kind.NONE, debug = "—（対象外の画面）| ${g.info} | 明るさ${lum.toInt()} | 読取: $sample")
    }

    private fun vsObs(f: Frame, g: Vision.GlyphResult, order: String?, lines: List<Line>, label: String): Obs {
        val myVec = Vision.icon(f, Vision.IL, 0)
        val oppVec = Vision.icon(f, Vision.IR, 0)
        val (myIc, myS) = Vision.classifyIcon(Vision.icon(f, Vision.IL, Vision.IP))
        val (oppIc, oppS) = Vision.classifyIcon(Vision.icon(f, Vision.IR, Vision.IP))
        val zone = lines.filter { it.cy in 0.6f..0.79f }.sortedBy { it.cy }
        val myOcr = zone.filter { it.cx < 0.45f }.firstNotNullOfOrNull { Classes.fromText(it.text) }
        val oppOcr = zone.filter { it.cx > 0.55f }.firstNotNullOfOrNull { Classes.fromText(it.text) }
        val name = lines.filter { it.cy in 0.78f..0.865f && it.cx in 0.6f..0.93f }
            .sortedByDescending { it.cx }
            .map { it.text.trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("ランク") && !it.contains("グループ") && !it.contains("MP") }
        val dbg = "$label ${orderJa(order)} | ${g.info}\n" +
            "自分 icon:${Classes.name(myIc)}(${f2(myS)}) 文字:${Classes.name(myOcr)}\n" +
            "相手 icon:${Classes.name(oppIc)}(${f2(oppS)}) 文字:${Classes.name(oppOcr)} 名前:${name ?: "-"}"
        return Obs(
            Kind.VS, order = order,
            orderByGlyph = g.order != null, glyphStrength = g.strength,
            myClass = myIc ?: myOcr, myByIcon = myIc != null,
            oppClass = oppIc ?: oppOcr, oppByIcon = oppIc != null,
            myIcon = myVec, oppIcon = oppVec, oppName = name, debug = dbg,
        )
    }

    private fun f2(v: Float) = "%.2f".format(v)

    fun orderJa(o: String?) = when (o) { "first" -> "先攻"; "second" -> "後攻"; else -> "先後？" }
}

/** 画面の流れ（VS → 対戦 → リザルト）から1試合分の記録を組み立てる */
class Recorder(private val onRecord: (Match, Bitmap?) -> Unit) {
    private class Pending(var lastSeen: Long) {
        val glyphOrder = HashMap<String, Int>()   // 画像照合での先後の票（信頼度高）
        val ocrOrder = HashMap<String, Int>()     // 文字での先後の票（補助）
        val my = HashMap<String, Int>()
        val opp = HashMap<String, Int>()
        var myIconVotes = 0
        var oppIconVotes = 0
        var myIcon: FloatArray? = null
        var oppIcon: FloatArray? = null
        var oppName = ""
        var thumb: Bitmap? = null
        var thumbStrength = 0f
        var extraYes = 0      // 対戦中に自分側のPP+1ボタンを見た回数（後攻の証拠）
        var extraNo = 0       // 対戦中なのにボタンが無かった回数（先攻の証拠）
        val hasBattle get() = extraYes + extraNo > 0

        fun add(o: Obs, now: Long) {
            lastSeen = now
            o.order?.let { (if (o.orderByGlyph) glyphOrder else ocrOrder).merge(it, 1, Int::plus) }
            o.myClass?.let { my.merge(it, if (o.myByIcon) 2 else 1, Int::plus) }
            o.oppClass?.let { opp.merge(it, if (o.oppByIcon) 2 else 1, Int::plus) }
            if (o.myByIcon) myIconVotes++
            if (o.oppByIcon) oppIconVotes++
            // カードが出揃ったフレームのアイコンを優先して保存
            if (o.myIcon != null && (myIcon == null || o.orderByGlyph)) myIcon = o.myIcon
            if (o.oppIcon != null && (oppIcon == null || o.orderByGlyph)) oppIcon = o.oppIcon
            if (!o.oppName.isNullOrBlank()) oppName = o.oppName
        }

        /** エクストラPPから見た先後（十分な証拠がなければ null） */
        fun extraOrder(): String? = when {
            extraYes >= 3 && extraYes * 2 >= extraNo -> "second"
            extraNo >= 6 && extraYes == 0 -> "first"
            else -> null
        }

        /** 先後の結論と、それに自信があるか（VS画面の照合 と 対戦中のエクストラPP の2つで確かめる） */
        fun decideOrder(): Pair<String?, Boolean> {
            var vs: String? = null
            var vsSure = false
            if (glyphOrder.isNotEmpty()) {
                val best = glyphOrder.maxByOrNull { it.value }!!
                val other = glyphOrder.filterKeys { it != best.key }.values.sum()
                vs = best.key
                vsSure = best.value >= 2 && other * 4 <= best.value
            } else {
                vs = ocrOrder.maxByOrNull { it.value }?.key
            }
            val ex = extraOrder()
            return when {
                ex != null && vs != null && ex == vs -> Pair(vs, true)          // 2つが一致 → 確定
                // 食い違い → 「PP+1」の文字が何度も読めていればそちらを、そうでなければVS画面を採用して要確認
                ex != null && vs != null -> Pair(if (ex == "second" && extraYes >= 4) ex else vs, false)
                ex != null -> Pair(ex, extraYes >= 4 || extraNo >= 10)          // VS画面を取り逃した
                else -> Pair(vs, vsSure)
            }
        }

        fun voteText(): String {
            val gf = glyphOrder["first"] ?: 0; val gs = glyphOrder["second"] ?: 0
            val of = ocrOrder["first"] ?: 0; val os = ocrOrder["second"] ?: 0
            return "先後の票 画像:先$gf/後$gs 文字:先$of/後$os PP+1:あり$extraYes/なし$extraNo"
        }
    }

    private var pending: Pending? = null
    private var latched = false          // リザルトを記録済み（次のVSまで重複させない）
    private var lastResultSeen = 0L
    private var hold: Obs? = null        // 勝敗に自信がないとき、少し待つ
    private var holdSince = 0L

    /** いま集計中の試合の先後の票（診断表示用） */
    fun voteText(): String = pending?.voteText() ?: ""

    fun feed(o: Obs, now: Long) {
        when (o.kind) {
            Kind.VS -> {
                hold?.let { commit(it, now) }
                latched = false
                // VS画面は数秒で終わる。15秒以上あいたら、または対戦が始まった後なら別の試合として数え直す
                val p = pending?.takeIf { now - it.lastSeen < 15_000 && !it.hasBattle }
                    ?: Pending(now).also { pending?.thumb?.recycle(); pending = it }
                p.add(o, now)
            }
            Kind.RESULT -> {
                if (latched) { lastResultSeen = now; return }
                if (!o.confident && (hold == null || now - holdSince < 3000)) {
                    if (hold == null) { hold = o; holdSince = now }
                    return
                }
                commit(o, now)
            }
            Kind.BATTLE -> {
                hold?.let { commit(it, now) }
                latched = false                       // 対戦中＝前のリザルトは終わっている
                val p = pending ?: Pending(now).also { pending = it }
                p.lastSeen = now
                when (o.extraPP) {
                    true -> p.extraYes += if (o.extraByText) 2 else 1
                    false -> p.extraNo++
                    null -> {}
                }
            }
            Kind.NONE -> {
                hold?.let { commit(it, now) }
                if (latched && now - lastResultSeen > 90_000) latched = false
            }
        }
    }

    /** この強さのVSフレームを、記録に添える開始画面として保存したいか */
    fun wantsThumb(strength: Float): Boolean {
        val p = pending ?: return false
        return strength > p.thumbStrength + 0.01f
    }

    fun setThumb(b: Bitmap, strength: Float) {
        val p = pending ?: run { b.recycle(); return }
        p.thumb?.recycle()
        p.thumb = b
        p.thumbStrength = strength
    }

    private fun commit(o: Obs, now: Long) {
        val p = pending
        val (order, orderSure) = p?.decideOrder() ?: Pair(null, false)
        val my = p?.my?.maxByOrNull { it.value }?.key ?: Store.defaultMyClass
        val opp = p?.opp?.maxByOrNull { it.value }?.key
        val m = Match(
            id = "a$now", ts = now,
            result = o.outcome ?: "loss",
            order = order, myClass = my, oppClass = opp,
            oppName = p?.oppName ?: "",
            needsCheck = p == null || !o.confident || !orderSure || my == null || opp == null,
            myIcon = p?.myIcon?.let { Vision.encode(it) },
            oppIcon = p?.oppIcon?.let { Vision.encode(it) },
            series = Store.series,
            deck = Store.deck,
        )
        // 文字で分かったクラスのアイコンを覚えて、次から絵だけで判定できるようにする
        if (p != null) {
            if (p.myIconVotes == 0 && my != null && p.my.containsKey(my)) p.myIcon?.let { Store.learnIcon(my, it) }
            if (p.oppIconVotes == 0 && opp != null && p.opp.containsKey(opp)) p.oppIcon?.let { Store.learnIcon(opp, it) }
        }
        val thumb = p?.thumb
        pending = null
        hold = null
        latched = true
        lastResultSeen = now
        onRecord(m, thumb)
    }
}
