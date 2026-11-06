package com.drivesplit.pleos

import com.drivesplit.domain.GeoPoint
import com.drivesplit.domain.NamedPlace
import com.drivesplit.domain.repository.PlaceNameResolver
import com.drivesplit.domain.repository.PlaceRecorder

/** 주소에서 구간을 나눌 행정구역을 뽑는다. "경기도 성남시 수정구 시흥동" → "수정구". 구가 없으면 시·군. */
fun regionOf(address: String): String? {
    val words = address.split(' ').filter { it.isNotBlank() }
    return words.lastOrNull { it.endsWith("구") || it.endsWith("군") } ?: words.lastOrNull { it.endsWith("시") }
}

/**
 * 주행 중 Pleos NaviHelper가 알려준 현재 위치의 주소를 모아 두고, 경로 위의 점을 가장 가까운 주소로 부른다.
 * NaviHelper는 임의 좌표가 아니라 "지금 위치"의 주소만 주므로 지나가는 동안 받아 둬야 한다.
 */
class NaviPlaceNames : PlaceNameResolver, PlaceRecorder {
    private val places = mutableListOf<NamedPlace>()

    @Synchronized
    fun record(point: GeoPoint, address: String) {
        val name = shortName(address) ?: return
        val last = places.lastOrNull()
        // 같은 동네를 계속 지나는 동안은 처음 받은 점 하나만 남긴다.
        if (last != null && last.name == name && last.point.distanceMetersTo(point) < SAME_PLACE_M) return
        places += NamedPlace(point, name)
    }

    @Synchronized
    override fun restore(places: List<NamedPlace>) {
        this.places += places
    }

    @Synchronized
    override fun snapshot(): List<NamedPlace> = places.toList()

    @Synchronized
    override fun clear() {
        places.clear()
    }

    override suspend fun nameOf(point: GeoPoint): String? =
        snapshot().minByOrNull { it.point.distanceMetersTo(point) }
            ?.takeIf { it.point.distanceMetersTo(point) <= NEARBY_M }
            ?.name

    private companion object {
        const val SAME_PLACE_M = 1_000.0
        const val NEARBY_M = 1_500.0

        /** "경기도 성남시 수정구 시흥동 123" → "수정구 시흥동". 번지는 버리고 끝의 행정구역 두 단계만 쓴다. */
        fun shortName(address: String): String? {
            val words = address.split(' ').filter { it.isNotBlank() && !it.first().isDigit() }
            return words.takeLast(2).joinToString(" ").ifBlank { null }
        }
    }
}
