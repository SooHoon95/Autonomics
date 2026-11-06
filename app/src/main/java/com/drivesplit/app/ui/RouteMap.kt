package com.drivesplit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesplit.domain.GeoPoint
import kotlin.math.cos
import kotlin.math.min

data class RouteLine(val points: List<GeoPoint>, val color: Color)

data class RouteMarker(val point: GeoPoint, val label: String, val color: Color)

/** 정산 모드 구간 색. 구간이 더 많으면 처음부터 다시 쓴다. */
val SegmentColors = listOf(
    Color(0xFFFFB547),
    Color(0xFF4FC3F7),
    Color(0xFFB388FF),
    Color(0xFF4CD08A),
    Color(0xFFFF8A80),
)

fun segmentColor(index: Int): Color = SegmentColors[index % SegmentColors.size]

/**
 * 지도 타일 없이 지나온 경로만 그린다. 구간마다 색을 달리해서 정산·효율 차이를 경로 위에서 보여준다.
 * 위경도는 등장방형으로 펴서 화면에 맞춘다. 수백 km 범위에서는 모양이 거의 그대로다.
 */
@Composable
fun RouteMap(lines: List<RouteLine>, markers: List<RouteMarker>, modifier: Modifier = Modifier) {
    val all = lines.flatMap { it.points }
    if (all.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("위치 기록이 없어요", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
        }
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)

    Canvas(modifier) {
        val project = projection(all, padding = 48.dp.toPx())
        val width = 9.dp.toPx()

        lines.filter { it.points.size >= 2 }.forEach { line ->
            val path = Path().apply {
                line.points.forEachIndexed { i, p ->
                    val o = project(p)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
            }
            // 어두운 테두리를 먼저 깔아 구간 경계가 또렷하게 보이게 한다.
            drawPath(path, Palette.ink, style = Stroke(width + 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, line.color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val start = RouteMarker(all.first(), "출발", Palette.text)
        val end = RouteMarker(all.last(), "도착", Palette.amber)
        (listOf(start) + markers + end).forEach { marker ->
            val o = project(marker.point)
            drawCircle(Palette.ink, radius = 14.dp.toPx(), center = o)
            drawCircle(marker.color, radius = 9.dp.toPx(), center = o)
            val text = textMeasurer.measure(marker.label, labelStyle)
            val x = (o.x + 18.dp.toPx()).coerceAtMost(size.width - text.size.width)
            val y = (o.y - text.size.height / 2).coerceIn(0f, size.height - text.size.height)
            drawLabelBackground(Offset(x, y), text.size.width.toFloat(), text.size.height.toFloat())
            drawText(text, topLeft = Offset(x, y))
        }
    }
}

private fun DrawScope.drawLabelBackground(topLeft: Offset, w: Float, h: Float) {
    val pad = 6.dp.toPx()
    drawRoundRect(
        Palette.ink.copy(alpha = 0.8f),
        topLeft = Offset(topLeft.x - pad, topLeft.y - pad / 2),
        size = Size(w + pad * 2, h + pad),
        cornerRadius = CornerRadius(pad),
    )
}

/** 경로 전체가 여백 안에 들어오도록 비율을 유지해 화면 좌표로 바꾸는 함수를 만든다. */
private fun DrawScope.projection(points: List<GeoPoint>, padding: Float): (GeoPoint) -> Offset {
    val midLat = Math.toRadians((points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2)
    val xs = points.map { it.longitude * cos(midLat) }
    val ys = points.map { it.latitude }
    val minX = xs.min()
    val maxY = ys.max()
    val spanX = (xs.max() - minX).coerceAtLeast(1e-6)
    val spanY = (maxY - ys.min()).coerceAtLeast(1e-6)
    val scale = min((size.width - padding * 2) / spanX, (size.height - padding * 2) / spanY)
    val offsetX = (size.width - spanX * scale) / 2
    val offsetY = (size.height - spanY * scale) / 2
    return { p ->
        Offset(
            (offsetX + (p.longitude * cos(midLat) - minX) * scale).toFloat(),
            (offsetY + (maxY - p.latitude) * scale).toFloat(),
        )
    }
}

/** 점 간격이 속도에 따라 달라서, 점 개수가 아니라 거리로 절반 지점을 찾는다. */
fun List<GeoPoint>.halfwayPoint(): GeoPoint? {
    if (isEmpty()) return null
    val steps = zipWithNext { a, b -> a.distanceMetersTo(b) }
    var remaining = steps.sum() / 2
    steps.forEachIndexed { i, d ->
        if (remaining <= d) return this[i + 1]
        remaining -= d
    }
    return last()
}

@Composable
fun rememberPlaceName(point: GeoPoint?, vm: MainViewModel): String? =
    produceState<String?>(null, point) { value = point?.let { vm.placeName(it) } }.value

/**
 * 구간을 "강남역 → 판교"처럼 부른다. 진행 중인 구간은 아직 끝이 없어서 출발지만 쓴다.
 * 지명을 모르면 "구간 N"으로 돌아간다.
 */
@Composable
fun segmentTitle(index: Int, path: List<GeoPoint>, vm: MainViewModel, ongoing: Boolean = false): String {
    val from = rememberPlaceName(path.firstOrNull(), vm)
    val to = rememberPlaceName(if (ongoing) null else path.lastOrNull(), vm)
    return when {
        from == null -> "구간 ${index + 1}"
        ongoing -> "$from →"
        to == null || to == from -> from
        else -> "$from → $to"
    }
}
