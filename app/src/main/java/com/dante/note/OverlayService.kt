package com.dante.note

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var busy = false
    private var scorer: Scorer? = null
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (projection == null && data != null) {
            try { setupProjection(code, data) } catch (e: Exception) { toast("화면 캡처를 시작하지 못했어요: ${e.message}") }
        }
        if (bubble == null) showBubble()
        if (scorer == null) Thread { scorer = Scorer(this) }.start()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val ch = "dante"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "도우미", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch)
            .setContentTitle("단테의 노트 실행 중")
            .setContentText("떠 있는 '단테' 버튼을 누르면 화면을 분석해요")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: return
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)
        val (w, h, dpi) = screenSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = mp.createVirtualDisplay("dante", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
    }

    /** 화면 방향이 바뀌었으면 캡처 크기를 다시 맞춘다 */
    private fun ensureSize(): Boolean {
        val r = reader ?: return false
        val (w, h, dpi) = screenSize()
        if (r.width == w && r.height == h) return false
        val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd?.resize(w, h, dpi)
        vd?.surface = nr.surface
        reader = nr
        r.close()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val b = TextView(this).apply {
            text = "단테"; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#1A1007")); textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E0A84A")); setStroke(dp(2), Color.parseColor("#7A5426"))
            }
            alpha = 0.92f
        }
        val lp = WindowManager.LayoutParams(dp(54), dp(54), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(140) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var downAt = 0L
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; downAt = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - downAt > 600) closePanel() else capture()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(b, lp)
        bubble = b; bubbleLp = lp
    }

    private fun toast(t: String) = handler.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun capture() {
        if (busy) return
        if (reader == null) { toast("화면 캡처 권한이 없어요. 앱에서 다시 시작해 주세요"); return }
        busy = true
        closePanel()
        bubble?.visibility = View.INVISIBLE
        bubble?.text = "…"
        val delay = if (ensureSize()) 600L else 250L
        handler.postDelayed({
            val bmp = grab()
            bubble?.visibility = View.VISIBLE
            if (bmp == null) { busy = false; bubble?.text = "단테"; toast("화면을 가져오지 못했어요. 다시 눌러 주세요"); return@postDelayed }
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { txt ->
                    val lines = txt.textBlocks.flatMap { b -> b.lines.map { it.text } }
                    val sc = scorer ?: Scorer(this).also { scorer = it }
                    val found = sc.match(lines)
                    showPanel(sc.rank(found, Prefs.cfg(this)), lines.size)
                }
                .addOnFailureListener { toast("글자 인식 실패: ${it.message}") }
                .addOnCompleteListener { busy = false; bubble?.text = "단테"; bmp.recycle() }
        }, delay)
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        return try {
            val p = img.planes[0]
            val w = img.width; val h = img.height
            val rowPad = p.rowStride - p.pixelStride * w
            val full = Bitmap.createBitmap(w + rowPad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val out = Bitmap.createBitmap(full, 0, 0, w, h)
            if (out != full) full.recycle()
            out
        } catch (e: Exception) { null } finally { img.close() }
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun showPanel(picks: List<Pick>, lineCount: Int) {
        closePanel()
        val (sw, sh, _) = screenSize()
        val land = sw > sh
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01A120C")); cornerRadius = dp(12).toFloat(); setStroke(dp(2), Color.parseColor("#7A5426"))
            }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "추천 순위"; setTextColor(Color.parseColor("#F0C674")); textSize = 16f; setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = "  닫기  "; setTextColor(Color.WHITE); textSize = 14f
            setOnClickListener { closePanel() }
        })
        box.addView(head)
        val cfg = Prefs.cfg(this)
        box.addView(TextView(this).apply {
            text = "덱: " + (cfg.kws.joinToString("·").ifEmpty { "미설정" }) + (if (cfg.sup.isNotEmpty()) " · 보충: " + cfg.sup.joinToString("·") else "") + " · 기프트를 누르면 얻은 걸로 기록"
            setTextColor(Color.parseColor("#A8916F")); textSize = 11f
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (picks.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "\n화면에서 기프트 이름을 찾지 못했어요.\n(읽은 글줄 ${lineCount}개)\n상점·보상 화면에서 기프트 이름이 보일 때 눌러 주세요."
                setTextColor(Color.WHITE); textSize = 13f
            })
        }
        var rank = 0
        for (pk in picks) {
            val g = pk.gift
            val good = pk.score > 0
            if (good) rank++
            val row = TextView(this).apply {
                val eff = g.effect.replace("\n", " ").let { if (it.length > 70) it.take(70) + "…" else it }
                text = (if (good) "$rank. " else "– ") + g.name + "   " + (if (good) String.format("%.0f점", pk.score) else "") +
                    "\n" + (Scorer.ROMAN[g.tier] ?: g.tier) + "등급 · " + g.kw + " · " + pk.why.joinToString(", ") +
                    "\n" + eff
                setTextColor(if (pk.score < 0) Color.GRAY else Color.WHITE); textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                if (rank == 1 && good) setBackgroundColor(Color.parseColor("#3A2410"))
                setOnClickListener {
                    Prefs.addOwned(this@OverlayService, g.name)
                    toast("「${g.name}」 얻은 걸로 기록했어요")
                    closePanel()
                }
            }
            list.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(list) }
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val w = if (land) (sw * 0.42).toInt() else (sw * 0.92).toInt()
        val h = if (land) (sh * 0.86).toInt() else (sh * 0.6).toInt()
        val lp = WindowManager.LayoutParams(w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = if (land) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = dp(8); y = dp(8)
        }
        wm.addView(box, lp)
        panel = box
    }

    override fun onDestroy() {
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }
}
