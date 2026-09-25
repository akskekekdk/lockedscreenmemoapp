package com.lockmemo.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 메모를 잠금화면 배경화면에 한 줄씩 그려 넣는다.
 * 알림은 폰이 겹치거나 묶어서 보여줄 수 있지만, 배경화면에 그린 글자는 항상 그대로 보인다.
 */
object LockWallpaper {
    private const val TAG = "LockWallpaper"
    private const val PREFS = "wallpaper"
    private const val KEY_APPLIED = "applied"
    private const val KEY_LAST = "last_signature"
    private const val BACKGROUND_FILE = "lock_background.jpg"
    private const val MAX_LINES = 8

    private val worker = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun backgroundFile(context: Context) = File(context.filesDir, BACKGROUND_FILE)

    fun hasCustomBackground(context: Context) = backgroundFile(context).exists()

    /** 메모 목록이 바뀌었을 때만 새로 그려서 잠금화면 배경으로 설정한다. */
    fun update(context: Context, lines: List<String>) {
        val app = context.applicationContext
        val background = backgroundFile(app)
        val signature = (lines.take(MAX_LINES) + "bg:${background.lastModified()}").joinToString("\n")
        if (prefs(app).getBoolean(KEY_APPLIED, false) && prefs(app).getString(KEY_LAST, null) == signature) return
        worker.execute {
            try {
                val (width, height) = screenSize(app)
                val bitmap = render(app, lines.take(MAX_LINES), width, height)
                WallpaperManager.getInstance(app).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                bitmap.recycle()
                prefs(app).edit().putBoolean(KEY_APPLIED, true).putString(KEY_LAST, signature).apply()
            } catch (e: Exception) {
                Log.w(TAG, "잠금화면 배경 설정 실패", e)
            }
        }
    }

    /** 이 앱이 바꿔둔 잠금화면 배경을 없애 홈 화면 배경으로 돌아가게 한다. */
    fun clear(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(KEY_APPLIED, false)) return
        prefs(app).edit().putBoolean(KEY_APPLIED, false).remove(KEY_LAST).apply()
        worker.execute {
            try {
                WallpaperManager.getInstance(app).clear(WallpaperManager.FLAG_LOCK)
            } catch (e: Exception) {
                Log.w(TAG, "잠금화면 배경 되돌리기 실패", e)
            }
        }
    }

    /** 고른 사진을 앱 안에 복사해 둔다(원본 접근 권한이 사라져도 계속 쓰도록). */
    fun setBackground(context: Context, uri: Uri?) {
        val file = backgroundFile(context)
        if (uri == null) {
            file.delete()
            return
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    internal fun render(context: Context, lines: List<String>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(context, canvas, width, height)
        if (lines.isEmpty()) return bitmap

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.045f
            setShadowLayer(width * 0.006f, 0f, 0f, Color.argb(160, 0, 0, 0))
        }
        val padding = width * 0.06f
        val lineGap = textPaint.textSize * 0.9f
        val lineHeight = textPaint.fontSpacing
        val boxInner = width * 0.045f
        val maxTextWidth = width - padding * 2 - boxInner * 2

        // 시계 아래, 화면 위쪽 1/3 지점부터 한 줄씩
        val top = height * 0.30f
        val boxHeight = lines.size * lineHeight + (lines.size - 1) * lineGap + boxInner * 2
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
        canvas.drawRoundRect(
            RectF(padding, top, width - padding, top + boxHeight),
            width * 0.04f, width * 0.04f, boxPaint,
        )

        var baseline = top + boxInner - textPaint.fontMetrics.ascent
        lines.forEach { line ->
            val fitted = fitOneLine(line, textPaint, maxTextWidth)
            canvas.drawText(fitted, padding + boxInner, baseline, textPaint)
            baseline += lineHeight + lineGap
        }
        return bitmap
    }

    /** 폭을 넘으면 뒤를 잘라 "…"을 붙여 항상 한 줄로 만든다. */
    private fun fitOneLine(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val count = paint.breakText(text, true, maxWidth - paint.measureText(ellipsis), null)
        return text.take(count).trimEnd() + ellipsis
    }

    private fun drawBackground(context: Context, canvas: Canvas, width: Int, height: Int) {
        val photo = backgroundFile(context).takeIf { it.exists() }?.let { decodeScaled(it, width, height) }
        if (photo != null) {
            // 화면을 꽉 채우도록 가운데를 잘라서 그린다
            val scale = max(width.toFloat() / photo.width, height.toFloat() / photo.height)
            val srcW = (width / scale).toInt()
            val srcH = (height / scale).toInt()
            val left = (photo.width - srcW) / 2
            val topPx = (photo.height - srcH) / 2
            canvas.drawBitmap(
                photo,
                Rect(left, topPx, left + srcW, topPx + srcH),
                Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            photo.recycle()
            return
        }
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.rgb(0x2B, 0x2D, 0x42), Color.rgb(0x10, 0x10, 0x16), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun decodeScaled(file: File, width: Int, height: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 는 사진의 회전 정보(EXIF)를 반영해 준다
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                var sample = 1
                while (info.size.width / (sample * 2) >= width && info.size.height / (sample * 2) >= height) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
