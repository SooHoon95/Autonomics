package com.drivesplit.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drivesplit.domain.trip.TripState
import com.drivesplit.domain.Segment
import kotlin.math.roundToLong

@Composable
fun ResultScreen(state: TripState.Finished, vm: MainViewModel, onClose: () -> Unit, closeLabel: String) {
    val s = state.figures.settlement
    val passengers = state.setup.passengers
    val powertrain = state.setup.profile.powertrain
    val names = passengers.associate { it.id to it.name }
    val url = remember(state) { vm.shareUrl(state) }
    val qr = remember(url) { qrBitmap(url) }
    var selected by remember(state) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("정산 완료", style = MaterialTheme.typography.titleLarge, color = Palette.amber)
                Text(won(s.totalWon), style = MaterialTheme.typography.displayLarge)
                Text(
                    "${km(s.totalDistanceKm)} · ${powertrain.energyWord} ${energy(s.totalEnergy, powertrain)} ${sourceLabel(s.isMeasured)} · " +
                        "${powertrain.perUnitWord} ${won(state.setup.options.pricePerUnit.roundToLong())}" +
                        (s.kmPerUnit?.let { " · 실제 ${powertrain.efficiencyWord} ${efficiency(it, powertrain)}" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.muted,
                )
                Spacer(Modifier.height(24.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    passengers.forEach { p ->
                        // 누르면 그 사람만의 정산서와 QR을 띄운다.
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = p.id }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val tag = when {
                                p.isDriver -> " (운전)"
                                p.id in state.settledShares -> " · 하차 때 정산함"
                                else -> ""
                            }
                            Text(p.name + tag, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text(won(s.shares[p.id] ?: 0), style = MaterialTheme.typography.titleLarge)
                            Text("  ›", style = MaterialTheme.typography.titleLarge, color = Palette.muted)
                        }
                        HorizontalDivider(color = Palette.line)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("이름을 누르면 사람별 정산서를 볼 수 있어요", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    Spacer(Modifier.height(12.dp))
                    Text("구간별 내역", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                    s.segmentCosts.forEachIndexed { i, cost ->
                        val who = cost.segment.occupantIds.mapNotNull { names[it] }.joinToString(", ")
                        val source = if (cost.energy.source.isMeasured) "실측" else "추정"
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 지도 위 경로 색과 맞춘다.
                            Box(Modifier.size(14.dp).background(segmentColor(i), CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${segmentTitle(i, cost.segment.path, vm)} · ${km(cost.segment.distanceKm)} · ${energy(cost.energy.amount, powertrain)} $source · ${won(cost.costWon.roundToLong())} · $who",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Text("지나온 길", style = MaterialTheme.typography.titleLarge)
                RouteMap(
                    lines = s.segmentCosts.mapIndexed { i, cost -> RouteLine(cost.segment.path, segmentColor(i)) },
                    markers = dropOffMarkers(s.segmentCosts.map { it.segment }, names),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Panel(Modifier.weight(0.7f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("동승자 폰으로 찍으세요", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Image(
                        bitmap = qr,
                        contentDescription = "정산 결과 QR 코드",
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    BigButton(closeLabel, onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        selected?.let { id ->
            val person = passengers.firstOrNull { it.id == id }
            val personUrl = remember(state, id) { vm.personUrl(state.setup, s, id) }
            if (person != null && personUrl != null) {
                PersonSettlementCard(
                    title = "${person.name} 님 정산" + if (id in state.settledShares) " (하차 때 확정)" else "",
                    settlement = s,
                    powertrain = powertrain,
                    passengerId = id,
                    url = personUrl,
                    note = null,
                    qrCaption = "${person.name} 님 폰으로 찍으세요",
                    vm = vm,
                    onDismiss = { selected = null },
                )
            }
        }
        }
    }

    /** 구간이 바뀔 때 내린 사람을 그 지점에 표시한다. */
    private fun dropOffMarkers(segments: List<Segment>, names: Map<String, String>): List<RouteMarker> =
        segments.zipWithNext().mapNotNull { (before, after) ->
            val left = (before.occupantIds - after.occupantIds).mapNotNull { names[it] }
            val point = after.path.firstOrNull() ?: before.path.lastOrNull()
            if (left.isEmpty() || point == null) null else RouteMarker(point, "${left.joinToString(", ")} 하차", Palette.go)
    }
