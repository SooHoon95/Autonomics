package com.drivesplit.pleos

import android.Manifest
import android.car.Car

/** 이 모듈의 기능이 실행 중에 사용자에게 받아야 하는 권한. 나머지 Pleos 권한은 일반 권한이라 선언만 하면 된다. */
object PleosPermissions {
    /** 지나온 길(GPS)과 차속. 거절해도 주행거리·에너지 정산은 된다. */
    val trip: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Car.PERMISSION_SPEED,
    )

    /** 동승자 이름 음성 입력. */
    const val VOICE: String = Manifest.permission.RECORD_AUDIO
}
