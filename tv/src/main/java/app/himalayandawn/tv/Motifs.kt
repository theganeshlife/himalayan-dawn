package app.himalayandawn.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** A band of Dhaka-weave diamonds: filled rose, open saffron, filled saffron, open rose. */
class DhakaBandView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rose = context.getColor(R.color.rose)
    private val saffron = context.getColor(R.color.saffron)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        line.strokeWidth = h / 8f
        var x = 0f
        var i = 0
        while (x < width) {
            path.reset()
            path.moveTo(x, h / 2); path.lineTo(x + h / 2, 0f); path.lineTo(x + h, h / 2); path.lineTo(x + h / 2, h); path.close()
            when (i % 4) {
                0 -> { fill.color = rose; canvas.drawPath(path, fill) }
                1 -> { line.color = saffron; canvas.drawPath(path, line) }
                2 -> { fill.color = saffron; canvas.drawPath(path, fill) }
                else -> { line.color = rose; canvas.drawPath(path, line) }
            }
            x += h
            i++
        }
    }
}

/** A strip of Newari ankhijhyal lattice: crossed diagonals with small diamonds. */
class LatticeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.wood) }

    override fun onDraw(canvas: Canvas) {
        val s = height.toFloat()
        paint.strokeWidth = s / 11f
        var x = 0f
        while (x < width) {
            paint.alpha = 170
            canvas.drawLine(x, 0f, x + s, s, paint)
            canvas.drawLine(x + s, 0f, x, s, paint)
            paint.alpha = 210
            val c = s / 2
            val r = s / 5
            canvas.save()
            canvas.rotate(45f, x + c, c)
            canvas.drawRect(x + c - r / 1.4f, c - r / 1.4f, x + c + r / 1.4f, c + r / 1.4f, paint)
            canvas.restore()
            x += s
        }
    }
}
