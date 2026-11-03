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
        private const val INTERVAL_MS = 700L
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
        recorder = Recorder { m -> onRecorded(m) }
        dpi = resources.displayMetrics.densityDpi

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }

            // Android 14〜：「1つのアプリだけ共有」を選んだときなど、映す範囲のサイズが変わった
            override fun onCapturedContentResize(width: Int, height: Int) {
                h.post {
                    contentSizeMode = true
                    safeResize(width, height)
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

    private fun safeResize(w: Int, h: Int) {
        try { resizeTo(w, h) } catch (e: Exception) { Store.setDebug("サイズ変更エラー: $e") }
    }

    private fun resizeTo(srcW: Int, srcH: Int) {
        if (srcW <= 0 || srcH <= 0) return
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
        if (!contentSizeMode && now - lastSizeCheck > 2000) {
            lastSizeCheck = now
            val (w, h) = displaySize()       // 縦⇔横の回転に追従
            resizeTo(w, h)
        }
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
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val f = Frame(px, w, h)
        if (f.cw < w * 0.5f || f.ch < h * 0.5f) return   // ほぼ真っ黒（画面オフなど）
        if (f.cw < f.ch) {                                  // 縦画面＝シャドバの対戦画面ではない
            Store.setDebug("縦画面のため待機中")
            return
        }
        val lines = ocr(bmp, f)
        val obs = Analyzer.analyze(f, lines)
        recorder?.feed(obs, System.currentTimeMillis())
        Store.setDebug(obs.debug)
    }

    private fun ocr(bmp: Bitmap, f: Frame): List<Line> {
        val rec = recognizer ?: return emptyList()
        val text = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
        val out = ArrayList<Line>()
        for (block in text.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            out.add(
                Line(
                    line.text,
                    (box.exactCenterX() - f.cl) / f.cw,
                    (box.exactCenterY() - f.ct) / f.ch,
                ),
            )
        }
        return out
    }

    // ---------- 記録と通知 ----------

    private fun onRecorded(m: Match) {
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
