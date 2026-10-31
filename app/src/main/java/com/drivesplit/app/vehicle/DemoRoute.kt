package com.drivesplit.app.vehicle

import com.drivesplit.core.GeoPoint

/**
 * 시연용 강남 → 경부고속도로 → 대전 경로. 실제 도로를 대략 따라가는 꺾은선이고 점 사이는 직선으로 잇는다.
 * 이름이 있는 점은 지명 조회(시연용)에도 쓴다.
 */
object DemoRoute {
    data class Stop(val point: GeoPoint, val name: String?)

    val stops = listOf(
        Stop(GeoPoint(37.4979, 127.0276), "강남역"),
        Stop(GeoPoint(37.4932, 127.0318), null),
        Stop(GeoPoint(37.4887, 127.0300), null),
        Stop(GeoPoint(37.4845, 127.0345), null),
        Stop(GeoPoint(37.4770, 127.0410), "양재"),
        Stop(GeoPoint(37.4600, 127.0430), "내곡"),
        Stop(GeoPoint(37.4300, 127.0650), "청계산"),
        Stop(GeoPoint(37.3947, 127.1112), "판교"),
        Stop(GeoPoint(37.3400, 127.1150), "죽전"),
        Stop(GeoPoint(37.2850, 127.1100), "신갈"),
        Stop(GeoPoint(37.2200, 127.0850), "동탄"),
        Stop(GeoPoint(37.1500, 127.0700), "오산"),
        Stop(GeoPoint(37.0800, 127.1150), "진위"),
        Stop(GeoPoint(37.0080, 127.1700), "안성"),
        Stop(GeoPoint(36.9100, 127.1650), "성환"),
        Stop(GeoPoint(36.8150, 127.1530), "천안"),
        Stop(GeoPoint(36.7550, 127.2600), "목천"),
        Stop(GeoPoint(36.6900, 127.3300), "청주"),
        Stop(GeoPoint(36.5800, 127.3900), "부강"),
        Stop(GeoPoint(36.4500, 127.4300), "신탄진"),
        Stop(GeoPoint(36.3900, 127.4100), "대덕"),
        Stop(GeoPoint(36.3504, 127.3845), "대전"),
    )

    private val points = stops.map { it.point }
    private val cumulativeKm = points.zipWithNext { a, b -> a.distanceMetersTo(b) / 1000 }
        .runningFold(0.0) { acc, d -> acc + d }
    private val totalKm = cumulativeKm.last()

    fun pointAt(traveledKm: Double): GeoPoint {
        // 끝에 닿으면 왔던 길로 되돌아간다.
        val lap = traveledKm % (2 * totalKm)
        val along = if (lap <= totalKm) lap else 2 * totalKm - lap
        val i = cumulativeKm.indexOfLast { it <= along }.coerceAtMost(points.size - 2)
        val t = (along - cumulativeKm[i]) / (cumulativeKm[i + 1] - cumulativeKm[i])
        val a = points[i]
        val b = points[i + 1]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }
}
