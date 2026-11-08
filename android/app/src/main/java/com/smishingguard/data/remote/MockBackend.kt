package com.smishingguard.data.remote

import com.smishingguard.domain.AlertResult
import com.smishingguard.domain.AlertStatus
import com.smishingguard.domain.AnalysisResult
import com.smishingguard.domain.GuardianRemote
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.PhoneNumber
import com.smishingguard.domain.RiskLevel
import com.smishingguard.domain.ShareLevel
import com.smishingguard.domain.SourceType
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * 외부 서비스 없이 화면을 테스트하기 위한 모의 구현 (debug 기본값).
 * - 인증번호는 항상 123456
 * - 문자에 "[테스트실패]" 가 있으면 네트워크 오류, "[테스트분석불가]" 면 서버 오류를 흉내 낸다.
 * - 보호자 알림은 "[테스트발송실패]" 가 있는 분석에서 실패한다. (분석 id 에 표식을 기억)
 */
class MockBackend : Backend {
    private val analyses = mutableMapOf<String, RiskLevel>()
    private val failAlertFor = mutableSetOf<String>()
    private val sentKeys = mutableSetOf<String>()
    private var guardian: GuardianRemote? = null
    private var guardianConsent = false

    override suspend fun analyze(messageBody: String, sender: String?, source: SourceType): AnalysisResult {
        delay(700)
        if (messageBody.contains("[테스트실패]")) throw BackendException(ErrorKind.NETWORK, null, "인터넷 연결을 확인해 주세요.")
        if (messageBody.contains("[테스트분석불가]")) throw BackendException(ErrorKind.SERVER, null, "서버에 문제가 있어요. 잠시 후 다시 시도해 주세요.")

        val hasUrl = Regex("(https?://|www\\.|bit\\.ly)", RegexOption.IGNORE_CASE).containsMatchIn(messageBody)
        val impersonation = Regex("(택배|배송|국세청|검찰|경찰|은행|카드|건강보험|쿠팡)").containsMatchIn(messageBody)
        val credential = Regex("(비밀번호|인증번호|계좌|주민등록|카드번호|OTP)").containsMatchIn(messageBody)
        val install = Regex("(\\.apk|앱\\s*설치|앱을\\s*설치)", RegexOption.IGNORE_CASE).containsMatchIn(messageBody)
        val urgent = Regex("(긴급|즉시|정지|오늘까지|결제|미납|환불)").containsMatchIn(messageBody)

        val reasons = buildList {
            if (hasUrl) add("문자에 인터넷 주소(링크)가 들어 있어요.")
            if (impersonation) add("택배·기관·금융사 등을 내세우는 문구가 있어요.")
            if (credential) add("개인정보나 금융정보를 알려 달라고 해요.")
            if (install) add("앱을 설치하라고 해요.")
            if (urgent) add("서두르게 만드는 표현이 있어요.")
        }
        val score = (if (hasUrl) 1 else 0) + (if (impersonation) 2 else 0) + (if (credential) 3 else 0) +
            (if (install) 3 else 0) + (if (urgent) 2 else 0)
        val level = when {
            score >= 5 -> RiskLevel.HIGH
            score >= 2 -> RiskLevel.CAUTION
            else -> RiskLevel.LOW
        }
        val id = "mock_analysis_" + UUID.randomUUID().toString().take(8)
        analyses[id] = level
        if (messageBody.contains("[테스트발송실패]")) failAlertFor += id
        return AnalysisResult(
            analysisId = id,
            riskLevel = level,
            summary = when (level) {
                RiskLevel.HIGH -> "스미싱이 의심됩니다. (모의 분석)"
                RiskLevel.CAUTION -> "확인이 필요한 문자입니다. (모의 분석)"
                else -> "뚜렷한 위험 요소가 발견되지 않았습니다. 다만 안전을 보장하지는 않습니다. (모의 분석)"
            },
            reasons = reasons.ifEmpty { listOf("스미싱에서 자주 보이는 표현이 거의 없어요.") },
            recommendedActions = com.smishingguard.domain.defaultActions(level),
        )
    }

    override suspend fun requestGuardianVerification(
        name: String,
        phoneNumber: String,
        relationship: String?,
        alertConsent: Boolean,
    ): GuardianRemote {
        delay(500)
        if (!PhoneNumber.isValidMobile(phoneNumber)) throw BackendException(ErrorKind.CLIENT, "INVALID_PHONE", "휴대전화 번호 형식이 올바르지 않아요.")
        val p = PhoneNumber.normalize(phoneNumber)
        guardianConsent = alertConsent
        val g = GuardianRemote(
            guardianId = "mock_guardian_1",
            name = name,
            phoneMasked = p.take(3) + "****" + p.takeLast(4),
            status = GuardianStatus.PENDING,
            devCode = "123456",
        )
        guardian = g
        return g
    }

    override suspend fun verifyGuardian(guardianId: String, code: String): GuardianRemote {
        delay(400)
        val g = guardian ?: throw BackendException(ErrorKind.CLIENT, "NOT_FOUND", "보호자를 찾을 수 없어요.")
        if (code != "123456") throw BackendException(ErrorKind.CLIENT, "CODE_MISMATCH", "인증번호가 맞지 않아요.")
        return g.copy(status = GuardianStatus.VERIFIED, devCode = null).also { guardian = it }
    }

    override suspend fun deleteGuardian(guardianId: String) {
        delay(200)
        guardian = null
    }

    override suspend fun setGuardianAlertConsent(guardianId: String, enabled: Boolean) {
        delay(200)
        guardianConsent = enabled
    }

    override suspend fun sendGuardianAlert(
        analysisId: String,
        guardianId: String,
        shareLevel: ShareLevel,
        sender: String?,
        excerpt: String?,
        idempotencyKey: String,
    ): AlertResult {
        delay(800)
        if (guardian?.status != GuardianStatus.VERIFIED) throw BackendException(ErrorKind.CLIENT, "GUARDIAN_NOT_VERIFIED", "인증되지 않은 보호자에게는 보낼 수 없어요.")
        if (!guardianConsent) throw BackendException(ErrorKind.CLIENT, "ALERT_DISABLED", "자동 보호자 알림이 꺼져 있어요.")
        if (analyses[analysisId] != RiskLevel.HIGH) throw BackendException(ErrorKind.CLIENT, "NOT_ELIGIBLE", "위험 수준이 높음일 때만 보내요.")
        if (!sentKeys.add(idempotencyKey)) return AlertResult(AlertStatus.SENT) // 중복 방지
        return if (analysisId in failAlertFor) AlertResult(AlertStatus.FAILED, "PROVIDER_ERROR") else AlertResult(AlertStatus.SENT)
    }
}
