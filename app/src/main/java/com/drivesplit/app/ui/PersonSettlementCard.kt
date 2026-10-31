package com.drivesplit.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drivesplit.core.Settlement

/**
 * 한 사람의 정산서: 함께 탄 구간과 그 사람 몫, 그 사람만을 위한 QR.
 * 중간 하차 때와, 지난 기록에서 사람별로 확인할 때 같이 쓴다.
 */
@Composable
fun PersonSettlementCard(
    title: String,
    settlement: Settlement,
    passengerId: String,
    url: String,
    note: String?,
    qrCaption: String,
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    val qr = remember(url) { qrBitmap(url) }
    // 구간 색과 번호를 전체 결과 화면과 맞추려고 원래 순서를 유지한다.
    val ridden = settlement.segmentCosts.withIndex().filter { passengerId in it.value.segment.occupantIds }
    Box(
        Modifier.fillMaxSize().background(Palette.ink.copy(alpha = 0.85f)).padding(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.amber)
                    Text(won(settlement.shares[passengerId] ?: 0), style = MaterialTheme.typography.displayLarge)
                    Text(
                        "함께 탄 거리 ${km(ridden.sumOf { it.value.segment.distanceKm })} · " +
                            "내 몫 연료 ${liters(ridden.sumOf { it.value.fuel.liters / it.value.payerIds.size.coerceAtLeast(1) })}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.muted,
                    )
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        ridden.forEach { (i, cost) ->
                            // 금액은 10원 단위로 맞춘 합계만 보여주고, 구간에서는 1인당 연료량을 보여준다.
                            val people = cost.payerIds.size.coerceAtLeast(1)
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(segmentColor(i), CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${segmentTitle(i, cost.segment.path, vm)} · ${km(cost.segment.distanceKm)} · " +
                                        "${people}명이 나눔 · 1인 ${liters(cost.fuel.liters / people)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted) }
                    Spacer(Modifier.height(16.dp))
                    BigButton("확인", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(qrCaption, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Image(
                        bitmap = qr,
                        contentDescription = "$title QR 코드",
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}
