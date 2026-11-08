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
import androidx.compose.ui.geometry.Rect
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
        val pins = listOf(start, end) + markers
        val dots = pins.map { project(it.point) }
        dots.zip(pins).forEach { (o, marker) ->
            drawCircle(Palette.ink, radius = 14.dp.toPx(), center = o)
            drawCircle(marker.color, radius = 9.dp.toPx(), center = o)
        }

        // 라벨은 점과 다른 라벨을 가리지 않는 자리를 찾아 놓는다. 출발·도착을 먼저 자리 잡고,
        // 가까운 자리가 다 차 있으면 더 멀리 놓고 점까지 가는 선을 긋는다.
        val placed = mutableListOf<Rect>()
        val dotBoxes = dots.map { Rect(it, 15.dp.toPx()) }
        val pad = 6.dp.toPx()
        dots.zip(pins).forEach { (o, marker) ->
            val text = textMeasurer.measure(marker.label, labelStyle)
            val w = text.size.width.toFloat()
            val h = text.size.height.toFloat()
            val box = labelSpot(o, w, h, pad, placed, dotBoxes)
            placed += box
            val topLeft = Offset(box.left + pad, box.top + pad / 2)
            val center = box.center
            if ((center - o).getDistance() > w / 2 + 40.dp.toPx()) {
                drawLine(Palette.muted, o, Offset(center.x.coerceIn(box.left, box.right), center.y.coerceIn(box.top, box.bottom)), 2.dp.toPx())
            }
            drawLabelBackground(topLeft, w, h)
            drawText(text, topLeft = topLeft)
        }
    }
}

/**
 * 점 [o] 둘레의 후보 자리(오른쪽, 왼쪽, 위, 아래, 대각선 순, 가까운 것부터)를 차례로 보고
 * 화면 안에 있으면서 이미 놓은 라벨·점과 겹치지 않는 첫 자리를 돌려준다. 다 겹치면 가장 덜 겹치는 자리.
 */
private fun DrawScope.labelSpot(o: Offset, w: Float, h: Float, pad: Float, placed: List<Rect>, dots: List<Rect>): Rect {
    val bw = w + pad * 2
    val bh = h + pad
    val gap = 20.dp.toPx()
    val candidates = buildList {
        for (step in 0..3) {
            val d = gap + step * (bh + 4.dp.toPx())
            add(Offset(o.x + d, o.y - bh / 2))            // 오른쪽
            add(Offset(o.x - d - bw, o.y - bh / 2))       // 왼쪽
            add(Offset(o.x - bw / 2, o.y - d - bh))       // 위
            add(Offset(o.x - bw / 2, o.y + d))            // 아래
            add(Offset(o.x + d * 0.7f, o.y - d * 0.7f - bh))   // 오른쪽 위
            add(Offset(o.x + d * 0.7f, o.y + d * 0.7f))        // 오른쪽 아래
            add(Offset(o.x - d * 0.7f - bw, o.y - d * 0.7f - bh)) // 왼쪽 위
            add(Offset(o.x - d * 0.7f - bw, o.y + d * 0.7f))      // 왼쪽 아래
        }
    }.map { Rect(it, Size(bw, bh)) }
        .filter { it.left >= 0 && it.top >= 0 && it.right <= size.width && it.bottom <= size.height }

    fun overlap(r: Rect): Float = (placed + dots).sumOf { other ->
        val i = r.intersect(other)
        if (i.width > 0 && i.height > 0) (i.width * i.height).toDouble() else 0.0
    }.toFloat()

    return candidates.firstOrNull { overlap(it) == 0f }
        ?: candidates.minByOrNull { overlap(it) }
        ?: Rect(Offset((o.x + gap).coerceAtMost(size.width - bw), (o.y - bh / 2).coerceIn(0f, size.height - bh)), Size(bw, bh))
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
