package dev.whispr.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * While this is in the composition, the hosting view ignores touches that
 * arrive while another app's window covers it (tapjacking). Used where one
 * tap confirms something security-relevant.
 */
@Composable
fun FilterObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = previous }
    }
}
