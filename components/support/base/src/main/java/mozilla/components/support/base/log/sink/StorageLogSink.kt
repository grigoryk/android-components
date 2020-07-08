/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.support.base.log.sink

import android.content.Context
import androidx.annotation.Nullable
import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.support.base.log.Log

class StorageLogSink(private val context: Context) : LogSink {
    override fun log(priority: Log.Priority, tag: String?, throwable: Throwable?, message: String?) {
        if (message == null) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            LogStorage.dao(context).addLogEntry(LogEntry(
                timestamp = System.currentTimeMillis(),
                priority = priority.asInt(),
                tag = tag,
                message = message
            ))
        }
    }
}

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val uid: Int = 0,

    val timestamp: Long,

    @ColumnInfo(index = true)
    val priority: Int,

    @ColumnInfo(index = true, defaultValue = "NULL")
    @Nullable
    val tag: String?,

    val message: String
)

//internal const val LOG_CUTOFF_WINDOW = 1000L * 60 * 60 * 24 // 24 hours
internal const val LOG_CUTOFF_WINDOW = 1000L * 10 // 24 hours

@Dao
interface LogsDao {
//    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
//    abstract suspend fun getAll(): LiveData<List<LogEntry>>
//
//    @Query("SELECT DISTINCT tag FROM log_entries")
//    abstract suspend fun getTags(): LiveData<List<String>>
//
//    @Query("SELECT * FROM log_entries WHERE tag = :tag")
//    abstract suspend fun getByTag(tag: String): LiveData<List<LogEntry>>
//
//    @Query("SELECT * FROM log_entries WHERE tag IN (:tags)")
//    abstract suspend fun getByTags(tags: List<String>): LiveData<List<LogEntry>>
//
    @Query("SELECT * FROM log_entries WHERE tag IN (:tags) AND priority IN (:priorities)")
    abstract fun filteredLogs(tags: List<String>, priorities: List<Int>): LiveData<List<LogEntry>>

    @Transaction
    suspend fun addLogEntry(logEntry: LogEntry) {
        // TODO collect up a few log entries before bulk-inserting them?
        deleteStaleLogs(System.currentTimeMillis() - LOG_CUTOFF_WINDOW)
        insertLogEntry(logEntry)
    }

    @Insert
    suspend fun insertLogEntry(logEntry: LogEntry)

    @Query("DELETE FROM log_entries WHERE timestamp < :cutoffTimestampMs")
    fun deleteStaleLogs(cutoffTimestampMs: Long)
}

@Database(entities = [LogEntry::class], version = 1)
internal abstract class LogDatabase : RoomDatabase() {
    abstract fun logsDao(): LogsDao
}

object LogStorage {
    private var db: LogDatabase? = null

    @Synchronized
    fun dao(applicationContext: Context): LogsDao {
        db?.let { return it.logsDao() }

        db = Room.databaseBuilder(
            applicationContext, LogDatabase::class.java, "logs-db"
        ).build()

        return db!!.logsDao()
    }
}

internal fun Log.Priority.asInt(): Int = when(this) {
    Log.Priority.DEBUG -> 1
    Log.Priority.INFO -> 2
    Log.Priority.WARN -> 3
    Log.Priority.ERROR -> 4
}
