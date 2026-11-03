package com.marionette1994.svrec

import android.util.Base64
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** キャプチャした1フレーム（ARGBピクセル）。上下左右の真っ黒な帯は除いた範囲を「ゲーム画面」とする */
class Frame(val px: IntArray, val w: Int, val h: Int) {
    var cl = 0; var ct = 0; var cr = w; var cb = h

    init {
        fun lum(c: Int) = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        fun colBlack(x: Int): Boolean {
            for (k in 1..9) if (lum(px[min(h - 1, h * k / 10) * w + x]) > 4) return false
            return true
        }
        fun rowBlack(y: Int): Boolean {
            for (k in 1..9) if (lum(px[y * w + min(w - 1, w * k / 10)]) > 4) return false
            return true
        }
        var x = 0
        while (x < w / 5 && colBlack(x)) x++
        cl = x
        x = w - 1
        while (x > w * 4 / 5 && colBlack(x)) x--
        cr = x + 1
        var y = 0
        while (y < h / 5 && rowBlack(y)) y++
        ct = y
        y = h - 1
        while (y > h * 4 / 5 && rowBlack(y)) y--
        cb = y + 1
    }

    val cw get() = (cr - cl).toFloat()
    val ch get() = (cb - ct).toFloat()
}

object Vision {
    // 位置はゲーム画面に対する割合（実機スクショで計測）
    val GL = floatArrayOf(.235f, .37f, .335f, .58f)   // 左カードの「先/後」の字
    val GR = floatArrayOf(.615f, .37f, .715f, .58f)   // 右カードの「先/後」の字
    val IL = floatArrayOf(.11f, .61f, .185f, .77f)    // 自分のクラスアイコン
    val IR = floatArrayOf(.815f, .61f, .89f, .77f)    // 相手のクラスアイコン
    val TOP = floatArrayOf(.38f, .02f, .62f, .16f)    // WIN / RESULT の帯
    const val GN = 24; const val GP = 4
    const val IN = 16; const val IP = 2

