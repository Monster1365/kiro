package com.smishingguard.data.remote

import com.smishingguard.domain.AlertResult
import com.smishingguard.domain.AnalysisResult
import com.smishingguard.domain.GuardianRemote
import com.smishingguard.domain.ShareLevel
import com.smishingguard.domain.SourceType

enum class ErrorKind { NETWORK, SERVER, INVALID_RESPONSE, CLIENT }

/** 사용자에게 보여줄 수 있는 메시지만 담는다. 문자 원문/전화번호를 포함하지 않는다. */
class BackendException(val kind: ErrorKind, val code: String?, message: String) : Exception(message)

/** 서버(또는 모의 구현) 연동 인터페이스. 앱에는 AI/SMS 비밀키가 없고 서버가 대신 호출한다. */
interface Backend {
    suspend fun analyze(messageBody: String, sender: String?, source: SourceType): AnalysisResult

    suspend fun requestGuardianVerification(
        name: String,
        phoneNumber: String,
        relationship: String?,
        alertConsent: Boolean,
    ): GuardianRemote

    suspend fun verifyGuardian(guardianId: String, code: String): GuardianRemote
    suspend fun deleteGuardian(guardianId: String)
    suspend fun setGuardianAlertConsent(guardianId: String, enabled: Boolean)

    suspend fun sendGuardianAlert(
        analysisId: String,
        guardianId: String,
        shareLevel: ShareLevel,
        sender: String?,
        excerpt: String?,
        idempotencyKey: String,
    ): AlertResult
}
