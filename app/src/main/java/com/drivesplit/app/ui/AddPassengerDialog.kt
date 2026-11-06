package com.drivesplit.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.drivesplit.app.R
import com.drivesplit.pleos.PleosPermissions

/** 정차 중에 새로 탄 사람의 이름을 받는다. 말로도 입력할 수 있다. */
@Composable
fun AddPassengerDialog(defaultName: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val speech = rememberSpeechInput()
    var name by remember { mutableStateOf(defaultName) }
    var listening by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun listen() {
        listening = true
        message = null
        speech.listen(
            onResult = {
                name = it
                listening = false
            },
            onFail = {
                listening = false
                message = "잘 못 들었어요. 마이크를 다시 누르고 이름을 말해 주세요."
            },
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else message = "마이크 권한이 있어야 말로 입력할 수 있어요."
    }

    AlertDialog(
        onDismissRequest = {
            speech.cancel()
            onDismiss()
        },
        containerColor = Palette.panel,
        title = { Text("동승자 추가", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (listening) "듣는 중… 이름을 말해 주세요" else "이름") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    trailingIcon = if (speech.isAvailable) {
                        {
                            IconButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(context, PleosPermissions.VOICE) ==
                                        PackageManager.PERMISSION_GRANTED
                                    if (granted) listen() else permission.launch(PleosPermissions.VOICE)
                                },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_mic),
                                    contentDescription = "이름 말로 입력",
                                    tint = if (listening) Palette.amber else Palette.muted,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    message ?: "지금부터 함께 탄 구간만 나눠 내요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) {
                Text("태우기", style = MaterialTheme.typography.bodyLarge, color = Palette.amber)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                speech.cancel()
                onDismiss()
            }) {
                Text("취소", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
            }
        },
    )
}
