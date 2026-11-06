package com.drivesplit.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.drivesplit.pleos.SpeechInput

/** 화면이 떠 있는 동안만 음성 인식기를 쥐고 있다가 화면을 떠나면 놓는다. */
@Composable
fun rememberSpeechInput(): SpeechInput {
    val context = LocalContext.current
    val input = remember { SpeechInput(context) }
    DisposableEffect(input) { onDispose { input.destroy() } }
    return input
}
