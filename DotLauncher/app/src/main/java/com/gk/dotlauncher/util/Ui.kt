package com.gk.dotlauncher.util

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.gk.dotlauncher.R

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dp(v: Int): Int = dp(v.toFloat()).toInt()
fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
fun View.dp(v: Float): Float = context.dp(v)
fun View.dp(v: Int): Int = context.dp(v)
fun View.sp(v: Float): Float = context.sp(v)

fun View.buzz() {
    if (Prefs(context).haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

object Fonts {
    private var doto: Typeface? = null
    private var mono: Typeface? = null
    fun doto(c: Context): Typeface = doto ?: (ResourcesCompat.getFont(c, R.font.doto) ?: Typeface.MONOSPACE).also { doto = it }
    fun mono(c: Context): Typeface = mono ?: (ResourcesCompat.getFont(c, R.font.space_mono) ?: Typeface.MONOSPACE).also { mono = it }
}

object Palette {
    const val CARD = 0xFF141414.toInt()
    const val CARD_ALT = 0xFF1E1E1E.toInt()
    const val DOT_OFF = 0xFF262626.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val DIM = 0xFF8A8A8A.toInt()
}