    /** box を n×n（周囲に pad マス余分）に面積平均で縮小。戻り値は RGB 0..255 */
    fun area(f: Frame, b: FloatArray, n: Int, pad: Int): FloatArray {
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        val l = f.cl + (b[0] - bw * pad / n) * f.cw
        val t = f.ct + (b[1] - bh * pad / n) * f.ch
        val r = f.cl + (b[2] + bw * pad / n) * f.cw
        val btm = f.ct + (b[3] + bh * pad / n) * f.ch
        val m = n + 2 * pad
        val out = FloatArray(m * m * 3)
        for (ty in 0 until m) {
            val ya = floor(t + (btm - t) * ty / m).toInt()
            val yb = max(ya + 1, floor(t + (btm - t) * (ty + 1) / m).toInt())
            val y0 = max(0, ya); val y1 = min(f.h, yb)
            for (tx in 0 until m) {
                val xa = floor(l + (r - l) * tx / m).toInt()
                val xb = max(xa + 1, floor(l + (r - l) * (tx + 1) / m).toInt())
                val x0 = max(0, xa); val x1 = min(f.w, xb)
                var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    val row = y * f.w
                    while (x < x1) {
                        val c = f.px[row + x]
                        sr += (c shr 16 and 255); sg += (c shr 8 and 255); sb += (c and 255); cnt++
                        x++
                    }
                    y++
                }
                val i = (ty * m + tx) * 3
                if (cnt > 0) {
                    out[i] = sr.toFloat() / cnt; out[i + 1] = sg.toFloat() / cnt; out[i + 2] = sb.toFloat() / cnt
                }
            }
        }
        return out
    }

    /** 明るい文字だけを浮かせたマスク（0..1） */
    fun glyph(f: Frame, b: FloatArray, pad: Int): FloatArray {
        val a = area(f, b, GN, pad)
        val m = GN + 2 * pad
        return FloatArray(m * m) {
            val l = a[it * 3] * 0.299f + a[it * 3 + 1] * 0.587f + a[it * 3 + 2] * 0.114f
            ((l - 120f) / 100f).coerceIn(0f, 1f)
        }
    }

    fun icon(f: Frame, b: FloatArray, pad: Int): FloatArray {
        val a = area(f, b, IN, pad)
        for (i in a.indices) a[i] = a[i] / 255f
        return a
    }

    fun ncc(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var ma = 0f; var mb = 0f
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var ab = 0f; var aa = 0f; var bb = 0f
        for (i in 0 until n) {
            val x = a[i] - ma; val y = b[i] - mb
            ab += x * y; aa += x * x; bb += y * y
        }
        val d = sqrt(aa * bb)
        return if (d > 1e-6f) ab / d else 0f
    }

    /** m×m の領域内で n×n のテンプレートを少しずつずらし、一番よく一致した値 */
    fun best(region: FloatArray, m: Int, t: FloatArray, n: Int, c: Int): Float {
        val win = FloatArray(n * n * c)
        var best = -1f
        for (oy in 0..(m - n)) for (ox in 0..(m - n)) {
            var k = 0
            for (y in 0 until n) {
                val base = ((oy + y) * m + ox) * c
                for (i in 0 until n * c) win[k++] = region[base + i]
            }
            val s = ncc(win, t)
            if (s > best) best = s
        }
        return best
    }

    /** strength = 判定に使った左右カードの一致度のうち低い方（0..1）。高いほどカードが出揃っている */
    class GlyphResult(val order: String?, val strength: Float, val info: String)

    /** 対戦開始画面の左右カードから先攻/後攻を判定（左が自分） */
    fun glyphOrder(f: Frame): GlyphResult {
        val m = GN + 2 * GP
        val l = glyph(f, GL, GP); val r = glyph(f, GR, GP)
        val lf = best(l, m, Templates.LF, GN, 1); val ls = best(l, m, Templates.LS, GN, 1)
        val rf = best(r, m, Templates.RF, GN, 1); val rs = best(r, m, Templates.RS, GN, 1)
        val leftFirst = lf > 0.78f && lf - ls > 0.2f
        val leftSecond = ls > 0.78f && ls - lf > 0.2f
        val rightFirst = rf > 0.78f && rf - rs > 0.2f
        val rightSecond = rs > 0.78f && rs - rf > 0.2f
        // 左右どちらのカードも出揃って、互いに矛盾しないときだけ採用（演出の途中フレームは捨てる）
        val order = when {
            leftFirst && rightSecond -> "first"
            leftSecond && rightFirst -> "second"
            else -> null
        }
        val strength = when (order) {
            "first" -> min(lf, rs)
            "second" -> min(ls, rf)
            else -> 0f
        }
        return GlyphResult(order, strength, "先後 L先%.2f L後%.2f R先%.2f R後%.2f".format(lf, ls, rf, rs))
    }

    /** アイコン領域を既知のクラスと照合。確信が持てなければ null */
    fun classifyIcon(region: FloatArray): Pair<String?, Float> {
        val m = IN + 2 * IP
        var bestCls: String? = null
        var bestS = -1f
        var second = -1f
        for ((cls, tpls) in Store.iconTemplates()) {
            var s = -1f
            for (t in tpls) s = max(s, best(region, m, t, IN, 3))
            if (s > bestS) { second = bestS; bestS = s; bestCls = cls } else if (s > second) second = s
        }
        return if (bestS > 0.8f && bestS - second > 0.12f) Pair(bestCls, bestS) else Pair(null, bestS)
    }

    /** 上部中央の明るさと赤み（WINは金色に光る） */
    fun topStats(f: Frame): Pair<Float, Float> {
        val a = area(f, TOP, 8, 0)
        var lum = 0f; var rb = 0f
        val n = a.size / 3
        for (i in 0 until n) {
            lum += a[i * 3] * 0.299f + a[i * 3 + 1] * 0.587f + a[i * 3 + 2] * 0.114f
            rb += a[i * 3] - a[i * 3 + 2]
        }
        return Pair(lum / n, rb / n)
    }

    fun encode(v: FloatArray): String {
        val b = ByteArray(v.size) { (v[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    fun decode(s: String?): FloatArray? {
        if (s.isNullOrEmpty()) return null
        return try {
            val b = Base64.decode(s, Base64.DEFAULT)
            FloatArray(b.size) { (b[it].toInt() and 255) / 255f }
        } catch (_: Exception) {
            null
        }
    }
}
