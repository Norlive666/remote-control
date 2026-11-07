package io.github.norlive.remotecontrol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Settings are pages within the measured activity, without a separately positioned window. */
@Composable
internal fun SettingsWindow(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) { content() }
}
