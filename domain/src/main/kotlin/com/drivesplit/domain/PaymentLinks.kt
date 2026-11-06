package com.drivesplit.domain

/**
 * 운전자가 정산금을 받을 송금 링크. 동승자 폰의 정산 페이지가 이 링크로 송금 앱을 연다.
 * 운전자는 앱 설정에 링크를 통째로 붙여 넣어도 되고 아이디·코드만 넣어도 된다.
 */
object PaymentLinks {
    private val tossId = Regex("""^(?:https?://)?(?:toss\.me/)?([A-Za-z0-9_.-]+)/?$""")
    private val kakaoPayCode = Regex("""^(?:https?://)?(?:qr\.kakaopay\.com/)?([A-Za-z0-9]+)/?$""")

    /** "https://toss.me/abc", "toss.me/abc", "abc" → "abc". 알아볼 수 없으면 null. */
    fun tossIdOf(input: String): String? = tossId.matchEntire(input.trim())?.groupValues?.get(1)

    /** "https://qr.kakaopay.com/Ej8x1y2z", 코드만 → "Ej8x1y2z". 알아볼 수 없으면 null. */
    fun kakaoPayCodeOf(input: String): String? = kakaoPayCode.matchEntire(input.trim())?.groupValues?.get(1)

    /** 토스 공식 송금 링크. 금액이 채워진 송금 화면이 열린다. */
    fun tossUrl(id: String, amountWon: Long): String = "https://toss.me/$id/$amountWon"

    /** 카카오페이 송금 링크. 금액을 미리 채우는 공식 방법이 없어 받는 사람 화면만 연다. */
    fun kakaoPayUrl(code: String): String = "https://qr.kakaopay.com/$code"
}
