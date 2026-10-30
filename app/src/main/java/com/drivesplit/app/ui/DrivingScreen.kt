package com.drivesplit.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesplit.app.trip.TripState

/** 택시 미터기처럼 링이 1만 원마다 한 바퀴 찬다. */
private const val WON_PER_LAP = 10_000L

@Composable
fun DrivingScreen(state: TripState.Active, vm: MainViewModel) {
    val holdStop by vm.holdStop.collectAsStateWithLifecycle()
    val live = state.live
    val passengers = state.setup.passengers

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        // 왼쪽: 주행 중에도 한눈에 읽는 미터기
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CostMeter(totalWon = live.totalWon, modifier = Modifier.fillMaxHeight(0.78f))
            Spacer(Modifier.height(16.dp))
            Text(
                "${km(live.totalDistanceKm)} · ${liters(live.totalLiters)} · ${state.speedKmh.toInt()}km/h",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
        }

        // 오른쪽: 1인당 금액과 하차 처리. 조작은 정차 중에만 열린다.
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("지금까지 1인당", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    if (state.isStopped) "정차 중" else "주행 중",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.isStopped) Palette.go else Palette.amber,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                passengers.forEach { p ->
                    val aboard = p.id in state.occupantIds
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = aboard,
                            enabled = state.isStopped && !p.isDriver,
                            onClick = {
                                vm.setOccupants(if (aboard) state.occupantIds - p.id else state.occupantIds + p.id)
                            },
                            label = {
                                Text(
                                    p.name + if (p.isDriver) " (운전)" else if (aboard) "" else " · 내림",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.line,
                                selectedLabelColor = Palette.text,
                            ),
                            modifier = Modifier.height(56.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(won(live.shares[p.id] ?: 0), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Text(
                if (state.isStopped) "내린 사람을 누르면 다음 구간부터 빠져요" else "정차하면 하차 처리와 종료를 할 수 있어요",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.muted,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 시뮬레이터 전용: 실차에서는 실제 정차로 대체된다.
                GhostButton(if (holdStop) "출발 (시연)" else "정차 (시연)", onClick = { vm.setHoldStop(!holdStop) })
                BigButton("여행 종료", onClick = vm::finishTrip, enabled = state.isStopped, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CostMeter(totalWon: Long, modifier: Modifier = Modifier) {
    val lap = (totalWon % WON_PER_LAP).toFloat() / WON_PER_LAP
    val sweep by animateFloatAsState(targetValue = lap, label = "meter")

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = size.minDimension * 0.06f, cap = StrokeCap.Round)
            drawArc(Palette.line, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = stroke)
            drawArc(Palette.amber, startAngle = 135f, sweepAngle = 270f * sweep, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("총 연료비", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
            Text(
                won(totalWon),
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = Palette.text,
            )
        }
    }
}
