package iot.guitar.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Draw behind the system bars (call before setContentView). */
fun AppCompatActivity.enableEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

/**
 * Adds the system-bar insets to this view's own padding so content never sits under the
 * status/navigation bars. Safe to call once per view; keeps the layout padding as a base.
 */
fun View.applySystemBarInsetsAsPadding(top: Boolean = true, bottom: Boolean = true) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(
            baseLeft + bars.left,
            baseTop + if (top) bars.top else 0,
            baseRight + bars.right,
            baseBottom + if (bottom) bars.bottom else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
