package com.drivesplit.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel) {
    val recent by vm.recentTrips.collectAsStateWithLifecycle()
    val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("DriveSplit", style = MaterialTheme.typography.titleLarge, color = Palette.amber)
            Spacer(Modifier.height(12.dp))
            Text("기름값, 탄 만큼만\n나눠요", style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp))
            Spacer(Modifier.height(16.dp))
            Text(
                "차가 주행거리와 연료를 기록하고, 중간에 내린 사람은 탄 구간만 계산해요.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
            Spacer(Modifier.height(40.dp))
            BigButton("여행 시작", onClick = vm::openSetup)
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Text("최근 정산", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (recent.isEmpty()) {
                Text("아직 정산한 여행이 없어요.", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
            }
            LazyColumn {
                items(recent, key = { it.id }) { trip ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dateFormat.format(Date(trip.endedAtMs)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${km(trip.distanceKm)} · ${liters(trip.liters)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.muted,
                            )
                        }
                        Text(won(trip.totalWon), style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider(color = Palette.line)
                }
            }
        }
    }
}
