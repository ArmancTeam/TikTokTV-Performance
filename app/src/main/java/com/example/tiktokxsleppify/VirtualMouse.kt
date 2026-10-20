package com.example.tiktokxsleppify

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * D-pad driven pointer for the WebView: physics-based cursor, synthetic touch events and
 * edge-scrolling (push the cursor against the top/bottom edge to scroll).
 *
 * @param onEdgeScroll called with (cursorX, cursorY, dy) when the cursor is held against a vertical edge.
 */
class VirtualMouse(
    private val activity: Activity,
    private val webView: WebView,
    private val onEdgeScroll: (x: Float, y: Float, dy: Int) -> Unit
) {

    var isEnabled = false
        private set

    /** Color of the on-screen cursor. */
    var cursorColor: Int = Color.parseColor("#FE2C55")
        set(value) {
            field = value
            view?.invalidate()
        }

    /** Neon halo enabled in the cursor. */
    var neonEnabled: Boolean = true
        set(value) { field = value; view?.invalidate() }

    /** Neon glow strength from 25 to 100 percent. */
    var neonIntensity: Int = 75
        set(value) { field = value.coerceIn(25, 100); view?.invalidate() }

    /** 0.5x .. 2.0x, scales acceleration and top speed. */
    var speedMultiplier = 1.0f

    /** Opt-in: scroll when the cursor is pinned against the top/bottom edge for a moment. */
    var edgeScrollEnabled = false

    val x: Float get() = posX
    val y: Float get() = posY

    private var view: View? = null
    private var posX = 960f
    private var posY = 540f
    private var holdingOk = false
    private var touchDownTime = 0L
    private var lastEdgeScroll = 0L
    private var edgePinnedSince = 0L
    private var lastHoverAt = 0L
    private var hoverDirty = false
    private val handler = Handler(Looper.getMainLooper())

    // Physics
    private var vx = 0f
    private var vy = 0f
    private var accX = 0f
    private var accY = 0f
    private val maxSpeed = 26f        // crosses 1080p in ~1.2 s
    private val acceleration = 1.6f
    private val friction = 0.82f      // slides ~120 px after release

    private val loop = object : Runnable {
        override fun run() {
            if (!isEnabled) return

            vx += accX * speedMultiplier
            vy += accY * speedMultiplier
            if (accX == 0f) vx *= friction
            if (accY == 0f) vy *= friction

            val maxV = maxSpeed * speedMultiplier
            vx = vx.coerceIn(-maxV, maxV)
            vy = vy.coerceIn(-maxV, maxV)

            if (accX == 0f && abs(vx) < 0.4f) vx = 0f
            if (accY == 0f && abs(vy) < 0.4f) vy = 0f

            if (abs(vx) > 0.4f || abs(vy) > 0.4f) {
                val w = webView.width.toFloat()
                val h = webView.height.toFloat()
                val nextX = posX + vx
                val nextY = posY + vy
                if (nextX < 0f || nextX > w) { vx = 0f; posX = nextX.coerceIn(0f, w) } else posX = nextX
                if (nextY < 0f || nextY > h) { vy = 0f; posY = nextY.coerceIn(0f, h) } else posY = nextY

                view?.translationX = posX
                view?.translationY = posY
                if (holdingOk) dispatchTouch(MotionEvent.ACTION_MOVE) else hoverDirty = true
            }

            flushHover()
            handleEdgeScroll()
            handler.postDelayed(this, 16)
        }
    }

    /**
     * Only scrolls when the cursor is pinned exactly on the edge (it clamps there), the matching direction
     * key is still held, and this has lasted [EDGE_DWELL_MS]. Merely reaching the edge to click something
     * (progress bar, search icon, modal close button) never scrolls.
     */
    private fun handleEdgeScroll() {
        if (!edgeScrollEnabled || holdingOk) { edgePinnedSince = 0L; return }
        val h = webView.height.toFloat()
        if (h <= 0f) return
        val atTop = accY < 0f && posY <= EDGE_PIN_PX
        val atBottom = accY > 0f && posY >= h - EDGE_PIN_PX
        if (!atTop && !atBottom) { edgePinnedSince = 0L; return }
        val now = SystemClock.uptimeMillis()
        if (edgePinnedSince == 0L) { edgePinnedSince = now; return }
        if (now - edgePinnedSince < EDGE_DWELL_MS) return
        if (now - lastEdgeScroll < EDGE_SCROLL_INTERVAL_MS) return
        lastEdgeScroll = now
        onEdgeScroll(posX, posY, if (atTop) -EDGE_SCROLL_STEP else EDGE_SCROLL_STEP)
    }

    /**
     * TikTok's desktop UI relies on :hover (video controls, progress bar, share menu). A touch-only cursor
     * never produces hover, so send real mouse hover events while the cursor moves (throttled).
     */
    private fun flushHover() {
        if (!hoverDirty || holdingOk) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHoverAt < HOVER_INTERVAL_MS) return
        lastHoverAt = now
        hoverDirty = false
        try {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = posX
                y = posY
                pressure = 0f
                size = 1f
            })
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            webView.dispatchGenericMotionEvent(ev)
            ev.recycle()
        } catch (e: Exception) {
            // Hover is a nicety; never let it break the cursor.
        }
    }

    fun toggle() {
        isEnabled = !isEnabled
        if (isEnabled) {
            ensureView()
            posX = webView.width / 2f
            posY = webView.height / 2f
            view?.visibility = View.VISIBLE
            view?.translationX = posX
            view?.translationY = posY
            edgePinnedSince = 0L
            hoverDirty = true
            handler.post(loop)
        } else {
            view?.visibility = View.GONE
            handler.removeCallbacks(loop)
            accX = 0f; accY = 0f; vx = 0f; vy = 0f
            if (holdingOk) {
                holdingOk = false
                dispatchTouch(MotionEvent.ACTION_UP)
                view?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(150)?.start()
            }
        }
    }

    /** D-pad key pressed. Returns true when the key moved the cursor. */
    fun onDirectionDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> accY = -acceleration
            KeyEvent.KEYCODE_DPAD_DOWN -> accY = acceleration
            KeyEvent.KEYCODE_DPAD_LEFT -> accX = -acceleration
            KeyEvent.KEYCODE_DPAD_RIGHT -> accX = acceleration
            else -> return false
        }
        return true
    }

    /** D-pad key released: only acceleration is cleared so friction gives the glide. */
    fun onDirectionUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> accY = 0f
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> accX = 0f
            else -> return false
        }
        return true
    }

    fun pressOk() {
        if (!isEnabled) return
        holdingOk = true
        dispatchTouch(MotionEvent.ACTION_DOWN)
        view?.animate()?.scaleX(0.85f)?.scaleY(0.85f)?.alpha(0.8f)?.setDuration(120)?.start()
    }

    fun releaseOk() {
        if (!isEnabled || !holdingOk) return
        dispatchTouch(MotionEvent.ACTION_UP)
        holdingOk = false
        view?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(250)?.start()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        (view?.parent as? FrameLayout)?.removeView(view)
        view = null
    }

    private fun dispatchTouch(action: Int) {
        val eventTime = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = eventTime
        val event = MotionEvent.obtain(touchDownTime, eventTime, action, posX, posY, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun ensureView() {
        if (view != null) return
        val size = (activity.resources.displayMetrics.heightPixels * 0.055f).toInt().coerceIn(56, 110)
        val pin = object : View(activity) {
            private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cursorColor
                style = Paint.Style.FILL
            }
            private val neonGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cursorColor
                style = Paint.Style.STROKE
                strokeWidth = size * 0.10f
                setShadowLayer(size * 0.28f, 0f, 0f, cursorColor)
            }
            private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#40000000")
                style = Paint.Style.FILL
            }
            private val path = Path()
            private val tri = Path()

            private fun buildPin(w: Float) {
                val cx = w * 0.53f
                val cy = w * 0.53f
                val r = w * 0.42f
                path.reset()
                path.addCircle(cx, cy, r, Path.Direction.CW)
                tri.reset()
                tri.moveTo(0f, 0f) // tip = exact hotspot (top-left corner of the view)
                tri.lineTo(cx - r * 0.28f, cy - r * 0.88f)
                tri.lineTo(cx - r * 0.88f, cy - r * 0.28f)
                tri.close()
                path.op(tri, Path.Op.UNION)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                body.color = cursorColor
                val w = width.toFloat()
                buildPin(w)
                // Adjustable neon halo + bright outline around the pointer.
                if (neonEnabled) {
                    neonGlow.color = (cursorColor and 0x00FFFFFF) or
                        (((neonIntensity * 255 / 100).coerceIn(0, 255)) shl 24)
                    neonGlow.strokeWidth = size * (0.04f + neonIntensity / 1000f)
                    neonGlow.setShadowLayer(size * (0.10f + neonIntensity / 350f), 0f, 0f, cursorColor)
                    canvas.drawPath(path, neonGlow)
                }
                canvas.drawPath(path, body)
                canvas.drawCircle(w * 0.53f, w * 0.53f, w * 0.15f, hole)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            elevation = 100f
            setLayerType(View.LAYER_TYPE_SOFTWARE, null) // needed for setShadowLayer
        }
        view = pin
        (activity.findViewById<View>(android.R.id.content) as? FrameLayout)?.addView(pin)
    }

    private companion object {
        const val EDGE_PIN_PX = 1f
        const val EDGE_DWELL_MS = 450L
        const val EDGE_SCROLL_STEP = 240
        const val EDGE_SCROLL_INTERVAL_MS = 160L
        const val HOVER_INTERVAL_MS = 40L
    }
}
