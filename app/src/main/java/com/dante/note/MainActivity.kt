package com.dante.note

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object Prefs {
    private const val F = "cfg"
    val KWS = listOf("화상", "출혈", "진동", "파열", "침잠", "호흡", "충전", "참격", "관통", "타격")
    val STK = listOf("화상", "출혈", "진동", "파열", "침잠", "호흡", "충전")
    private fun sp(c: Context) = c.getSharedPreferences(F, Context.MODE_PRIVATE)
    fun list(c: Context, k: String, def: String = ""): MutableList<String> =
        (sp(c).getString(k, def) ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
    fun setList(c: Context, k: String, v: Collection<String>) = sp(c).edit().putString(k, v.joinToString(",")).apply()
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun cfg(c: Context) = Cfg(
        list(c, "kws"), list(c, "sup"),
        list(c, "crit", "dmg,clash").toSet(),
        int(c, "heal", 2), list(c, "owned").toSet()
    )
    fun addOwned(c: Context, name: String) {
        val l = list(c, "owned"); if (!l.contains(name)) { l.add(name); setList(c, "owned", l) }
    }
}

class MainActivity : Activity() {
    private val REQ = 7
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#F0C674")); textSize = 18f
        setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(18), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#A8916F")); textSize = 13f
    }

    private fun checks(keys: List<String>, labels: List<String>, prefKey: String, def: String = "", onChange: (() -> Unit)? = null): GridLayout {
        val g = GridLayout(this).apply { columnCount = 4 }
        val cur = Prefs.list(this, prefKey, def)
        keys.forEachIndexed { i, k ->
            g.addView(CheckBox(this).apply {
                text = labels[i]; setTextColor(Color.WHITE); isChecked = cur.contains(k)
                setOnCheckedChangeListener { _, on ->
                    val l = Prefs.list(this@MainActivity, prefKey, def)
                    if (on) { if (!l.contains(k)) l.add(k) } else l.remove(k)
                    Prefs.setList(this@MainActivity, prefKey, l)
                    onChange?.invoke()
                }
            })
        }
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(Color.parseColor("#120D0A"))
        }
        root.addView(TextView(this).apply {
            text = "DANTE'S NOTE"; setTextColor(Color.parseColor("#E0A84A")); textSize = 26f
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        root.addView(note("림버스 컴퍼니 거울 던전 화면을 읽고 기프트 추천을 띄워 주는 도우미예요. 화면을 보기만 하고 게임을 조작하지 않아요."))

        root.addView(title("1. 권한"))
        root.addView(Button(this).apply {
            text = "다른 앱 위에 표시 권한 켜기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        if (Build.VERSION.SDK_INT >= 33) root.addView(Button(this).apply {
            text = "알림 권한 허용"
            setOnClickListener { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3) }
        })

        root.addView(title("2. 내 덱 키워드"))
        root.addView(checks(Prefs.KWS, Prefs.KWS, "kws"))
        root.addView(title("기프트로 보충할 키워드"))
        root.addView(note("인격에 없어도 기프트로 걸 수 있는 효과예요. 고르면 그 효과를 걸어 주는 기프트와 사기 기프트를 추천에 넣어요."))
        root.addView(checks(Prefs.STK, Prefs.STK, "sup"))
        root.addView(title("기준"))
        root.addView(checks(listOf("dmg", "clash", "deb"), listOf("피해", "합 위력", "디버프"), "crit", "dmg,clash"))

        root.addView(title("챙길 회복·부활 기프트"))
        val healLbl = note("")
        fun setHealLbl(v: Int) { healLbl.text = if (v == 0) "안 챙김" else "${v}개 (부활 → 전체 회복 → 높은 등급 순)" }
        val hv = Prefs.int(this, "heal", 2); setHealLbl(hv)
        root.addView(healLbl)
        root.addView(SeekBar(this).apply {
            max = 5; progress = hv
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { Prefs.setInt(this@MainActivity, "heal", p); setHealLbl(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(title("3. 탐사 기록"))
        val ownedLbl = note("")
        fun refreshOwned() {
            val o = Prefs.list(this, "owned")
            ownedLbl.text = if (o.isEmpty()) "이번 탐사에서 얻은 기프트가 없어요. 추천 창에서 기프트를 누르면 기록돼요." else "얻은 기프트 ${o.size}개: " + o.joinToString(", ")
        }
        refreshOwned()
        root.addView(ownedLbl)
        root.addView(Button(this).apply {
            text = "새 탐사 시작 (기록 초기화)"
            setOnClickListener { Prefs.setList(this@MainActivity, "owned", emptyList()); refreshOwned(); toast("기록을 비웠어요") }
        })

        root.addView(title("4. 도우미 실행"))
        status = note("")
        root.addView(status)
        root.addView(Button(this).apply {
            text = "도우미 시작"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "도우미 종료"
            setOnClickListener { startService(Intent(this@MainActivity, OverlayService::class.java).setAction("STOP")) }
        })
        root.addView(note("\n사용법: 시작 → 화면 캡처 허용 → 림버스로 돌아가서 상점·보상 화면에서 떠 있는 '단테' 버튼을 누르세요. 버튼은 끌어서 옮길 수 있고, 길게 누르면 추천 창이 닫혀요."))
        root.addView(note("\n기프트 정보: 나무위키 (CC BY-NC-SA 2.0 KR)"))

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#120D0A")) })
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text =
            if (Settings.canDrawOverlays(this)) "✓ 다른 앱 위에 표시 권한 있음" else "✕ 다른 앱 위에 표시 권한이 필요해요"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun start() {
        if (!Settings.canDrawOverlays(this)) { toast("먼저 '다른 앱 위에 표시' 권한을 켜 주세요"); return }
        if (Prefs.list(this, "kws").isEmpty()) toast("덱 키워드를 고르면 추천이 더 정확해요")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK || data == null) { toast("화면 캡처를 허용해야 써요"); return }
        val i = Intent(this, OverlayService::class.java).putExtra("code", resultCode).putExtra("data", data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("도우미를 켰어요. 림버스로 돌아가세요")
        moveTaskToBack(true)
    }
}
