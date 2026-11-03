package com.marionette1994.svrec

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Classes {
    val ids = listOf("elf", "royal", "witch", "dragon", "vamp", "bishop", "nemesis")
    val names = linkedMapOf(
        "elf" to "エルフ", "royal" to "ロイヤル", "witch" to "ウィッチ", "dragon" to "ドラゴン",
        "vamp" to "ナイトメア", "bishop" to "ビショップ", "nemesis" to "ネメシス",
    )

    fun name(id: String?): String = if (id == null) "？" else names[id] ?: "？"

    /** OCRで読めた断片（「ネメ」「ヤル」など）からクラスを推定する */
    fun fromText(raw: String): String? {
        val t = raw.replace(" ", "").replace("　", "")
        var best: String? = null
        var bestHits = 0
        var tie = false
        for ((id, name) in names) {
            if (t.contains(name)) return id
            var hits = 0
            for (i in 0 until name.length - 1) if (t.contains(name.substring(i, i + 2))) hits++
            if (hits > bestHits) {
                best = id; bestHits = hits; tie = false
            } else if (hits == bestHits && hits > 0) {
                tie = true
            }
        }
        return if (bestHits > 0 && !tie) best else null
    }
}

data class Match(
    val id: String,
    var ts: Long,
    var result: String,       // "win" / "loss"
    var order: String?,       // "first" / "second" / null
    var myClass: String?,
    var oppClass: String?,
    var oppName: String = "",
    var oppType: String = "",
    var needsCheck: Boolean = false,
    var exported: Boolean = false,
    var myIcon: String? = null,
    var oppIcon: String? = null,
    var series: String = "",
    var deck: String = "",
) {
    /** 統計・書き出しで使うデッキ名（空ならクラス名。SVTの取り込みと同じ扱い） */
    fun deckLabel(): String = deck.ifBlank { Classes.name(myClass) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("ts", ts); put("result", result)
        put("order", order ?: JSONObject.NULL)
        put("myClass", myClass ?: JSONObject.NULL)
        put("oppClass", oppClass ?: JSONObject.NULL)
        put("oppName", oppName); put("oppType", oppType)
        put("needsCheck", needsCheck); put("exported", exported)
        put("myIcon", myIcon ?: JSONObject.NULL)
        put("oppIcon", oppIcon ?: JSONObject.NULL)
        put("series", series); put("deck", deck)
    }

    companion object {
        private fun JSONObject.str(k: String): String? =
            if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

        fun fromJson(o: JSONObject) = Match(
            id = o.getString("id"),
            ts = o.getLong("ts"),
            result = o.optString("result", "loss"),
            order = o.str("order"),
            myClass = o.str("myClass"),
            oppClass = o.str("oppClass"),
            oppName = o.optString("oppName", ""),
            oppType = o.optString("oppType", ""),
            needsCheck = o.optBoolean("needsCheck", false),
            exported = o.optBoolean("exported", false),
            myIcon = o.str("myIcon"),
            oppIcon = o.str("oppIcon"),
            series = o.optString("series", ""),
            deck = o.optString("deck", ""),
        )
    }
}

/** 記録・設定・学習したアイコンの保存先（アプリ内ファイル） */
object Store {
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private val matches = mutableListOf<Match>()            // 新しい順
    private val learned = mutableMapOf<String, MutableList<FloatArray>>()
    private var inited = false

    @Volatile var running = false
        private set
    @Volatile var debug: String = ""
        private set
    var debugListener: ((String) -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        dir = ctx.applicationContext.filesDir
        prefs = ctx.applicationContext.getSharedPreferences("svrec", Context.MODE_PRIVATE)
        loadMatches()
        loadIcons()
    }

    // ---------- 設定 ----------
    var series: String
        get() = prefs.getString("series", "") ?: ""
        set(v) { prefs.edit().putString("series", v).apply() }
    var deck: String
        get() = prefs.getString("deck", "") ?: ""
        set(v) { prefs.edit().putString("deck", v).apply() }
    var defaultMyClass: String?
        get() = prefs.getString("myClass", null)
        set(v) { prefs.edit().putString("myClass", v).apply() }

