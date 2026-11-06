package com.drivesplit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentLinksTest {

    @Test
    fun tossIdIsReadFromLinkOrPlainId() {
        assertEquals("driver01", PaymentLinks.tossIdOf("https://toss.me/driver01"))
        assertEquals("driver01", PaymentLinks.tossIdOf("toss.me/driver01/"))
        assertEquals("driver01", PaymentLinks.tossIdOf(" driver01 "))
    }

    @Test
    fun kakaoPayCodeIsReadFromLinkOrPlainCode() {
        assertEquals("Ej8x1y2z", PaymentLinks.kakaoPayCodeOf("https://qr.kakaopay.com/Ej8x1y2z"))
        assertEquals("Ej8x1y2z", PaymentLinks.kakaoPayCodeOf("Ej8x1y2z"))
    }

    @Test
    fun unrelatedTextIsRejected() {
        assertNull(PaymentLinks.tossIdOf("https://example.com/pay/abc"))
        assertNull(PaymentLinks.kakaoPayCodeOf("카카오페이 링크"))
    }

    @Test
    fun tossLinkCarriesAmount() {
        assertEquals("https://toss.me/driver01/2620", PaymentLinks.tossUrl("driver01", 2_620))
    }
}
