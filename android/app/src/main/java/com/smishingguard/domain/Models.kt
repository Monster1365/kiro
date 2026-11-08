package com.smishingguard.domain

enum class RiskLevel(val label: String, val headline: String) {
    HIGH("높음", "스미싱이 의심됩니다"),
    CAUTION("주의", "확인이 필요한 문자입니다"),
    LOW("낮음", "뚜렷한 위험 요소가 발견되지 않았습니다"),
    UNKNOWN("분석 불가", "문자를 분석하지 못했습니다");

    companion object {
        fun parse(s: String?): RiskLevel? = entries.firstOrNull { it.name == s }
    }
}

enum class SourceType { MANUAL, AUTO }

enum class ShareLevel(val label: String, val description: String) {
    MINIMAL("기본", "위험이 있었다는 사실, 시각, 대응 안내만 보내요"),
    WITH_SENDER("발신 번호 포함", "기본 내용에 문자를 보낸 번호를 함께 보내요"),
    WITH_EXCERPT("문자 내용 일부 포함", "기본 내용에 발신 번호와 문자 내용 일부(링크 제외)를 함께 보내요");
}

enum class GuardianStatus { NONE, PENDING, VERIFIED, FAILED }

enum class AlertStatus(val label: String) {
    NONE("보내지 않음"),
    REQUESTED("보호자 알림 요청 중"),
    // 사업자 접수/전송 상태이며 보호자가 읽었다는 뜻이 아님
    SENT("보호자 알림 문자를 보냈어요 (읽었는지는 알 수 없어요)"),
    FAILED("보호자 알림을 보내지 못했어요");

    companion object {
        fun parse(s: String?): AlertStatus = entries.firstOrNull { it.name == s } ?: NONE
    }
}

/** 서버가 반환한 분석 결과 (analysis_status == SUCCESS 인 경우에만 사용) */
data class AnalysisResult(
    val analysisId: String?,
    val riskLevel: RiskLevel,
    val summary: String,
    val reasons: List<String>,
    val recommendedActions: List<String>,
)

data class GuardianRemote(
    val guardianId: String,
    val name: String,
    val phoneMasked: String,
    val status: GuardianStatus,
    val devCode: String? = null, // 개발용 서버(MOCK_EXPOSE_CODE)에서만 내려옴
)

data class AlertResult(val status: AlertStatus, val failureCode: String? = null)

/** 화면 표시용 이력 */
data class DetectionRecord(
    val id: String,
    val sourceType: SourceType,
    val sender: String?,
    val messageBody: String?, // 원문 저장 동의 시에만 존재
    val riskLevel: RiskLevel,
    val summary: String,
    val reasons: List<String>,
    val recommendedActions: List<String>,
    val analyzedAt: Long,
    val guardianAlertStatus: AlertStatus,
    val feedback: String?,
)

data class AppSettings(
    val onboarded: Boolean = false,
    val analysisConsent: Boolean = false,
    val localOriginalSaveConsent: Boolean = false,
    val guardianAutoAlertEnabled: Boolean = false,
    val guardianShareLevel: ShareLevel = ShareLevel.MINIMAL,
    val retentionDays: Int = 30,
    val notificationEnabled: Boolean = true,
    val guardianId: String? = null,
    val guardianName: String? = null,
    val guardianPhoneMasked: String? = null,
    val guardianStatus: GuardianStatus = GuardianStatus.NONE,
)

/** 위험 수준별 기본 대응 안내 (원문 미저장 이력 등에서 사용) */
fun defaultActions(level: RiskLevel): List<String> = when (level) {
    RiskLevel.HIGH -> listOf(
        "문자 속 링크를 누르지 마세요.",
        "앱 설치나 개인정보 입력 요청에 응하지 마세요.",
        "공식 앱이나 공식 전화번호로 직접 확인하세요.",
    )
    RiskLevel.CAUTION -> listOf(
        "링크를 누르기 전에 공식 앱이나 공식 전화번호로 먼저 확인하세요.",
        "개인정보를 알려주지 마세요.",
    )
    RiskLevel.LOW -> listOf("모르는 링크는 누르지 않는 것이 안전해요.")
    RiskLevel.UNKNOWN -> listOf("링크를 누르지 말고 공식 앱이나 공식 전화번호로 직접 확인하세요.")
}

object PhoneNumber {
    private val MOBILE = Regex("^01[016789]\\d{7,8}$")
    fun normalize(raw: String): String = raw.filter { it.isDigit() }
    fun isValidMobile(raw: String): Boolean = MOBILE.matches(normalize(raw))
}
