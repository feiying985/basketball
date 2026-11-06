package com.feiying.basketball.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "analysis_records")
data class AnalysisRecordEntity(
    @PrimaryKey val localId: String,
    val serverJobId: String?,
    val createdAt: Long,
    val source: String,
    val localVideoPath: String?,
    val filename: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val status: String,
    val hoopRoi: String? = null,
    val resultJson: String?,
    val errorCode: String?,
    val errorDetail: String?,
    val updatedAt: Long,
)

@Dao
interface AnalysisRecordDao {
    @Query("SELECT * FROM analysis_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AnalysisRecordEntity>>

    @Query("SELECT * FROM analysis_records WHERE localId = :localId")
    suspend fun get(localId: String): AnalysisRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AnalysisRecordEntity)

    @Query(
        "UPDATE analysis_records SET status = :newStatus, updatedAt = :updatedAt " +
            "WHERE status = :oldStatus",
    )
    suspend fun updateStatusForAll(
        oldStatus: String,
        newStatus: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE analysis_records SET status = :status, updatedAt = :updatedAt " +
            "WHERE localId = :localId",
    )
    suspend fun updateStatus(localId: String, status: String, updatedAt: Long)

    @Query(
        "UPDATE analysis_records SET serverJobId = :serverJobId, status = :status, " +
            "errorCode = NULL, errorDetail = NULL, updatedAt = :updatedAt " +
            "WHERE localId = :localId",
    )
    suspend fun markQueued(
        localId: String,
        serverJobId: String,
        status: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE analysis_records SET status = :status, resultJson = :resultJson, " +
            "errorCode = NULL, errorDetail = NULL, updatedAt = :updatedAt " +
            "WHERE localId = :localId",
    )
    suspend fun markCompleted(
        localId: String,
        status: String,
        resultJson: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE analysis_records SET status = :status, errorCode = :errorCode, " +
            "errorDetail = :errorDetail, updatedAt = :updatedAt WHERE localId = :localId",
    )
    suspend fun markFailed(
        localId: String,
        status: String,
        errorCode: String?,
        errorDetail: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE analysis_records SET serverJobId = NULL, status = :status, " +
            "resultJson = NULL, errorCode = NULL, errorDetail = NULL, updatedAt = :updatedAt " +
            "WHERE localId = :localId",
    )
    suspend fun resetForRetry(localId: String, status: String, updatedAt: Long)

    @Query(
        "UPDATE analysis_records SET status = :status, errorCode = :errorCode, " +
            "updatedAt = :updatedAt WHERE localId = :localId AND status = :expectedStatus",
    )
    suspend fun markInterrupted(
        localId: String,
        expectedStatus: String,
        status: String,
        errorCode: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM analysis_records WHERE localId = :localId")
    suspend fun delete(localId: String)
}

@Database(
    entities = [AnalysisRecordEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisRecordDao(): AnalysisRecordDao

    companion object {
        const val NAME = "basketball.db"

        /** Adds the optional manual hoop rectangle without dropping history. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE analysis_records ADD COLUMN hoopRoi TEXT")
            }
        }
    }
}
