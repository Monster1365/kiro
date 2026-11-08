package com.smishingguard.data.remote

import com.smishingguard.data.SettingsStore
import com.smishingguard.domain.AlertResult
import com.smishingguard.domain.AlertStatus
import com.smishingguard.domain.AnalysisResult
import com.smishingguard.domain.GuardianRemote
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.RiskLevel
import com.smishingguard.domain.ShareLevel
import com.smishingguard.domain.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** REST 서버 연동. 요청/응답 본문은 절대 로그에 남기지 않는다. */
class HttpBackend(
    private val baseUrl: String,
    private val settings: SettingsStore,
) : Backend {

    private val registerLock = Mutex()

    private class Response(val status: Int, val json: JSONObject)

    override suspend fun analyze(messageBody: String, sender: String?, source: SourceType): AnalysisResult {
        val body = JSONObject()
            .put("message_body", messageBody)
            .put("source_type", source.name)
        if (!sender.isNullOrBlank()) body.put("sender", sender)
        val j = call("POST", "/api/v1/analyses", body)
        return parseAnalysis(j)
    }

    override suspend fun requestGuardianVerification(
        name: String,
        phoneNumber: String,
        relationship: String?,
        alertConsent: Boolean,
    ): GuardianRemote {
        val body = JSONObject()
            .put("name", name)
            .put("phone_number", phoneNumber)
            .put("alert_consent", alertConsent)
        if (!relationship.isNullOrBlank()) body.put("relationship", relationship)
        return parseGuardian(call("POST", "/api/v1/guardians/verification-requests", body))
    }

    override suspend fun verifyGuardian(guardianId: String, code: String): GuardianRemote =
        parseGuardian(
            call("POST", "/api/v1/guardians/verifications", JSONObject().put("guardian_id", guardianId).put("code", code)),
        )

    override suspend fun deleteGuardian(guardianId: String) {
        call("DELETE", "/api/v1/guardians/$guardianId", null)
    }

    override suspend fun setGuardianAlertConsent(guardianId: String, enabled: Boolean) {
        call("PATCH", "/api/v1/guardians/$guardianId", JSONObject().put("alert_consent", enabled))
    }

    override suspend fun sendGuardianAlert(
        analysisId: String,
        guardianId: String,
        shareLevel: ShareLevel,
        sender: String?,
        excerpt: String?,
        idempotencyKey: String,
    ): AlertResult {
        val body = JSONObject()
            .put("analysis_id", analysisId)
            .put("guardian_id", guardianId)
            .put("share_level", shareLevel.name)
            .put("idempotency_key", idempotencyKey)
        // 공유 범위에 포함되지 않은 값은 아예 전송하지 않는다.
        if (shareLevel != ShareLevel.MINIMAL && !sender.isNullOrBlank()) body.put("sender", sender)
        if (shareLevel == ShareLevel.WITH_EXCERPT && !excerpt.isNullOrBlank()) body.put("excerpt", excerpt)
        val j = call("POST", "/api/v1/guardian-alerts", body)
        return when (j.optString("status")) {
            "SENT" -> AlertResult(AlertStatus.SENT)
            "FAILED" -> AlertResult(AlertStatus.FAILED, j.optString("failure_code").ifBlank { null })
            "REQUESTED" -> AlertResult(AlertStatus.REQUESTED)
            else -> throw BackendException(ErrorKind.INVALID_RESPONSE, null, "서버 응답을 이해하지 못했어요.")
        }
    }

    // ---------- 파싱 (형식이 다르면 INVALID_RESPONSE → 분석 불가로 처리) ----------

    private fun parseAnalysis(j: JSONObject): AnalysisResult {
        try {
            if (j.getString("analysis_status") != "SUCCESS") {
                throw BackendException(ErrorKind.SERVER, null, "분석에 실패했어요.")
            }
            val level = RiskLevel.parse(j.getString("risk_level"))
            if (level == null || level == RiskLevel.UNKNOWN) {
                throw BackendException(ErrorKind.INVALID_RESPONSE, null, "서버 응답을 이해하지 못했어요.")
            }
            return AnalysisResult(
                analysisId = j.optString("analysis_id").ifBlank { null },
                riskLevel = level,
                summary = j.getString("summary"),
                reasons = j.getJSONArray("reasons").toStrings(),
                recommendedActions = j.getJSONArray("recommended_actions").toStrings(),
            )
        } catch (e: JSONException) {
            throw BackendException(ErrorKind.INVALID_RESPONSE, null, "서버 응답을 이해하지 못했어요.")
        }
    }

    private fun parseGuardian(j: JSONObject): GuardianRemote = try {
        GuardianRemote(
            guardianId = j.getString("guardian_id"),
            name = j.getString("name"),
            phoneMasked = j.getString("phone_masked"),
            status = GuardianStatus.entries.firstOrNull { it.name == j.getString("verification_status") }
                ?: throw JSONException("status"),
            devCode = j.optString("dev_code").ifBlank { null },
        )
    } catch (e: JSONException) {
        throw BackendException(ErrorKind.INVALID_RESPONSE, null, "서버 응답을 이해하지 못했어요.")
    }

    private fun JSONArray.toStrings(): List<String> = (0 until length()).map { getString(it) }

    // ---------- HTTP ----------

    private suspend fun ensureToken(): String = registerLock.withLock {
        settings.getToken()?.let { return it }
        val res = rawCall("POST", "/api/v1/installations", null, null)
        val token = res.json.optString("token")
        if (res.status !in 200..299 || token.isBlank()) {
            throw BackendException(ErrorKind.SERVER, null, "서비스에 연결하지 못했어요.")
        }
        settings.saveToken(token)
        token
    }

    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject {
        var token = ensureToken()
        var res = rawCall(method, path, body, token)
        if (res.status == 401) { // 토큰 만료/서버 초기화 → 한 번만 재등록
            settings.clearToken()
            token = ensureToken()
            res = rawCall(method, path, body, token)
        }
        if (res.status in 200..299) return res.json
        val err = res.json.optJSONObject("error")
        val code = err?.optString("code")
        val message = err?.optString("message")?.ifBlank { null }
        throw when {
            res.status >= 500 -> BackendException(ErrorKind.SERVER, code, "서버에 문제가 있어요. 잠시 후 다시 시도해 주세요.")
            else -> BackendException(ErrorKind.CLIENT, code, message ?: "요청을 처리하지 못했어요.")
        }
    }

    private suspend fun rawCall(method: String, path: String, body: JSONObject?, token: String?): Response =
        withContext(Dispatchers.IO) {
            val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                // HttpURLConnection 은 PATCH 를 지원하지 않아 override 헤더를 사용한다.
                if (method == "PATCH") {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    conn.requestMethod = method
                }
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (e: JSONException) {
                    if (status in 200..299) {
                        throw BackendException(ErrorKind.INVALID_RESPONSE, null, "서버 응답을 이해하지 못했어요.")
                    }
                    JSONObject()
                }
                Response(status, json)
            } catch (e: IOException) {
                throw BackendException(ErrorKind.NETWORK, null, "인터넷 연결을 확인해 주세요.")
            } finally {
                conn.disconnect()
            }
        }
}
