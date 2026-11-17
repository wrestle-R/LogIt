package dev.logit.app

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator

/** Two visible modes with a sliding selection; the 48dp touch target exceeds the track. */
class ThemeToggle(private val owner: Activity, private val change: () -> Unit) : View(owner) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Theme.light()
    private var position = if (light) 0f else 1f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Dark mode"
        tooltipText = if (light) "Use dark theme" else "Use light theme"
        if (android.os.Build.VERSION.SDK_INT >= 30)
            stateDescription = if (light) "Light theme" else "Dark theme"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        paint.style = Paint.Style.FILL
        paint.color = if (light) 0xffe4e4e7.toInt() else 0xff18181b.toInt()
        canvas.drawRoundRect(1f, 6f, 79f, 42f, 18f, 18f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (light) 0xffd4d4d8.toInt() else 0xff3f3f46.toInt()
        canvas.drawRoundRect(1f, 6f, 79f, 42f, 18f, 18f, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (light) 0xffffffff.toInt() else 0xff3f3f46.toInt()
        canvas.drawCircle(21f + 38f * position, 24f, 15f, paint)
        paint.color = if (light) 0xff18181b.toInt() else 0xffa1a1aa.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(21f, 24f, 3.5f, paint)
        for (i in 0..7) {
            val angle = i * Math.PI / 4
            val x = kotlin.math.cos(angle).toFloat()
            val y = kotlin.math.sin(angle).toFloat()
            canvas.drawLine(21f + x * 6f, 24f + y * 6f, 21f + x * 8f, 24f + y * 8f, paint)
        }
        val moon = Path().apply { addCircle(59f, 24f, 7f, Path.Direction.CW) }
        moon.op(Path().apply { addCircle(62.5f, 21f, 6.5f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        paint.style = Paint.Style.FILL
        paint.color = if (light) 0xff71717a.toInt() else 0xfffafafa.toInt()
        canvas.drawPath(moon, paint)
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Theme.text
            canvas.drawRoundRect(1f, 3f, 79f, 45f, 21f, 21f, paint)
        }
        canvas.restore()
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (animator != null) return true
        val target = if (light) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            change()
            return true
        }
        animator =
            ValueAnimator.ofFloat(position, target).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            change()
                        }
                    }
                )
                start()
            }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = !light
    }

    override fun onDetachedFromWindow() {
        animator?.removeAllListeners()
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
