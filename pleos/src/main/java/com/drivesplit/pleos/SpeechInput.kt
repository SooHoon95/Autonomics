package com.drivesplit.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/**
 * Pleos SpeechToText SDK로 한 마디를 받아 글자로 돌려준다.
 * 기기 안 엔진(ON_DEVICE)을 써서 서버 등록·네트워크 없이 동작한다.
 */
class SpeechInput(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val stt = SpeechToText(context, Mode.ON_DEVICE)

    @Volatile var isAvailable: Boolean = true
        private set

    private var onResult: ((String) -> Unit)? = null
    private var onFail: (() -> Unit)? = null

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (!isFinal) return
            val name = spokenName(text)
            main.post { if (name.isEmpty()) finishWithFailure() else finishWith(name) }
        }

        override fun onError(error: ErrorType) {
            Log.w(TAG, "STT error: $error")
            main.post { finishWithFailure() }
        }

        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {}
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        // 권한이나 음성 서비스가 없는 환경에서도 화면은 떠야 하니 실패하면 음성 입력만 끈다.
        runCatching { initialize() }.onFailure {
            Log.w(TAG, "STT unavailable", it)
            isAvailable = false
        }
    }

    private fun initialize() {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                stt.setLocale(Locale.KOREA)
                stt.addListener(listener)
            }

            override fun onFailure(error: ErrorType) {
                Log.w(TAG, "STT init failed: $error")
                isAvailable = false
            }
        })
    }

    fun listen(onResult: (String) -> Unit, onFail: () -> Unit) {
        if (!isAvailable) return onFail()
        this.onResult = onResult
        this.onFail = onFail
        runCatching { stt.request() }.onFailure {
            Log.w(TAG, "STT request failed", it)
            finishWithFailure()
        }
    }

    fun cancel() {
        onResult = null
        onFail = null
        runCatching { stt.stop() }
    }

    fun destroy() {
        cancel()
        runCatching {
            stt.removeListener(listener)
            stt.release()
        }
    }

    private fun finishWith(name: String) {
        val callback = onResult
        onResult = null
        onFail = null
        callback?.invoke(name)
    }

    private fun finishWithFailure() {
        val callback = onFail
        onResult = null
        onFail = null
        callback?.invoke()
    }

    private companion object {
        const val TAG = "SpeechInput"
    }
}

/** 이름 칸에 넣을 수 있게 끝의 문장부호와 "님"을 떼어낸다. "민수님." → "민수" */
private fun spokenName(text: String): String =
    text.trim().trimEnd('.', '!', '?', ',').trim().removeSuffix("님").trim()
