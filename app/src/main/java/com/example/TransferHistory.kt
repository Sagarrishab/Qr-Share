package com.example

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val fileSize: Long,
    val direction: String, // "INCOMING" or "OUTGOING"
    val timestamp: Long,
    val filePath: String,
    val peerIp: String,
    val backupStatus: String = "NOT_BACKED_UP", // "NOT_BACKED_UP", "BACKED_UP", "FAILED", "UPLOADING"
    val backupTarget: String = "LOCAL" // "DROPBOX", "GOOGLE_DRIVE", "LOCAL"
)

@Entity(tableName = "cloud_backup_config")
data class CloudBackupConfig(
    @PrimaryKey val id: Int = 1,
    val provider: String = "NONE", // "NONE", "GOOGLE_DRIVE", "DROPBOX", "WEBHOOK"
    val isAutoBackupEnabled: Boolean = false,
    val accessToken: String = "",
    val targetFolder: String = "QRFileShare",
    val connectionStatus: String = "Not Configured", // "Not Configured", "Connected", "Failed"
    val lastBackupTime: Long = 0L
)

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(record: TransferRecord): Long

    @Delete
    suspend fun deleteTransfer(record: TransferRecord)

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()

    // Config operations
    @Query("SELECT * FROM cloud_backup_config WHERE id = 1")
    fun getBackupConfigFlux(): Flow<CloudBackupConfig?>

    @Query("SELECT * FROM cloud_backup_config WHERE id = 1")
    suspend fun getBackupConfig(): CloudBackupConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBackupConfig(config: CloudBackupConfig)
}

@Database(entities = [TransferRecord::class, CloudBackupConfig::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transfer_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
