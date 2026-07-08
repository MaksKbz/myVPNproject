package com.makskbz.myvpnproject.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v3.6 — тесты сопоставления ISP → пресет (без сети, без VpnService).
 * Проверяем только чистую логику presetForIsp(), т.к. detect() требует
 * реального android.net.VpnService и сети — это тестируется вручную/E2E.
 */
class NetworkProfileDetectorTest {

    private fun profile(isp: String? = null, org: String? = null, asn: String? = null) =
        NetworkProfileDetector.IspProfile(ip = "1.2.3.4", isp = isp, org = org, asn = asn, countryCode = "KZ")

    @Test
    fun `null profile yields null preset`() {
        assertNull(NetworkProfileDetector.presetForIsp(null))
    }

    @Test
    fun `Kazakhtelecom isp maps to kz-telecom`() {
        val p = profile(isp = "JSC Kazakhtelecom")
        assertEquals("kz-telecom", NetworkProfileDetector.presetForIsp(p))
    }

    @Test
    fun `Kcell org maps to kz-telecom`() {
        val p = profile(org = "Kcell JSC")
        assertEquals("kz-telecom", NetworkProfileDetector.presetForIsp(p))
    }

    @Test
    fun `MTS PJSC ASN maps to mts-ru`() {
        val p = profile(asn = "AS8359 MTS PJSC")
        assertEquals("mts-ru", NetworkProfileDetector.presetForIsp(p))
    }

    @Test
    fun `Beeline isp maps to beeline-ru`() {
        val p = profile(isp = "Beeline")
        assertEquals("beeline-ru", NetworkProfileDetector.presetForIsp(p))
    }

    @Test
    fun `Rostelecom org maps to rostelecom`() {
        val p = profile(org = "Rostelecom PJSC")
        assertEquals("rostelecom", NetworkProfileDetector.presetForIsp(p))
    }

    @Test
    fun `unknown operator yields null`() {
        val p = profile(isp = "Some Random Local ISP LLC")
        assertNull(NetworkProfileDetector.presetForIsp(p))
    }

    @Test
    fun `blank fields yield null without throwing`() {
        val p = profile(isp = "", org = "", asn = "")
        assertNull(NetworkProfileDetector.presetForIsp(p))
    }
}
