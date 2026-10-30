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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val MAX_RIDERS = 4

@Composable
fun SetupScreen(vm: MainViewModel) {
    val form by vm.form.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("누가 타나요?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            GhostButton("취소", onClick = vm::closeSetup)
            Spacer(Modifier.padding(horizontal = 8.dp))
            BigButton("출발", onClick = vm::startTrip, enabled = form.isValid)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Field("운전자", form.driverName) { v -> vm.updateForm { it.copy(driverName = v) } }
                    form.riders.forEachIndexed { index, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Field("동승자 ${index + 1}", name, Modifier.weight(1f)) { v ->
                                vm.updateForm { it.copy(riders = it.riders.toMutableList().also { list -> list[index] = v }) }
                            }
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            GhostButton("빼기", onClick = {
                                vm.updateForm { it.copy(riders = it.riders.filterIndexed { i, _ -> i != index }) }
                            })
                        }
                    }
                    if (form.riders.size < MAX_RIDERS) {
                        GhostButton("동승자 추가", onClick = {
                            vm.updateForm { it.copy(riders = it.riders + "동승자 ${it.riders.size + 1}") }
                        })
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Field("연비 (km/L)", form.efficiencyKmPerLiter, keyboard = KeyboardType.Decimal) { v ->
                        vm.updateForm { it.copy(efficiencyKmPerLiter = v) }
                    }
                    Field(
                        if (form.priceIsLive) "휘발유 가격 (원/L) · 오피넷 전국 평균" else "휘발유 가격 (원/L) · 기본값",
                        form.pricePerLiter,
                        keyboard = KeyboardType.Number,
                    ) { v -> vm.updateForm { it.copy(pricePerLiter = v, priceIsLive = false) } }
                    Field("운전자 토스 아이디 (송금 링크용, 선택)", form.tossId) { v -> vm.updateForm { it.copy(tossId = v) } }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("운전자도 나눠 내기", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "끄면 동승자끼리만 나눠요",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.muted,
                            )
                        }
                        Switch(checked = form.driverPays, onCheckedChange = { v -> vm.updateForm { it.copy(driverPays = v) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}
