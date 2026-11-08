package com.smishingguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smishingguard.domain.AppSettings
import com.smishingguard.domain.GuardianStatus
import com.smishingguard.domain.ShareLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val analysisConsent = booleanPreferencesKey("analysis_consent")
        val originalSave = booleanPreferencesKey("local_original_save_consent")
        val autoAlert = booleanPreferencesKey("guardian_auto_alert_enabled")
        val shareLevel = stringPreferencesKey("guardian_share_level")
        val retentionDays = intPreferencesKey("retention_days")
        val notificationEnabled = booleanPreferencesKey("notification_enabled")
        val guardianId = stringPreferencesKey("guardian_id")
        val guardianName = stringPreferencesKey("guardian_name")
        val guardianPhoneMasked = stringPreferencesKey("guardian_phone_masked")
        val guardianStatus = stringPreferencesKey("guardian_status")
        val tokenEnc = stringPreferencesKey("token_enc")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboarded = p[K.onboarded] ?: false,
            analysisConsent = p[K.analysisConsent] ?: false,
            localOriginalSaveConsent = p[K.originalSave] ?: false,
            guardianAutoAlertEnabled = p[K.autoAlert] ?: false,
            guardianShareLevel = p[K.shareLevel]?.let { s -> ShareLevel.entries.firstOrNull { it.name == s } } ?: ShareLevel.MINIMAL,
            retentionDays = p[K.retentionDays] ?: 30,
            notificationEnabled = p[K.notificationEnabled] ?: true,
            guardianId = p[K.guardianId],
            guardianName = p[K.guardianName],
            guardianPhoneMasked = p[K.guardianPhoneMasked],
            guardianStatus = p[K.guardianStatus]?.let { s -> GuardianStatus.entries.firstOrNull { it.name == s } } ?: GuardianStatus.NONE,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }
    suspend fun setAnalysisConsent(v: Boolean) = edit { it[K.analysisConsent] = v }
    suspend fun setOriginalSaveConsent(v: Boolean) = edit { it[K.originalSave] = v }
    suspend fun setGuardianAutoAlert(v: Boolean) = edit { it[K.autoAlert] = v }
    suspend fun setShareLevel(v: ShareLevel) = edit { it[K.shareLevel] = v.name }
    suspend fun setRetentionDays(v: Int) = edit { it[K.retentionDays] = v }
    suspend fun setNotificationEnabled(v: Boolean) = edit { it[K.notificationEnabled] = v }

    suspend fun saveGuardian(id: String, name: String, phoneMasked: String, status: GuardianStatus) = edit {
        it[K.guardianId] = id
        it[K.guardianName] = name
        it[K.guardianPhoneMasked] = phoneMasked
        it[K.guardianStatus] = status.name
    }

    suspend fun clearGuardian() = edit {
        it.remove(K.guardianId)
        it.remove(K.guardianName)
        it.remove(K.guardianPhoneMasked)
        it[K.guardianStatus] = GuardianStatus.NONE.name
        it[K.autoAlert] = false // 보호자가 없으면 자동 알림도 꺼진다
    }

    /** 서버 토큰은 Keystore 로 암호화해 저장 */
    suspend fun getToken(): String? = context.dataStore.data.first()[K.tokenEnc]?.let { CryptoBox.decrypt(it) }
    suspend fun saveToken(token: String) = edit { it[K.tokenEnc] = CryptoBox.encrypt(token) }
    suspend fun clearToken() = edit { it.remove(K.tokenEnc) }
}
