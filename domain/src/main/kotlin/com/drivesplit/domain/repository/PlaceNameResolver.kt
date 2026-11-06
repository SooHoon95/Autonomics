package com.drivesplit.domain.repository

import com.drivesplit.domain.GeoPoint
import com.drivesplit.domain.NamedPlace

/** 좌표를 사람이 읽는 지명으로 바꾼다. 모르면 null. */
fun interface PlaceNameResolver {
    suspend fun nameOf(point: GeoPoint): String?
}

/** 주행하면서 지명을 모으는 쪽. 기록에 함께 저장했다가 지난 기록을 열 때 되돌려 놓는다. */
interface PlaceRecorder {
    fun snapshot(): List<NamedPlace>

    fun restore(places: List<NamedPlace>)

    /** 새 여행을 시작할 때 지난 여행의 지명을 비운다. 기록마다 그 여행의 지명만 남도록. */
    fun clear()
}
