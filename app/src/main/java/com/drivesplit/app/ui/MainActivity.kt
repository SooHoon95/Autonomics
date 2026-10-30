package com.drivesplit.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drivesplit.app.DriveSplitApp
import com.drivesplit.app.trip.TripState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DriveSplitTheme {
                Box(Modifier.fillMaxSize().background(Palette.ink)) {
                    AppRoot()
                }
            }
        }
    }
}

private val factory = viewModelFactory {
    initializer { MainViewModel(this[APPLICATION_KEY] as DriveSplitApp) }
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel(factory = factory)) {
    val trip by vm.trip.collectAsStateWithLifecycle()
    val showSetup by vm.showSetup.collectAsStateWithLifecycle()

    when (val state = trip) {
        is TripState.Active -> DrivingScreen(state, vm)
        is TripState.Finished -> ResultScreen(state, vm)
        TripState.Idle -> if (showSetup) SetupScreen(vm) else HomeScreen(vm)
    }
}
