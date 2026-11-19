package com.dante.note

import android.content.Context
import org.json.JSONArray

data class Gift(
    val name: String, val en: String, val kw: String, val tier: String, val flags: String,
    val recipe: List<String>, val effect: String,
    val sd: Int, val sc: Int, val sb: Int,
    val nc: Int, val nd: Int, val nb: Int,
    val src: List<String>, val heal: String, val usedIn: List<String>, val packs: List<String>
)

data class Pick(val gift: Gift, val score: Double, val why: List<String>)

data class Cfg(
    val kws: List<String>, val sup: List<String>, val crit: Set<String>,
    val heal: Int, val owned: Set<String>
)

class Scorer(ctx: Context) {
    val gifts: List<Gift>
    private val byName: Map<String, Gift>
    private val norm: List<Pair<String, Gift>>

    init {
        val txt = ctx.assets.open("gifts.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(txt)
        val list = ArrayList<Gift>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            fun strs(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            val sc = o.getJSONObject("sc")
            val num = o.getJSONObject("num")
            list.add(
                Gift(
                    o.getString("n"), o.optString("en"), o.optString("k"), o.optString("t"), o.optString("f"),
                    strs("rc"), o.optString("e"),
                    sc.optInt("d"), sc.optInt("c"), sc.optInt("b"),
                    num.optInt("c"), num.optInt("d"), num.optInt("b"),
                    strs("src"), o.optString("heal"), strs("used"), strs("pk")
                )
            )
        }
        gifts = list
        byName = list.associateBy { it.name }
        norm = list.map { n(it.name) to it }.filter { it.first.length >= 2 }.sortedByDescending { it.first.length }
    }

    companion object {
        val TW = mapOf("1" to 1.0, "2" to 1.6, "3" to 2.4, "4" to 3.4, "5" to 4.5, "EX" to 4.5)
        val ROMAN = mapOf("1" to "I", "2" to "II", "3" to "III", "4" to "IV", "5" to "V", "EX" to "EX")
        fun n(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }

    fun gift(name: String) = byName[name]

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** OCR로 읽은 줄들에서 기프트 이름 찾기 */
    fun match(lines: List<String>): List<Gift> {
        val ls = lines.map { n(it) }.filter { it.isNotEmpty() }
        val cands = ArrayList<String>(ls)
        for (i in 0 until ls.size - 1) cands.add(ls[i] + ls[i + 1])
        val found = LinkedHashMap<String, Gift>()
        for ((key, g) in norm) {
            var hit = false
            for (line in cands) {
                if (key.length <= 3) {
                    if (line == key || (line.contains(key) && line.length <= key.length + 3)) { hit = true; break }
                } else {
                    if (line.contains(key)) { hit = true; break }
                    if (key.length >= 5 && kotlin.math.abs(line.length - key.length) <= 2 && lev(line, key) <= 1) { hit = true; break }
                }
            }
            if (hit && found.keys.none { n(it).contains(key) }) found[g.name] = g
        }
        return found.values.toList()
    }

    private fun rel(g: Gift, c: Cfg): Pair<Double, String> {
        val all = c.kws + c.sup
        if (c.kws.contains(g.kw)) return 1.0 to "${g.kw} 키워드"
        val s = g.src.filter { all.contains(it) }
        if (s.isNotEmpty()) return 0.95 to s.joinToString("·") + " 부여"
        if (c.sup.contains(g.kw)) return 0.8 to "${g.kw} 보충"
        if (g.kw == "범용") return 0.45 to "범용"
        if (all.any { g.effect.contains(it) }) return 0.6 to "연관"
        return 0.0 to ""
    }

    private fun value(g: Gift, c: Cfg): Pair<Double, String> {
        val (r, why) = rel(g, c)
        if (r == 0.0) return 0.0 to ""
        val crit = if (c.crit.isEmpty()) setOf("dmg", "clash", "deb") else c.crit
        var w = 0.0
        if ("dmg" in crit) w += g.sd + minOf(3.0, g.nd * 0.04)
        if ("clash" in crit) w += g.sc + minOf(3.0, g.nc * 0.5)
        if ("deb" in crit) w += g.sb + minOf(2.0, g.nb * 0.15)
        return r * (TW[g.tier] ?: 1.0) * (w + 0.3) to why
    }

    private fun healOrder(c: Cfg): List<Gift> {
        val re1 = Regex("최대 체력의 (\\d+)%")
        return gifts.filter { it.heal.isNotEmpty() && it.recipe.isEmpty() }.map { g ->
            var s = (if (g.heal == "rev") 40.0 else 3.0) + (TW[g.tier] ?: 1.0)
            if (g.effect.contains("모든 아군") || g.effect.contains("전투에 참여한 모든")) s += 2.5
            if (Regex("\\[편성[^\\]]*전용").containsMatchIn(g.effect)) s -= 2
            if (g.effect.contains("편성되어 있으면") || g.effect.contains("소속 인격이")) s -= 2.5
            re1.find(g.effect)?.let { s += minOf(3.0, it.groupValues[1].toDouble() / 10) }
            if (c.kws.contains(g.kw)) s += 2 else if (g.kw != "범용") s -= 3
            if (g.packs.isNotEmpty()) s -= 0.5
            g to s
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun rank(found: List<Gift>, c: Cfg): List<Pick> {
        val heal = healOrder(c)
        return found.map { g ->
            val why = ArrayList<String>()
            var s = 0.0
            if (c.owned.contains(g.name)) {
                why.add("이미 보유")
                return@map Pick(g, -1.0, why)
            }
            val (v, w) = value(g, c)
            if (v > 0) { s += v; why.add(w) }
            val hi = heal.indexOf(g)
            if (hi in 0 until c.heal) { s += 12 - hi * 2; why.add("회복·부활 ${hi + 1}순위") }
            else if (g.heal == "rev") { s += 4; why.add("부활") }
            for (fn in g.usedIn) {
                val f = byName[fn] ?: continue
                if (c.owned.contains(fn)) continue
                val miss = f.recipe.count { it != g.name && !c.owned.contains(it) }
                when (miss) {
                    0 -> { s += 10; why.add("사면 「$fn」 합성 가능") }
                    1 -> { s += 4; why.add("「$fn」 재료 (1개 더)") }
                    else -> s += 1
                }
            }
            if (why.isEmpty()) why.add("관련 낮음")
            Pick(g, s, why)
        }.sortedByDescending { it.score }
    }
}
