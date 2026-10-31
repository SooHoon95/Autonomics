package com.drivesplit.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesplit.app.trip.TripMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DeleteRed = Color(0xFFFF6B5B)

@Composable
fun HomeScreen(vm: MainViewModel) {
    val recent by vm.recentTrips.collectAsStateWithLifecycle()
    val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // 기록이 다 지워지면 편집 모드도 끝낸다.
    LaunchedEffect(recent.isEmpty()) {
        if (recent.isEmpty()) {
            editing = false
            confirmClear = false
        }
    }

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("DriveSplit", style = MaterialTheme.typography.titleLarge, color = Palette.amber)
            Spacer(Modifier.height(12.dp))
            Text("실제로 태운 기름만큼\n계산해요", style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp))
            Spacer(Modifier.height(16.dp))
            Text(
                "차의 연료 센서로 실제 소모량과 주행거리를 기록해요.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModeButton("정산 모드", "동승자와 탄 구간만큼 나누기", Modifier.weight(1f)) { vm.openSetup(TripMode.SPLIT) }
                ModeButton("솔로 모드", "내 운전 효율과 기름값 보기", Modifier.weight(1f)) { vm.openSetup(TripMode.SOLO) }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("최근 기록", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (recent.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            editing = !editing
                            confirmClear = false
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                    ) {
                        Text(if (editing) "완료" else "편집", style = MaterialTheme.typography.bodyLarge, color = Palette.amber)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (recent.isEmpty()) {
                Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(recent, key = { it.id }) { trip ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val modeLabel = if (trip.mode == TripMode.SOLO.name) "솔로" else "정산"
                            Text("$modeLabel · ${dateFormat.format(Date(trip.endedAtMs))}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${km(trip.distanceKm)} · ${liters(trip.liters)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.muted,
                            )
                        }
                        Text(won(trip.totalWon), style = MaterialTheme.typography.titleLarge)
                        if (editing) {
                            IconButton(onClick = { vm.deleteTrip(trip.id) }, modifier = Modifier.padding(start = 12.dp).size(64.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "기록 삭제", tint = DeleteRed, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = Palette.line)
                }
            }
            if (editing) {
                Spacer(Modifier.height(16.dp))
                // 전체 삭제는 되돌릴 수 없으니 한 번 더 누르게 한다.
                GhostButton(
                    if (confirmClear) "한 번 더 누르면 모두 지워요" else "기록 전체 삭제",
                    onClick = {
                        if (confirmClear) vm.deleteAllTrips() else confirmClear = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModeButton(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 120.dp),
        color = Palette.panel,
        border = BorderStroke(2.dp, Palette.amber),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.amber)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
    }
}
