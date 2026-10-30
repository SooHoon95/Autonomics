package com.drivesplit.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drivesplit.app.trip.TripState
import com.drivesplit.core.FuelSource
import kotlin.math.roundToLong

@Composable
fun ResultScreen(state: TripState.Finished, vm: MainViewModel) {
    val s = state.settlement
    val passengers = state.setup.passengers
    val names = passengers.associate { it.id to it.name }
    val url = remember(state) { vm.shareUrl(state) }
    val qr = remember(url) { qrBitmap(url) }

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1.3f).fillMaxHeight()) {
            Text("정산 완료", style = MaterialTheme.typography.titleLarge, color = Palette.amber)
            Text(won(s.totalWon), style = MaterialTheme.typography.displayLarge)
            Text(
                "${km(s.totalDistanceKm)} · ${liters(s.totalLiters)} · 리터당 ${won(state.setup.options.pricePerLiter.roundToLong())}",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                passengers.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(p.name + if (p.isDriver) " (운전)" else "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text(won(s.shares[p.id] ?: 0), style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider(color = Palette.line)
                }
                Spacer(Modifier.height(20.dp))
                Text("구간별 내역", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                s.segmentCosts.forEachIndexed { i, cost ->
                    val who = cost.segment.occupantIds.mapNotNull { names[it] }.joinToString(", ")
                    val source = if (cost.fuel.source == FuelSource.MEASURED) "실측" else "추정"
                    Text(
                        "구간 ${i + 1} · ${km(cost.segment.distanceKm)} · ${won(cost.costWon.roundToLong())} ($source) · $who",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("동승자 폰으로 찍으세요", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Image(
                    bitmap = qr,
                    contentDescription = "정산 결과 QR 코드",
                    modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(16.dp))
                BigButton("완료", onClick = vm::closeResult, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
