package com.marionette1994.svrec

/** OCRで読めた1行（位置はゲーム画面に対する割合） */
data class Line(val text: String, val cx: Float, val cy: Float)

enum class Kind { VS, RESULT, NONE }

class Obs(
    val kind: Kind,
    val order: String? = null,
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
)

object Analyzer {
    private val RESULT_WORDS = listOf("TOTAL", "再戦", "ボーナス", "累積", "NEXT", "MASTER", "ランク")

    fun analyze(f: Frame, lines: List<Line>): Obs {
        val g = Vision.glyphOrder(f)

        // ---- 対戦開始（VS）画面 ----
        var ocrLeft: String? = null
        var ocrRight: String? = null
        for (l in lines) {
            if (l.cy !in 0.28f..0.72f) continue
            val o = when {
                l.text.contains('先') -> "first"
                l.text.contains('後') -> "second"
                else -> null
            } ?: continue
            if (l.cx in 0.18f..0.44f) ocrLeft = o else if (l.cx in 0.56f..0.82f) ocrRight = o
        }
        val groupL = lines.any { it.text.contains("グループ") && it.cx < 0.5f && it.cy > 0.78f }
        val groupR = lines.any { it.text.contains("グループ") && it.cx > 0.5f && it.cy > 0.78f }
        val ocrOrder = when {
            ocrLeft != null && ocrRight != null && ocrLeft != ocrRight -> ocrLeft
            ocrLeft != null && groupL && groupR -> ocrLeft
            ocrRight != null && groupL && groupR -> if (ocrRight == "first") "second" else "first"
            else -> null
        }
        val order = g.order ?: ocrOrder
        if (order != null || (groupL && groupR)) {
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
            val dbg = "VS ${orderJa(order)} | ${g.info}\n" +
                "自分 icon:${Classes.name(myIc)}(${f2(myS)}) 文字:${Classes.name(myOcr)}\n" +
                "相手 icon:${Classes.name(oppIc)}(${f2(oppS)}) 文字:${Classes.name(oppOcr)} 名前:${name ?: "-"}"
            return Obs(
                Kind.VS, order = order,
                myClass = myIc ?: myOcr, myByIcon = myIc != null,
                oppClass = oppIc ?: oppOcr, oppByIcon = oppIc != null,
                myIcon = myVec, oppIcon = oppVec, oppName = name, debug = dbg,
            )
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

        val sample = lines.take(6).joinToString(" / ") { it.text }
        return Obs(Kind.NONE, debug = "—（対象外の画面）| ${g.info} | 明るさ${lum.toInt()} | 読取: $sample")
    }

    private fun f2(v: Float) = "%.2f".format(v)

    fun orderJa(o: String?) = when (o) { "first" -> "先攻"; "second" -> "後攻"; else -> "先後？" }
}

/** 画面の流れ（VS → 対戦 → リザルト）から1試合分の記録を組み立てる */
class Recorder(private val onRecord: (Match) -> Unit) {
    private class Pending(var lastSeen: Long) {
        val order = HashMap<String, Int>()
        val my = HashMap<String, Int>()
        val opp = HashMap<String, Int>()
        var myIconVotes = 0
        var oppIconVotes = 0
        var myIcon: FloatArray? = null
        var oppIcon: FloatArray? = null
        var oppName = ""

        fun add(o: Obs, now: Long) {
            lastSeen = now
            o.order?.let { order.merge(it, 1, Int::plus) }
            o.myClass?.let { my.merge(it, if (o.myByIcon) 2 else 1, Int::plus) }
            o.oppClass?.let { opp.merge(it, if (o.oppByIcon) 2 else 1, Int::plus) }
            if (o.myByIcon) myIconVotes++
            if (o.oppByIcon) oppIconVotes++
            // カードが出揃ったフレームのアイコンを優先して保存
            if (o.myIcon != null && (myIcon == null || o.order != null)) myIcon = o.myIcon
            if (o.oppIcon != null && (oppIcon == null || o.order != null)) oppIcon = o.oppIcon
            if (!o.oppName.isNullOrBlank()) oppName = o.oppName
        }
    }

    private var pending: Pending? = null
    private var latched = false          // リザルトを記録済み（次のVSまで重複させない）
    private var lastResultSeen = 0L
    private var hold: Obs? = null        // 勝敗に自信がないとき、少し待つ
    private var holdSince = 0L

    fun feed(o: Obs, now: Long) {
        when (o.kind) {
            Kind.VS -> {
                hold?.let { commit(it, now) }
                latched = false
                val p = pending?.takeIf { now - it.lastSeen < 60_000 } ?: Pending(now).also { pending = it }
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
            Kind.NONE -> {
                hold?.let { commit(it, now) }
                if (latched && now - lastResultSeen > 90_000) latched = false
            }
        }
    }

    private fun commit(o: Obs, now: Long) {
        val p = pending
        val order = p?.order?.maxByOrNull { it.value }?.key
        val my = p?.my?.maxByOrNull { it.value }?.key ?: Store.defaultMyClass
        val opp = p?.opp?.maxByOrNull { it.value }?.key
        val m = Match(
            id = "a$now", ts = now,
            result = o.outcome ?: "loss",
            order = order, myClass = my, oppClass = opp,
            oppName = p?.oppName ?: "",
            needsCheck = p == null || !o.confident || order == null || my == null || opp == null,
            myIcon = p?.myIcon?.let { Vision.encode(it) },
            oppIcon = p?.oppIcon?.let { Vision.encode(it) },
        )
        // 文字で分かったクラスのアイコンを覚えて、次から絵だけで判定できるようにする
        if (p != null) {
            if (p.myIconVotes == 0 && my != null && p.my.containsKey(my)) p.myIcon?.let { Store.learnIcon(my, it) }
            if (p.oppIconVotes == 0 && opp != null && p.opp.containsKey(opp)) p.oppIcon?.let { Store.learnIcon(opp, it) }
        }
        pending = null
        hold = null
        latched = true
        lastResultSeen = now
        onRecord(m)
    }
}
