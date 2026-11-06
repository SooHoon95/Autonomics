package com.drivesplit.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesplit.app.R
import com.drivesplit.domain.trip.TripMode
import com.drivesplit.domain.Powertrain
import com.drivesplit.pleos.PleosPermissions
import kotlinx.coroutines.delay

private const val MAX_RIDERS = 4

/** 차량 음성 엔진은 조용해도 계속 듣고 있어서 직접 끊는다. */
private const val LISTEN_TIMEOUT_MS = 8_000L

/** 음성 입력 대상. 운전자 칸은 -1, 동승자 칸은 순번. */
private const val DRIVER_SLOT = -1

@Composable
fun SetupScreen(vm: MainViewModel) {
    val form by vm.form.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val speech = rememberSpeechInput()
    // 지금 듣고 있는 칸, 권한을 기다리는 칸, 인식 실패 안내
    var listening by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var speechMessage by remember { mutableStateOf<String?>(null) }

    fun setName(slot: Int, name: String) = vm.updateForm {
        if (slot == DRIVER_SLOT) it.copy(driverName = name)
        else it.copy(riders = it.riders.toMutableList().also { list -> if (slot < list.size) list[slot] = name })
    }

    fun listenTo(slot: Int) {
        speech.cancel()
        listening = slot
        speechMessage = null
        speech.listen(
            onResult = { name ->
                setName(slot, name)
                listening = null
            },
            onFail = {
                listening = null
                speechMessage = "잘 못 들었어요. 마이크를 다시 누르고 이름을 말해 주세요."
            },
        )
    }

    LaunchedEffect(listening) {
        if (listening == null) return@LaunchedEffect
        delay(LISTEN_TIMEOUT_MS)
        speech.cancel()
        listening = null
        speechMessage = "말소리가 안 들렸어요. 마이크를 다시 누르고 이름을 말해 주세요."
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val slot = pending
        pending = null
        if (granted && slot != null) listenTo(slot) else if (!granted) speechMessage = "마이크 권한이 있어야 말로 입력할 수 있어요."
    }

    fun onMic(slot: Int) {
        if (listening == slot) {
            speech.cancel()
            listening = null
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, PleosPermissions.VOICE) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            listenTo(slot)
        } else {
            pending = slot
            permission.launch(PleosPermissions.VOICE)
        }
    }

    @Composable
    fun NameField(label: String, value: String, slot: Int, modifier: Modifier = Modifier.fillMaxWidth()) {
        val active = listening == slot
        Field(
            if (active) "$label · 듣는 중… 이름을 말해 주세요" else label,
            value,
            modifier,
            trailing = if (speech.isAvailable) {
                {
                    IconButton(onClick = { onMic(slot) }, modifier = Modifier.size(56.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_mic),
                            contentDescription = "$label 말로 입력",
                            tint = if (active) Palette.amber else Palette.muted,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            } else {
                null
            },
        ) { v -> setName(slot, v) }
    }

    // 지나온 길(위치)과 차속은 출발할 때 한 번 묻는다. 거절해도 주행거리·에너지 정산은 그대로 된다.
    val tripPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startTrip()
    }

    fun depart() {
        val missing = PleosPermissions.trip.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.startTrip() else tripPermissions.launch(missing.toTypedArray())
    }

    val powertrain = form.powertrain
    val unit = powertrain.unit

    Column(Modifier.fillMaxSize().padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = if (form.mode == TripMode.SOLO) "솔로 운전 설정" else "누가 타나요?"
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            GhostButton("취소", onClick = vm::closeSetup)
            Spacer(Modifier.padding(horizontal = 8.dp))
            BigButton("출발", onClick = ::depart, enabled = form.isValid)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (form.mode == TripMode.SPLIT) Panel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NameField("운전자", form.driverName, DRIVER_SLOT)
                    form.riders.forEachIndexed { index, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NameField("동승자 ${index + 1}", name, index, Modifier.weight(1f))
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            GhostButton("빼기", onClick = {
                                // 칸 번호가 당겨지니 듣던 것은 멈춘다.
                                speech.cancel()
                                listening = null
                                vm.updateForm { it.copy(riders = it.riders.filterIndexed { i, _ -> i != index }) }
                            })
                        }
                    }
                    if (form.riders.size < MAX_RIDERS) {
                        GhostButton("동승자 추가", onClick = {
                            vm.updateForm { it.copy(riders = it.riders + "동승자 ${it.riders.size + 1}") }
                        })
                    }
                    Text(
                        speechMessage ?: if (speech.isAvailable) "마이크를 누르고 이름을 말하면 바로 바뀌어요" else "이 차에서는 음성 입력을 쓸 수 없어요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.muted,
                    )
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        when {
                            !form.powertrainDetected -> "차량 정보를 읽지 못해 휘발유차로 계산해요"
                            powertrain == Powertrain.ELECTRIC -> "전기차로 확인했어요 · 배터리 사용량으로 계산"
                            else -> "휘발유차로 확인했어요 · 연료 사용량으로 계산"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (form.powertrainDetected) Palette.go else Palette.muted,
                    )
                    Field("공인 복합 ${powertrain.efficiencyWord} (km/$unit) · 효율 점수 기준", form.efficiency, keyboard = KeyboardType.Decimal) { v ->
                        vm.updateForm { it.copy(efficiency = v) }
                    }
                    Field(
                        when {
                            powertrain == Powertrain.ELECTRIC -> "충전 단가 (원/kWh) · 공용 급속 기준"
                            form.priceIsLive -> "휘발유 가격 (원/L) · 오피넷 전국 평균"
                            else -> "휘발유 가격 (원/L) · 기본값"
                        },
                        form.pricePerUnit,
                        keyboard = KeyboardType.Number,
                    ) { v -> vm.updateForm { it.copy(pricePerUnit = v, priceIsLive = false) } }
                    Text(
                        "금액은 Pleos 차량 데이터로 잰 실제 ${powertrain.energyWord} 사용량으로 계산해요. " +
                            "값을 못 받은 구간만 공인 ${powertrain.efficiencyWord}로 추정해요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.muted,
                    )
                    if (form.mode == TripMode.SPLIT) {
                        // 동승자가 QR 정산서에서 바로 송금할 수 있게 받을 곳을 적어 둔다.
                        Field(
                            if (form.tossInvalid) "토스 아이디를 알아볼 수 없어요 · 예: toss.me/아이디" else "운전자 토스 아이디 (선택) · 금액까지 채워져요",
                            form.tossId,
                        ) { v -> vm.updateForm { it.copy(tossId = v) } }
                        Field(
                            if (form.kakaoPayInvalid) "카카오페이 링크를 알아볼 수 없어요 · 예: qr.kakaopay.com/…" else "운전자 카카오페이 송금 링크 (선택)",
                            form.kakaoPay,
                        ) { v -> vm.updateForm { it.copy(kakaoPay = v) } }
                    }
                    if (form.mode == TripMode.SPLIT) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing,
        modifier = modifier,
    )
}
