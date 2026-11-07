package io.github.norlive.remotecontrol

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Bound fullscreen dialogs to their caller's measured window, not display configuration. */
@Composable
internal fun SettingsWindow(onClose: () -> Unit, content: @Composable () -> Unit) {
    // Capture the caller before Dialog introduces a separate Android view/window.
    val host = LocalView.current
    var size by remember(host) { mutableStateOf(IntSize(host.width, host.height)) }
    DisposableEffect(host) {
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            size = IntSize(view.width, view.height)
        }
        host.addOnLayoutChangeListener(listener)
        size = IntSize(host.width, host.height)
        onDispose { host.removeOnLayoutChangeListener(listener) }
    }
    val density = LocalDensity.current
    val width = with(density) { if (size.width > 0) size.width.toDp() else Dp.Infinity }
    val height = with(density) { if (size.height > 0) size.height.toDp() else Dp.Infinity }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.sizeIn(maxWidth = width, maxHeight = height)) { content() }
    }
}
