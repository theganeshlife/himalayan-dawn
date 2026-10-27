package app.himalayandawn.tv

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A 16:9 app tile with its label underneath; grows slightly when focused. */
object Tiles {
    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun make(
        context: Context,
        label: String,
        banner: Drawable?,
        icon: Drawable?,
        widthDp: Float = 112f,
        tintIcon: Boolean = false,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ): View {
        val w = dp(context, widthDp)
        val h = w * 9 / 16
        val radius = dp(context, 12f).toFloat()

        val frame = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.tile_bg)
            isFocusable = true
            isClickable = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        if (banner != null) {
            frame.addView(ImageView(context).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
                isDuplicateParentStateEnabled = true
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (icon != null) {
            val s = (h * 0.6f).toInt()
            frame.addView(ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isDuplicateParentStateEnabled = true
                if (tintIcon) imageTintList = context.getColorStateList(R.color.tile_icon)
            }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }

        val text = TextView(context).apply {
            this.text = label
            setTextColor(context.getColor(R.color.snow_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = context.resources.getFont(R.font.mukta_regular)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(frame, LinearLayout.LayoutParams(w, h))
            addView(text, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 6f) })
        }

        frame.setOnClickListener { onClick() }
        if (onLongClick != null) frame.setOnLongClickListener { onLongClick(); true }
        frame.setOnFocusChangeListener { _, focused ->
            val scale = if (focused) 1.1f else 1f
            frame.scaleX = scale
            frame.scaleY = scale
            frame.elevation = if (focused) dp(context, 8f).toFloat() else 0f
            text.setTextColor(context.getColor(if (focused) R.color.snow else R.color.snow_dim))
        }
        wrapper.tag = frame
        return wrapper
    }

    /** The focusable part of a tile made by [make]. */
    fun focusTarget(tile: View): View = tile.tag as View
}
