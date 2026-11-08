package com.smishingguard.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 탐지 이력. 민감 필드(sender, messageBodyEnc)는 CryptoBox 로 암호화된 문자열이다.
 * 원문 저장 동의가 없으면 sender/messageBodyEnc/reasons/actions 는 비워 두고 위험 수준과 요약만 저장한다.
 */
@Entity(tableName = "detections", indices = [Index(value = ["deduplicationKey"], unique = true)])
data class DetectionEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val senderEnc: String?,
    val messageBodyEnc: String?,
    val riskLevel: String,
    val summary: String,
    val reasonsJson: String,
    val actionsJson: String,
    val analyzedAt: Long,
    val guardianAlertStatus: String,
    val feedback: String?,
    /** 자동 수신 경로의 중복 처리 방지용. 개인정보와 연결될 수 있어 이력과 같은 보관 기간을 따른다. */
    val deduplicationKey: String?,
)

@Dao
interface DetectionDao {
    @Query("SELECT * FROM detections ORDER BY analyzedAt DESC")
    fun observeAll(): Flow<List<DetectionEntity>>

    @Query("SELECT * FROM detections WHERE id = :id")
    fun observe(id: String): Flow<DetectionEntity?>

    @Query("SELECT * FROM detections WHERE id = :id")
    suspend fun get(id: String): DetectionEntity?

    @Query("SELECT COUNT(*) FROM detections WHERE deduplicationKey = :key")
    suspend fun countByDedup(key: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DetectionEntity): Long

    @Query("UPDATE detections SET guardianAlertStatus = :status WHERE id = :id")
    suspend fun updateAlertStatus(id: String, status: String)

    @Query("UPDATE detections SET feedback = :feedback WHERE id = :id")
    suspend fun updateFeedback(id: String, feedback: String)

    @Query("DELETE FROM detections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM detections")
    suspend fun deleteAll()

    @Query("DELETE FROM detections WHERE analyzedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Database(entities = [DetectionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun detectionDao(): DetectionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "smishing_guard.db").build()
    }
}
