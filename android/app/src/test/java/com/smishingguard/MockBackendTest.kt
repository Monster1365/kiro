package com.smishingguard

import com.smishingguard.data.remote.BackendException
import com.smishingguard.data.remote.MockBackend
import com.smishingguard.domain.AlertStatus
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.PhoneNumber
import com.smishingguard.domain.RiskLevel
import com.smishingguard.domain.ShareLevel
import com.smishingguard.domain.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MockBackendTest {
    private val phish = "[CJ대한통운] 배송 주소 오류. 즉시 확인 http://bit.ly/abc"

    @Test
    fun phoneValidation() {
        assertTrue(PhoneNumber.isValidMobile("010-1234-5678"))
        assertTrue(PhoneNumber.isValidMobile("01612345678"))
        assertFalse(PhoneNumber.isValidMobile("02-123-4567"))
        assertFalse(PhoneNumber.isValidMobile(""))
    }

    @Test
    fun phishingIsHighAndNormalIsLow() = runTest {
        val b = MockBackend()
        assertEquals(RiskLevel.HIGH, b.analyze(phish, null, SourceType.MANUAL).riskLevel)
        assertEquals(RiskLevel.LOW, b.analyze("내일 점심 같이 먹자", null, SourceType.MANUAL).riskLevel)
    }

    @Test
    fun networkFailureIsAnErrorNotLow() = runTest {
        try {
            MockBackend().analyze("[테스트실패]", null, SourceType.MANUAL)
            fail("예외가 나야 한다")
        } catch (e: BackendException) {
            // 분석 실패는 예외로 전달되어 UNKNOWN 처리된다
        }
    }

    @Test
    fun alertRequiresVerifiedGuardianAndIsIdempotent() = runTest {
        val b = MockBackend()
        val a = b.analyze(phish, null, SourceType.MANUAL)
        val g = b.requestGuardianVerification("딸", "010-1111-2222", null, true)
        assertEquals(GuardianStatus.PENDING, g.status)

        // 미인증이면 거부
        try {
            b.sendGuardianAlert(a.analysisId!!, g.guardianId, ShareLevel.MINIMAL, null, null, "k")
            fail("미인증 보호자에게는 보낼 수 없다")
        } catch (e: BackendException) {
            assertEquals("GUARDIAN_NOT_VERIFIED", e.code)
        }

        assertEquals(GuardianStatus.VERIFIED, b.verifyGuardian(g.guardianId, "123456").status)
        assertEquals(AlertStatus.SENT, b.sendGuardianAlert(a.analysisId!!, g.guardianId, ShareLevel.MINIMAL, null, null, "k").status)
        // 같은 키로 다시 요청해도 성공 응답만 반환 (중복 발송 없음)
        assertEquals(AlertStatus.SENT, b.sendGuardianAlert(a.analysisId!!, g.guardianId, ShareLevel.MINIMAL, null, null, "k").status)
    }
}