    // ---------- 通知 ----------
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() {
        main.post {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it() }
        }
    }

    fun setRunning(v: Boolean) { running = v; changed() }
    fun setDebug(s: String) {
        debug = s
        main.post { debugListener?.invoke(s) }
    }

    // ---------- 記録 ----------
    fun snapshot(): List<Match> = synchronized(this) { matches.map { it.copy() } }

    fun addMatch(m: Match) {
        synchronized(this) { matches.add(0, m); saveMatches() }
        changed()
    }

    fun update(id: String, f: (Match) -> Unit) {
        synchronized(this) {
            matches.find { it.id == id }?.let(f)
            saveMatches()
        }
        changed()
    }

    fun delete(id: String) {
        synchronized(this) { matches.removeAll { it.id == id }; saveMatches() }
        changed()
    }

    fun markExported(ids: Set<String>) {
        synchronized(this) { matches.forEach { if (it.id in ids) it.exported = true }; saveMatches() }
        changed()
    }

    fun removeExported(): Int {
        val n: Int
        synchronized(this) {
            val before = matches.size
            matches.removeAll { it.exported }
            n = before - matches.size
            saveMatches()
        }
        changed()
        return n
    }

    private fun loadMatches() {
        val f = File(dir, "matches.json")
        if (!f.exists()) return
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) matches.add(Match.fromJson(a.getJSONObject(i)))
        } catch (_: Exception) {
        }
    }

    private fun saveMatches() {
        val a = JSONArray()
        matches.forEach { a.put(it.toJson()) }
        val tmp = File(dir, "matches.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(File(dir, "matches.json"))
    }

    // ---------- クラスアイコンの学習 ----------
    @Synchronized
    fun iconTemplates(): Map<String, List<FloatArray>> {
        val out = mutableMapOf<String, MutableList<FloatArray>>()
        Templates.ICON_SEEDS.forEach { (k, v) -> out.getOrPut(k) { mutableListOf() }.add(v) }
        learned.forEach { (k, v) -> out.getOrPut(k) { mutableListOf() }.addAll(v) }
        return out
    }

    /** 学習済みのクラス数（ネメシス・ロイヤルの初期値を含む） */
    @Synchronized
    fun knownIconClasses(): Set<String> = Templates.ICON_SEEDS.keys + learned.filterValues { it.isNotEmpty() }.keys

    /**
     * cls のアイコンとして vec を覚える。
     * force=true（手で直したとき）は、他クラスに紛れ込んだ似たテンプレートも消す。
     */
    @Synchronized
    fun learnIcon(cls: String, vec: FloatArray, force: Boolean = false) {
        if (vec.size != Vision.IN * Vision.IN * 3) return
        if (force) {
            learned.forEach { (k, list) -> if (k != cls) list.removeAll { Vision.ncc(it, vec) > 0.85f } }
        }
        val mine = (Templates.ICON_SEEDS[cls]?.let { listOf(it) } ?: emptyList()) + (learned[cls] ?: emptyList())
        if (mine.any { Vision.ncc(it, vec) > 0.9f }) { saveIcons(); return }
        val list = learned.getOrPut(cls) { mutableListOf() }
        list.add(0, vec)
        while (list.size > 4) list.removeAt(list.size - 1)
        saveIcons()
    }

    private fun loadIcons() {
        val f = File(dir, "icons.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                val list = mutableListOf<FloatArray>()
                for (i in 0 until a.length()) Vision.decode(a.getString(i))?.let { list.add(it) }
                learned[k] = list
            }
        } catch (_: Exception) {
        }
    }

    private fun saveIcons() {
        val o = JSONObject()
        learned.forEach { (k, list) -> o.put(k, JSONArray().apply { list.forEach { put(Vision.encode(it)) } }) }
        File(dir, "icons.json").writeText(o.toString())
    }
}
