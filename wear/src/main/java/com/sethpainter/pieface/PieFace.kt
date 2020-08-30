package com.sethpainter.pieface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.palette.graphics.Palette
import java.lang.ref.WeakReference
import java.util.*

private const val INTERACTIVE_UPDATE_RATE_MS = 1000

// Handler message id for updating the time periodically in interactive mode.
private const val MSG_UPDATE_TIME = 0

private const val SHADOW_RADIUS = 6f
private const val AMBIENT_STROKE_WIDTH = 3f

class PieFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: PieFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<PieFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var currentCalendar: Calendar

        private var registeredTimeZoneReceiver = false
        private var centerX: Float = 0F
        private var centerY: Float = 0F
        private var mutedMode = false

        private var pieColor: Int = 0
        private var pieHighlightColor: Int = 0
        private var pieShadowColor: Int = 0

        private lateinit var piePaint: Paint
        private lateinit var timePaint: Paint
        private lateinit var backgroundPaint: Paint
        private lateinit var backgroundBitmap: Bitmap
        private lateinit var mGrayBackgroundBitmap: Bitmap

        private var ambient = false
        private var lowBitAmbient = false
        private var burnInProtection = false

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val timeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                currentCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@PieFace)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            currentCalendar = Calendar.getInstance()

            initializeBackground()
            initializeWatchFace()
        }

        private fun initializeBackground() {
            backgroundPaint = Paint().apply {
                color = Color.BLACK
            }
            backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.bg)

            /* Extracts colors from background image to improve watchface style. */
            Palette.from(backgroundBitmap).generate {
                it?.let {
                    pieHighlightColor = it.getVibrantColor(Color.RED)
                    pieColor = it.getLightVibrantColor(Color.WHITE)
                    pieShadowColor = it.getDarkMutedColor(Color.BLACK)
                    updateWatchHandStyle()
                }
            }
        }

        private fun initializeWatchFace() {
            pieColor = Color.WHITE
            pieHighlightColor = Color.RED
            pieShadowColor = Color.BLACK

            piePaint = Paint().apply {
                color = pieColor
                isAntiAlias = true
                style = Paint.Style.FILL
                setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, pieShadowColor
                )
            }
            timePaint = Paint().apply {
                color = Color.RED
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = 48f
                setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, pieShadowColor
                )
            }
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            lowBitAmbient = properties.getBoolean(
                WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false
            )
            burnInProtection = properties.getBoolean(
                WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false
            )
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            ambient = inAmbientMode
            updateWatchHandStyle()
            // Check and trigger whether or not timer should be running (only in active mode).
            updateTimer()
        }

        private fun updateWatchHandStyle() {
            if (ambient) {
                piePaint.apply {
                    color = Color.WHITE
                    isAntiAlias = false
                    style = Paint.Style.STROKE
                    strokeWidth = AMBIENT_STROKE_WIDTH
                    clearShadowLayer()
                }
                timePaint.apply {
                    isAntiAlias = false
                    clearShadowLayer()
                }
            } else {
                piePaint.apply {
                    color = pieColor
                    style = Paint.Style.FILL
                    isAntiAlias = true
                    setShadowLayer(SHADOW_RADIUS, 0f, 0f, pieShadowColor)
                }
                timePaint.apply {
                    isAntiAlias = true
                    setShadowLayer(SHADOW_RADIUS, 0f, 0f, pieShadowColor)
                }
            }
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            // Dim display in mute mode.
            if (mutedMode != inMuteMode) {
                mutedMode = inMuteMode
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            centerX = width / 2f
            centerY = height / 2f

            // determine scale of bkg
            val scale = width.toFloat() / backgroundBitmap.width.toFloat()

            backgroundBitmap = Bitmap.createScaledBitmap(
                backgroundBitmap,
                (backgroundBitmap.width * scale).toInt(),
                (backgroundBitmap.height * scale).toInt(), true
            )

            // Create a greyscale image if the device type is applicable
            if (!burnInProtection && !lowBitAmbient) {
                mGrayBackgroundBitmap = Bitmap.createBitmap(
                    backgroundBitmap.width,
                    backgroundBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(mGrayBackgroundBitmap)
                val grayPaint = Paint()
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f)
                val filter = ColorMatrixColorFilter(colorMatrix)
                grayPaint.colorFilter = filter
                canvas.drawBitmap(backgroundBitmap, 0f, 0f, grayPaint)
            }
        }

        // When the user taps
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TAP ->
                    Toast.makeText(applicationContext, R.string.message, Toast.LENGTH_SHORT).show()
            }
            invalidate()
        }


        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            currentCalendar.timeInMillis = now

            drawBackground(canvas)
            drawWatchFace(canvas)
        }

        private fun drawBackground(canvas: Canvas) {
            if (ambient && (lowBitAmbient || burnInProtection))
                canvas.drawColor(Color.BLACK)
            else if (ambient)
                canvas.drawBitmap(mGrayBackgroundBitmap, 0f, 0f, backgroundPaint)
            else
                canvas.drawBitmap(backgroundBitmap, 0f, 0f, backgroundPaint)
        }

        private fun drawWatchFace(canvas: Canvas) {
            canvas.save()
            // Converting x / 60 to x / 360
            val sweep = (360f * (currentCalendar.get(Calendar.SECOND) / 60f))
            val arcAngle = (sweep - 360f)
            canvas.drawArc(
                0f, 0f, canvas.width.toFloat(),
                canvas.height.toFloat(), -90f, arcAngle, true, piePaint
            )
            canvas.drawText(
                "${currentCalendar.get(Calendar.HOUR)}:${currentCalendar.get(Calendar.MINUTE)}:${
                    currentCalendar.get(
                        Calendar.SECOND
                    )
                }",
                centerX, centerY, timePaint
            )

            canvas.restore() // Restore the canvas' original orientation.
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                // Update time zone in case it changed while we weren't visible.
                currentCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else
                unregisterReceiver()

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (!registeredTimeZoneReceiver) {
                registeredTimeZoneReceiver = true
                val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
                this@PieFace.registerReceiver(timeZoneReceiver, filter)
            }
        }

        private fun unregisterReceiver() {
            if (registeredTimeZoneReceiver) {
                registeredTimeZoneReceiver = false
                this@PieFace.unregisterReceiver(timeZoneReceiver)
            }
        }

        // Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        // Returns whether the [.mUpdateTimeHandler] timer should be running. The timer should only run in active mode.
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !ambient
        }

        // Handle updating the time periodically in interactive mode.
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }
}
