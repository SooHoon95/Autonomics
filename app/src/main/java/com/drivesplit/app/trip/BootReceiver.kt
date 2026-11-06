package com.drivesplit.app.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drivesplit.app.DriveSplitApp

/**
 * 시동을 다시 켜서 차량 화면이 부팅되면, 진행 중이던 여행을 앱을 열지 않아도 바로 이어서 기록한다.
 * 실제로 이어 붙이는 일은 [DriveSplitApp]이 프로세스를 띄우며 한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        (context.applicationContext as DriveSplitApp).resumeTrip { result.finish() }
    }
}
