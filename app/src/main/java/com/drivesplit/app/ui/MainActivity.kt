package com.drivesplit.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drivesplit.app.AutonomicsApp
import com.drivesplit.domain.trip.TripMode
import com.drivesplit.domain.trip.TripState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutonomicsTheme {
                // Surface가 기본 글자색(contentColor)을 내려줘야 색을 지정 안 한 Text도 다크 배경에서 보인다.
                Surface(Modifier.fillMaxSize(), color = Palette.ink, contentColor = Palette.text) {
                    AppRoot()
                }
            }
        }
    }
}

private val factory = viewModelFactory {
    initializer { MainViewModel(this[APPLICATION_KEY] as AutonomicsApp) }
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel(factory = factory)) {
    val trip by vm.trip.collectAsStateWithLifecycle()
    val showSetup by vm.showSetup.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    when (val state = trip) {
        is TripState.Active -> when (state.setup.mode) {
            TripMode.SPLIT -> DrivingScreen(state, vm)
            TripMode.SOLO -> SoloDrivingScreen(state, vm)
        }
        is TripState.Finished -> FinishedScreen(state, vm, onClose = vm::closeResult)
        TripState.Idle -> {
            val past = history
            when {
                past != null -> FinishedScreen(past, vm, onClose = vm::closeHistory, closeLabel = "닫기")
                showSetup -> SetupScreen(vm)
                else -> HomeScreen(vm)
            }
        }
    }
}

/** 방금 끝난 여행과 지난 기록이 같은 결과 화면을 쓴다. */
@Composable
private fun FinishedScreen(state: TripState.Finished, vm: MainViewModel, onClose: () -> Unit, closeLabel: String = "완료") {
    when (state.setup.mode) {
        TripMode.SPLIT -> ResultScreen(state, vm, onClose, closeLabel)
        TripMode.SOLO -> SoloResultScreen(state, vm, onClose, closeLabel)
    }
}
