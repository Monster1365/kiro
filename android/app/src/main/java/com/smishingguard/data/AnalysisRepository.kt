package com.smishingguard.data

import com.smishingguard.data.local.DetectionDao
import com.smishingguard.data.local.DetectionEntity
import com.smishingguard.data.remote.Backend
import com.smishingguard.data.remote.BackendException
import com.smishingguard.data.remote.ErrorKind
import com.smishingguard.domain.AlertResult
import com.smishingguard.domain.AlertStatus
import com.smishingguard.domain.AnalysisResult
import com.smishingguard.domain.DetectionRecord
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.RiskLevel
import com.smishingguard.domain.SourceType
import com.smishingguard.domain.defaultActions
import com.smishingguard.notify.Notifier
import com.smishingguard.sms.IncomingSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface AnalyzeOutcome {
    data class Success(val recordId: String) : AnalyzeOutcome

    /** 서버 분석 동의가 없어 원문을 전송하지 않았다. */
    data object NeedConsent : AnalyzeOutcome

    /** 분석 실패. 정상 문자로 처리하지 않는다. */
    data class Failed(val message: String, val kind: ErrorKind?) : AnalyzeOutcome

    /** 자동 수신 경로에서 이미 처리한 문자 */
    data object Duplicate : AnalyzeOutcome
}

class AnalysisRepository(
    private val dao: DetectionDao,
    private val settings: SettingsStore,
    private val backend: Backend,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 방금 분석한 결과의 상세(판단 근거 등). 원문 저장 동의가 없으면 DB 에는 위험 수준과 요약만 남기므로,
     * 결과 화면은 이 메모리 캐시에서 상세를 보여준다. 앱 종료 시 사라진다.
     */
    private val transient = ConcurrentHashMap<String, AnalysisResult>()

    val records: Flow<List<DetectionRecord>> = dao.observeAll().map { list -> list.map(::toRecord) }
    fun observe(id: String): Flow<DetectionRecord?> = dao.observe(id).map { it?.let(::toRecord) }

    /** 수동 입력 분석 */
    suspend fun analyzeManual(body: String, sender: String?): AnalyzeOutcome =
        analyze(body.trim(), sender?.trim()?.ifBlank { null }, SourceType.MANUAL, dedupKey = null)

    /** 자동 수신 경로에서 호출 (IncomingSmsSource 구현체가 연결되었을 때) */
    suspend fun analyzeIncoming(sms: IncomingSms): AnalyzeOutcome =
        analyze(sms.body, sms.sender, SourceType.AUTO, sms.deduplicationKey)

    private suspend fun analyze(body: String, sender: String?, source: SourceType, dedupKey: String?): AnalyzeOutcome {
        if (body.isBlank()) return AnalyzeOutcome.Failed("문자 내용을 입력해 주세요.", null)
        val s = settings.current()
        // 동의가 없으면 원문을 서버로 보내지 않는다.
        if (!s.analysisConsent) return AnalyzeOutcome.NeedConsent
        if (dedupKey != null && dao.countByDedup(dedupKey) > 0) return AnalyzeOutcome.Duplicate

        val result = try {
            backend.analyze(body, sender, source)
        } catch (e: BackendException) {
            return AnalyzeOutcome.Failed(failureMessage(e.message), e.kind)
        }
        if (result.riskLevel == RiskLevel.UNKNOWN) return AnalyzeOutcome.Failed(failureMessage(null), ErrorKind.INVALID_RESPONSE)

        val id = UUID.randomUUID().toString()
        val save = s.localOriginalSaveConsent
        transient[id] = result
        val entity = DetectionEntity(
            id = id,
            sourceType = source.name,
            senderEnc = if (save) sender?.let(CryptoBox::encrypt) else null,
            messageBodyEnc = if (save) CryptoBox.encrypt(body) else null,
            riskLevel = result.riskLevel.name,
            summary = result.summary,
            reasonsJson = if (save) JSONArray(result.reasons).toString() else "[]",
            actionsJson = if (save) JSONArray(result.recommendedActions).toString() else "[]",
            analyzedAt = now(),
            guardianAlertStatus = AlertStatus.NONE.name,
            feedback = null,
            deduplicationKey = dedupKey,
        )
        if (dao.insert(entity) == -1L) return AnalyzeOutcome.Duplicate

        // 사용자 알림: 실패해도 앱 내 결과는 그대로 확인할 수 있다.
        if (s.notificationEnabled) notifier.notifyRisk(id, result.riskLevel)

        // 보호자 알림은 사용자 경고를 막지 않도록 별도로 진행한다.
        val eligible = result.riskLevel == RiskLevel.HIGH &&
            s.guardianAutoAlertEnabled &&
            s.guardianStatus == GuardianStatus.VERIFIED &&
            s.guardianId != null &&
            result.analysisId != null
        if (eligible) {
            dao.updateAlertStatus(id, AlertStatus.REQUESTED.name)
            scope.launch {
                val r = try {
                    backend.sendGuardianAlert(
                        analysisId = result.analysisId!!,
                        guardianId = s.guardianId!!,
                        shareLevel = s.guardianShareLevel,
                        sender = sender,
                        excerpt = body.take(60),
                        idempotencyKey = "alert_${result.analysisId}_${s.guardianId}",
                    )
                } catch (e: BackendException) {
                    AlertResult(AlertStatus.FAILED, e.code)
                }
                dao.updateAlertStatus(id, r.status.name)
            }
        }
        return AnalyzeOutcome.Success(id)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        transient.remove(id)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        transient.clear()
    }

    /** 설정한 보관 기간이 지난 기기 내 이력 삭제 */
    suspend fun purgeExpired() {
        val days = settings.current().retentionDays
        dao.deleteOlderThan(now() - days * 24L * 60 * 60 * 1000)
    }

    suspend fun setFeedback(id: String, helpful: Boolean) =
        dao.updateFeedback(id, if (helpful) "HELPFUL" else "WRONG")

    private fun failureMessage(serverMessage: String?): String =
        (serverMessage?.let { "$it\n" } ?: "") +
            "문자를 분석하지 못했어요. 정상 문자라는 뜻은 아니에요. 다시 시도하거나, 링크를 누르지 말고 공식 앱이나 공식 전화번호로 직접 확인해 주세요."

    private fun toRecord(e: DetectionEntity): DetectionRecord {
        val level = RiskLevel.parse(e.riskLevel) ?: RiskLevel.UNKNOWN
        val t = transient[e.id]
        val storedReasons = parseArray(e.reasonsJson)
        val storedActions = parseArray(e.actionsJson)
        return DetectionRecord(
            id = e.id,
            sourceType = SourceType.entries.firstOrNull { it.name == e.sourceType } ?: SourceType.MANUAL,
            sender = e.senderEnc?.let(CryptoBox::decrypt),
            messageBody = e.messageBodyEnc?.let(CryptoBox::decrypt),
            riskLevel = level,
            summary = e.summary,
            reasons = storedReasons.ifEmpty { t?.reasons.orEmpty() },
            recommendedActions = storedActions.ifEmpty { t?.recommendedActions ?: defaultActions(level) },
            analyzedAt = e.analyzedAt,
            guardianAlertStatus = AlertStatus.parse(e.guardianAlertStatus),
            feedback = e.feedback,
        )
    }

    private fun parseArray(json: String): List<String> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}
