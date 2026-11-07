package com.drivesplit.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesplit.domain.VehicleKind

/** 처음 실행할 때(또는 홈에서 다시 열 때) 차종과 공인 효율을 받는다. 정산 단가와 효율 점수가 여기서 정해진다. */
@Composable
fun VehicleSetupScreen(vm: MainViewModel) {
    val form by vm.vehicleForm.collectAsStateWithLifecycle()
    val f = form ?: return
    val kind = f.kind

    Column(Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("내 차 정보", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (f.firstRun) "처음 한 번만 알려 주세요. 기름값·충전비를 정확히 나누는 기준이 돼요." else "바꾸면 다음 여행부터 적용돼요.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.muted,
                )
            }
            if (!f.firstRun) {
                GhostButton("취소", onClick = vm::closeVehicleSetup)
                Spacer(Modifier.padding(horizontal = 8.dp))
            }
            BigButton("저장", onClick = vm::saveVehicle, enabled = f.isValid)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Text("어떤 차인가요?", style = MaterialTheme.typography.titleLarge)
                f.detected?.let {
                    Text("차에서 ${it.label}로 확인했어요", style = MaterialTheme.typography.bodyLarge, color = Palette.go)
                }
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VehicleKind.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { option ->
                                KindCard(option, selected = option == kind, Modifier.weight(1f)) { vm.selectVehicleKind(option) }
                            }
                        }
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                if (kind == null) {
                    Text("왼쪽에서 차종을 골라 주세요", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                } else {
                    val powertrain = kind.powertrain
                    OutlinedTextField(
                        value = f.efficiency,
                        onValueChange = { v -> vm.updateVehicleForm { it.copy(efficiency = v) } },
                        label = { Text("공인 복합 ${powertrain.efficiencyWord} (km/${powertrain.unit})") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "자동차 등록증이나 제원표에 있어요. 운전 효율 점수의 기준이 되고, 차가 사용량을 알려주지 않을 때 추정에 써요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(kind.howItsCounted, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun KindCard(kind: VehicleKind, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 96.dp),
        color = if (selected) Palette.line else Palette.panel,
        border = BorderStroke(2.dp, if (selected) Palette.amber else Palette.line),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kind.label, style = MaterialTheme.typography.titleLarge, color = if (selected) Palette.amber else Palette.text)
            Text(kind.priceBasis, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
    }
}
