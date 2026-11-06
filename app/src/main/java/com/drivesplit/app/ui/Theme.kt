package com.drivesplit.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.drivesplit.domain.Powertrain
import java.text.NumberFormat
import java.util.Locale

/** 차량 화면은 야간 눈부심을 피하려고 다크 테마만 쓴다. 강조색은 연료 게이지의 호박색. */
object Palette {
    val ink = Color(0xFF0F1216)
    val panel = Color(0xFF181C22)
    val line = Color(0xFF2A3038)
    val text = Color(0xFFEDEFF2)
    val muted = Color(0xFF8B94A1)
    val amber = Color(0xFFFFB547)
    val go = Color(0xFF4CD08A)
}

private val colors = darkColorScheme(
    primary = Palette.amber,
    onPrimary = Palette.ink,
    background = Palette.ink,
    onBackground = Palette.text,
    surface = Palette.panel,
    onSurface = Palette.text,
    surfaceVariant = Palette.panel,
    onSurfaceVariant = Palette.muted,
    outline = Palette.line,
)

// 차량용: 팔 길이 거리에서 읽히도록 본문도 크게 잡는다.
private val typography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 24.sp),
    labelLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 20.sp),
)

@Composable
fun DriveSplitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

private val wonFormat = NumberFormat.getIntegerInstance(Locale.KOREA)

fun won(amount: Long): String = wonFormat.format(amount) + "원"
fun km(value: Double): String = String.format(Locale.KOREA, "%.1fkm", value)
fun energy(value: Double, powertrain: Powertrain): String = String.format(Locale.KOREA, "%.2f%s", value, powertrain.unit)
fun efficiency(value: Double, powertrain: Powertrain): String =
    String.format(Locale.KOREA, "%.1fkm/%s", value, powertrain.unit)

/** 휘발유차와 전기차에서 다르게 부르는 말. */
val Powertrain.energyWord: String get() = if (this == Powertrain.ELECTRIC) "전기" else "연료"
val Powertrain.efficiencyWord: String get() = if (this == Powertrain.ELECTRIC) "전비" else "연비"
val Powertrain.costWord: String get() = if (this == Powertrain.ELECTRIC) "충전비" else "연료비"
val Powertrain.perUnitWord: String get() = if (this == Powertrain.ELECTRIC) "kWh당" else "리터당"
fun sourceLabel(measured: Boolean): String = if (measured) "(실측)" else "(추정)"
