package com.drivesplit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesplit.app.trip.TripState
import com.drivesplit.core.DrivingReport
import com.drivesplit.core.SegmentEfficiency
import java.util.Locale

/** 효율 막대는 공인 연비의 150%를 끝으로 잡는다. */
private const val BAR_MAX_PERCENT = 150f

private val Bad = Color(0xFFFF6B5B)

private fun scoreColor(score: Int?): Color = when {
    score == null -> Palette.muted
    score >= 100 -> Palette.go
    score >= 85 -> Palette.amber
    else -> Bad
}

@Composable
fun SoloDrivingScreen(state: TripState.Active, vm: MainViewModel) {
    val holdStop by vm.holdStop.collectAsStateWithLifecycle()
    val report = state.live.report
    val rated = state.setup.profile.efficiencyKmPerLiter

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CostMeter(totalWon = report.costWon, label = "태운 기름값", modifier = Modifier.fillMaxHeight(0.78f))
            Spacer(Modifier.height(16.dp))
            Text(
                "${km(report.totalKm)} · ${liters(report.totalLiters)} ${sourceLabel(report.isMeasured)} · ${state.speedKmh.toInt()}km/h",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
        }

        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Text("실제 연비", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    report.kmPerLiter?.let { kmpl(it) } ?: "측정 중",
                    style = MaterialTheme.typography.displayLarge,
                    color = scoreColor(report.scorePercent),
                )
            }
            Text(
                report.scorePercent?.let { "공인 ${kmpl(rated)} 대비 $it%" } ?: "조금 더 달리면 계산돼요",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
            Spacer(Modifier.height(20.dp))
            Text("구간별 효율", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 최근 구간이 위로 오게. 마지막 구간은 아직 진행 중이다.
                val paths = state.live.settlement.segmentCosts.map { it.segment.path }
                report.segments.withIndex().reversed().forEach { (i, seg) ->
                    val current = i == report.segments.lastIndex
                    val title = segmentTitle(i, paths.getOrElse(i) { emptyList() }, vm, ongoing = current)
                    SegmentBar(label = title + if (current) " 진행 중" else "", seg = seg)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(if (holdStop) "출발 (시연)" else "정차 (시연)", onClick = { vm.setHoldStop(!holdStop) })
                BigButton("운전 종료", onClick = vm::finishTrip, enabled = state.isStopped, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SoloResultScreen(state: TripState.Finished, vm: MainViewModel, onClose: () -> Unit, closeLabel: String) {
    val report = state.figures.report
    val rated = state.setup.profile.efficiencyKmPerLiter
    val worst = report.segments.withIndex()
        .filter { it.value.scorePercent != null }
        .minByOrNull { it.value.scorePercent!! }
    val paths = state.figures.settlement.segmentCosts.map { it.segment.path }
    val titles = paths.mapIndexed { i, path -> segmentTitle(i, path, vm) }

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("운전 리포트", style = MaterialTheme.typography.titleLarge, color = Palette.amber)
            Text(won(report.costWon), style = MaterialTheme.typography.displayLarge)
            Text(
                "${km(report.totalKm)} · 연료 ${liters(report.totalLiters)} ${sourceLabel(report.isMeasured)} · 리터당 ${won(state.setup.options.pricePerLiter.toLong())}",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.muted,
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("실제 연비", report.kmPerLiter?.let { kmpl(it) } ?: "-", scoreColor(report.scorePercent), Modifier.weight(1f))
                Stat("공인 대비", report.scorePercent?.let { "$it%" } ?: "-", scoreColor(report.scorePercent), Modifier.weight(1f))
                Stat("공회전", minutes(report.idleSeconds), Palette.text, Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            if (worst != null) {
                val seg = worst.value
                Text(
                    // 지명을 모를 때는 제목이 이미 "구간 N"이다.
                    titles[worst.index].let { if (it.startsWith("구간 ")) "${it}에서" else "$it 구간에서" } +
                        " 효율이 가장 낮았어요. " +
                        "평균 ${seg.avgMovingSpeedKmh.toInt()}km/h, 정차 비율 ${(seg.idleRatio * 100).toInt()}%, " +
                        "공인 연비(${kmpl(rated)}) 대비 ${seg.scorePercent}%.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
            // 지나온 길을 효율 색으로 칠한다. 가장 비효율적인 구간에 표시를 단다.
            RouteMap(
                lines = paths.zip(report.segments) { path, seg -> RouteLine(path, scoreColor(seg.scorePercent)) },
                markers = listOfNotNull(
                    worst?.let { w ->
                        paths[w.index].halfwayPoint()
                            ?.let { RouteMarker(it, "${titles[w.index]} · ${w.value.scorePercent}%", scoreColor(w.value.scorePercent)) }
                    },
                ),
                modifier = Modifier.weight(1f).fillMaxWidth().background(Palette.panel, RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(16.dp))
            BigButton(closeLabel, onClick = onClose)
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Text("구간별 효율", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                report.segments.forEachIndexed { i, seg ->
                    SegmentBar(label = titles[i], seg = seg, showDetail = true)
                }
            }
        }
    }
}

@Composable
private fun SegmentBar(label: String, seg: SegmentEfficiency, showDetail: Boolean = false) {
    val fraction = ((seg.scorePercent ?: 0) / BAR_MAX_PERCENT).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label · ${km(seg.distanceKm)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                seg.kmPerLiter?.let { kmpl(it) } ?: "-",
                style = MaterialTheme.typography.bodyLarge,
                color = scoreColor(seg.scorePercent),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).background(Palette.line, RoundedCornerShape(7.dp))) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .background(scoreColor(seg.scorePercent), RoundedCornerShape(7.dp)),
            )
            // 공인 연비(100%) 위치 표시
            Box(Modifier.fillMaxWidth(100 / BAR_MAX_PERCENT).fillMaxHeight()) {
                Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Palette.text))
            }
        }
        if (showDetail) {
            Text(
                "${liters(seg.liters)} · ${won(seg.costWon)} · 평균 ${seg.avgMovingSpeedKmh.toInt()}km/h · 정차 ${(seg.idleRatio * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
    }
}

private fun minutes(seconds: Long): String = String.format(Locale.KOREA, "%d분 %02d초", seconds / 60, seconds % 60)
