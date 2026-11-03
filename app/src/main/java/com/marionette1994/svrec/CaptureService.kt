package com.marionette1994.svrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 画面をキャプチャして、VS画面とリザルト画面を見張る常駐サービス */
class CaptureService : Service() {
    companion object {
        const val ACTION_STOP = "com.marionette1994.svrec.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "rec"
        private const val NID = 1
        private const val LONG_SIDE = 1600
        private const val INTERVAL_MS = 200L     // 画像照合（軽い）は0.2秒ごと
        private const val OCR_MS = 900L          // 文字認識（重い）は約1秒ごと
        private const val OCR_VS_MS = 400L       // VS画面が見えている間は文字認識も速める
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var recognizer: TextRecognizer? = null
    private var recorder: Recorder? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 320
    private var contentSizeMode = false
    private var lastSizeCheck = 0L
    private var lastLine = "画面を見張っています"
    private var lastOcr = 0L
    private var pxBuf = IntArray(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        createChannel()
        // Android 14以降は、getMediaProjection の前に前面サービス化が必須
        startForeground(NID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            if (data != null) mpm.getMediaProjection(code, data) else null
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p

        val t = HandlerThread("svrec-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        recorder = Recorder { m, thumb -> onRecorded(m, thumb) }
        dpi = resources.displayMetrics.densityDpi

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }

            // Android 14〜：「1つのアプリだけ共有」を選んだときなど、映す範囲のサイズが変わった
            override fun onCapturedContentResize(width: Int, height: Int) {
                h.post {
                    contentSizeMode = true
                    safeResize(width, height, true)
                }
            }
        }, h)

        h.post {
            val (w, hh) = displaySize()
            safeResize(w, hh)
            Store.setRunning(true)
            h.postDelayed(poll, INTERVAL_MS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Store.setRunning(false)
        handler?.removeCallbacksAndMessages(null)
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        display = null
        reader = null
        try { recognizer?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        super.onDestroy()
    }

    // ---------- キャプチャ ----------

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val dmgr = getSystemService(DISPLAY_SERVICE) as DisplayManager
        dmgr.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun safeResize(w: Int, h: Int, keepAspect: Boolean = false) {
        try { resizeTo(w, h, keepAspect) } catch (e: Exception) { Store.setDebug("サイズ変更エラー: $e") }
    }

    /**
     * キャプチャ先の大きさを決める。シャドバは横画面なので、端末の向きに関係なく常に横長で作る。
     * （縦向きで作ってから resize すると、機種によっては映像が中央に小さく縮んだままになるため）
     */
    private fun resizeTo(rawW: Int, rawH: Int, keepAspect: Boolean = false) {
        if (rawW <= 0 || rawH <= 0) return
        val srcW = if (keepAspect) rawW else maxOf(rawW, rawH)
        val srcH = if (keepAspect) rawH else minOf(rawW, rawH)
        val p = projection ?: return
        val s = minOf(1f, LONG_SIDE.toFloat() / maxOf(srcW, srcH))
        val w = maxOf(2, (srcW * s).toInt() and 1.inv())
        val h = maxOf(2, (srcH * s).toInt() and 1.inv())
        if (w == capW && h == capH && reader != null) return
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        capW = w
        capH = h
        val d = display
        if (d == null) {
            // 1つの MediaProjection につき作れるのは1回だけ。以降は resize で対応する
            display = p.createVirtualDisplay(
                "svrec", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler,
            )
        } else {
            d.resize(w, h, dpi)
            d.surface = r.surface
        }
        try { old?.close() } catch (_: Exception) {}
    }

    private val poll = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Throwable) {
                Store.setDebug("エラー: $e")
            }
            if (projection != null) handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val r = reader ?: return
        val img = r.acquireLatestImage() ?: return
        val bmp = try { toBitmap(img) } finally { img.close() }
        try {
            process(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val ps = plane.pixelStride
        val pad = plane.rowStride - ps * img.width
        val b = Bitmap.createBitmap(img.width + pad / ps, img.height, Bitmap.Config.ARGB_8888)
        b.copyPixelsFromBuffer(plane.buffer)
        if (pad == 0) return b
        val c = Bitmap.createBitmap(b, 0, 0, img.width, img.height)
        b.recycle()
        return c
    }

    private fun process(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (pxBuf.size != w * h) pxBuf = IntArray(w * h)   // 毎フレーム確保しない
        val px = pxBuf
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val f = Frame(px, w, h)
        if (f.cw < w * 0.25f || f.ch < h * 0.25f) return   // ほぼ真っ黒（画面オフなど）
        if (f.cw < f.ch) {                                  // 縦画面＝シャドバの対戦画面ではない
            Store.setDebug("縦画面のため待機中")
            return
        }
        val rec = recorder ?: return
        val now = SystemClock.elapsedRealtime()
        val g = Vision.glyphOrder(f)
        val needOcr = now - lastOcr >= (if (g.order != null) OCR_VS_MS else OCR_MS)
        val obsOrNull = if (needOcr) {
            lastOcr = now
            Analyzer.analyze(f, ocr(bmp, f), g)
        } else {
            Analyzer.fast(f, g) ?: (if (now - lastBattle > 5000) cornerCheck(bmp, f, now) else null)
        }
        maybeSaveDebug(bmp, f, obsOrNull, g, now)
        val obs = obsOrNull ?: return                       // 何も判定できないフレームは捨てる
        rec.feed(obs, System.currentTimeMillis())
        // 先後を画像で判定できたフレームのうち、一番くっきりしたものを開始画面として残す
        if (obs.kind == Kind.VS && obs.orderByGlyph && rec.wantsThumb(obs.glyphStrength)) {
            val tw = 640
            val th = maxOf(1, h * tw / w)
            rec.setThumb(Bitmap.createScaledBitmap(bmp, tw, th, true), obs.glyphStrength)
        }
        if (obs.kind == Kind.BATTLE && !obs.mulligan) lastBattle = now
        val wall = System.currentTimeMillis()
        if (obs.kind != Kind.NONE || wall - lastNoneLog > 10_000) {
            if (obs.kind == Kind.NONE) lastNoneLog = wall
            Store.addLog("${Recorder.hms(wall)} ${obs.debug.replace("\n", " / ")}")
        }
        val votes = rec.voteText()
        val size = "キャプチャ${w}x${h} ゲーム部分${f.cw.toInt()}x${f.ch.toInt()}"
        Store.setDebug((if (votes.isEmpty()) obs.debug else obs.debug + "\n" + votes) + "\n" + size)
    }

    private var lastDebugSave = 0L
    private var lastNoneLog = 0L
    private var lastCorner = 0L
    private var lastBattle = 0L   // 対戦中の画面を最後に見た時刻（対戦中は右下の軽い読取をしない）

    /**
     * マリガン画面の「あなたの手番 先攻/後攻」は表示が短いことがあるので、
     * 右下の小さな範囲だけを0.3秒ごとに拡大して読む（全体の文字認識より軽い）
     */
    private fun cornerCheck(bmp: Bitmap, f: Frame, now: Long): Obs? {
        if (now - lastCorner < 300) return null
        lastCorner = now
        val rec = recognizer ?: return null
        val x0 = 0.74f; val y0 = 0.74f; val x1 = 0.97f; val y1 = 0.98f
        val sx = (f.cl + x0 * f.cw).toInt().coerceIn(0, bmp.width - 2)
        val sy = (f.ct + y0 * f.ch).toInt().coerceIn(0, bmp.height - 2)
        val sw = ((x1 - x0) * f.cw).toInt().coerceIn(1, bmp.width - sx)
        val sh = ((y1 - y0) * f.ch).toInt().coerceIn(1, bmp.height - sy)
        val crop = Bitmap.createBitmap(bmp, sx, sy, sw, sh)
        val big = Bitmap.createScaledBitmap(crop, sw * 2, sh * 2, true)
        try {
            val t = Tasks.await(rec.process(InputImage.fromBitmap(big, 0)), 3, TimeUnit.SECONDS)
            val lines = ArrayList<Line>()
            for (block in t.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                lines.add(Line(line.text, x0 + (x1 - x0) * box.exactCenterX() / big.width, y0 + (y1 - y0) * box.exactCenterY() / big.height))
            }
            return Analyzer.mulliganOnly(lines)
        } finally {
            if (big !== crop) big.recycle()
            crop.recycle()
        }
    }

    /** 診断モード中は、アプリが実際に見ている画面を約0.7秒ごとに Pictures/SVRecorder に保存する */
    private fun maybeSaveDebug(bmp: Bitmap, f: Frame, obs: Obs?, g: Vision.GlyphResult, now: Long) {
        if (System.currentTimeMillis() > Store.debugCaptureUntil) return
        if (now - lastDebugSave < 700) return
        lastDebugSave = now
        try {
            val t = java.text.SimpleDateFormat("HHmmss_SSS", java.util.Locale.JAPAN).format(java.util.Date())
            val kind = obs?.kind?.name ?: "SKIP"
            val sc = g.info.replace(Regex("[^0-9.]+"), "-").trim('-')
            val name = "svrec_${t}_${kind}_${sc}_area${f.cl}-${f.ct}-${f.cr}-${f.cb}.jpg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SVRecorder")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {
        }
    }

    /** ゲーム画面の部分だけを切り出し、小さければ拡大してから文字認識する */
    private fun ocr(bmp: Bitmap, f: Frame): List<Line> {
        val rec = recognizer ?: return emptyList()
        val cw = f.cr - f.cl
        val ch = f.cb - f.ct
        var img = bmp
        val owned = ArrayList<Bitmap>()
        if (cw < bmp.width || ch < bmp.height) {
            img = Bitmap.createBitmap(bmp, f.cl, f.ct, cw, ch).also { owned.add(it) }
        }
        if (img.width < 1500) {
            val tw = 1600
            val th = maxOf(1, img.height * tw / img.width)
            img = Bitmap.createScaledBitmap(img, tw, th, true).also { owned.add(it) }
        }
        try {
            val text = Tasks.await(rec.process(InputImage.fromBitmap(img, 0)), 5, TimeUnit.SECONDS)
            val out = ArrayList<Line>()
            for (block in text.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                out.add(Line(line.text, box.exactCenterX() / img.width, box.exactCenterY() / img.height))
            }
            // 右端の帯（PP+1ボタン・「あなたの手番 先攻/後攻」）は文字が小さいので、2倍に拡大してもう一度読む
            val x0 = 0.72f
            val sx = (f.cl + x0 * cw).toInt().coerceIn(0, bmp.width - 2)
            val sw = (f.cr - sx).coerceAtLeast(1)
            val strip = Bitmap.createBitmap(bmp, sx, f.ct, sw, ch).also { owned.add(it) }
            val big = Bitmap.createScaledBitmap(strip, sw * 2, ch * 2, true).also { owned.add(it) }
            val t2 = Tasks.await(rec.process(InputImage.fromBitmap(big, 0)), 5, TimeUnit.SECONDS)
            for (block in t2.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                out.add(Line(line.text, x0 + (1f - x0) * box.exactCenterX() / big.width, box.exactCenterY() / big.height))
            }
            return out
        } finally {
            owned.forEach { if (it !== bmp) it.recycle() }
        }
    }

    // ---------- 記録と通知 ----------

    private fun onRecorded(m: Match, thumb: Bitmap?) {
        if (thumb != null) {
            try {
                Store.thumbFile(m.id).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } catch (_: Exception) {
            }
            thumb.recycle()
        }
        Store.addMatch(m)
        val res = if (m.result == "win") "勝利" else "敗北"
        val warn = if (m.needsCheck) " ⚠要確認" else ""
        lastLine = "$res ${Analyzer.orderJa(m.order)} ${Classes.name(m.myClass)} vs ${Classes.name(m.oppClass)}$warn"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NID, buildNotification())
    }

    private fun todayTally(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val today = Store.snapshot().filter { it.ts >= start }
        val w = today.count { it.result == "win" }
        return "今日 ${w}勝${today.size - w}敗"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "記録中の表示", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("SV戦績オート 記録中（${todayTally()}）")
            .setContentText(lastLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "停止", stop).build())
            .build()
    }
}
