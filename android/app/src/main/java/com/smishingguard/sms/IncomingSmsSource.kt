package com.smishingguard.sms

/** 자동 수신 경로에서 분석 파이프라인으로 넘기는 최소 정보 */
data class IncomingSms(
    val body: String,
    val sender: String?,
    val receivedAt: Long,
    /** 같은 문자의 중복 수신 이벤트를 걸러내기 위한 값 (예: 발신번호+수신시각+본문 해시) */
    val deduplicationKey: String,
)

data class AutoAnalysisAvailability(val available: Boolean, val reason: String)

/**
 * 자동 SMS 수신 분석 확장 지점.
 *
 * Google Play SMS 권한 정책과 기술 검증이 끝나기 전에는 기본 기능이 아니다.
 * 승인된 방식(예: 기본 SMS 앱, 정책 예외 등)이 정해지면 이 인터페이스를 구현해 AppContainer 에 연결한다.
 * 구현체는 새로 수신된 문자만 [onMessage] 로 넘기고, 이후 처리는 AnalysisRepository.analyzeIncoming 이 맡는다.
 */
interface IncomingSmsSource {
    val availability: AutoAnalysisAvailability
    fun start(onMessage: suspend (IncomingSms) -> Unit)
    fun stop()
}

/** 현재 기본 구현: 사용할 수 없음. 수동 분석 방법을 안내한다. */
class UnavailableIncomingSmsSource : IncomingSmsSource {
    override val availability = AutoAnalysisAvailability(
        available = false,
        reason = "문자를 자동으로 읽는 기능은 아직 쓸 수 없어요. 의심되는 문자를 복사해서 이 앱에 붙여 넣으면 확인할 수 있어요.",
    )

    override fun start(onMessage: suspend (IncomingSms) -> Unit) = Unit
    override fun stop() = Unit
}
