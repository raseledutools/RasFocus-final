package com.rasel.RasFocus.selfcontrol.study_tools

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ============================================================
// ENTITY
// ============================================================
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = false)
    val id: Long = System.currentTimeMillis(),
    val title: String = "",
    val body: String = "",
    val folder: String = "General",
    val mood: String = "",
    val tags: List<String> = emptyList(),
    val date: String = "",
    val timestamp: Long = System.currentTimeMillis(),

    // Lock
    val isLocked: Boolean = false,
    val pinHash: String = "",          // SHA-256 of PIN (empty = no PIN lock)

    // Reminder
    val reminderTimeMillis: Long = 0L,
    val reminderLabel: String = "",

    // Media: stored as "|||"-separated "image:/path" or "voice:/path" strings
    // Using List<String> via separate TypeConverter column name avoids
    // Room KSP duplicate-converter conflict.
    @ColumnInfo(name = "media_paths")
    val mediaPaths: List<String> = emptyList(),
)

// ============================================================
// TYPE CONVERTERS
// Room KSP requires each (from, to) type pair to have exactly ONE converter.
// tags and mediaPaths both need List<String> ↔ String.
// Solution: use a single converter pair — Room applies it to all List<String> columns.
// ============================================================
class Converters {
    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString("|||")

    @TypeConverter
    fun toStringList(data: String): List<String> =
        if (data.isBlank()) emptyList() else data.split("|||").filter { it.isNotEmpty() }
}

// ============================================================
// DAO
// ============================================================
@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE folder = :folderName ORDER BY timestamp DESC")
    fun getEntriesByFolder(folderName: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE date LIKE '%' || :dateFragment || '%' ORDER BY timestamp DESC")
    fun getEntriesByDate(dateFragment: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE reminderTimeMillis > 0 ORDER BY reminderTimeMillis ASC")
    fun getEntriesWithReminder(): Flow<List<DiaryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: DiaryEntry)

    @Delete
    suspend fun deleteEntry(entry: DiaryEntry)

    @Query("SELECT * FROM diary_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryById(entryId: Long): DiaryEntry?

    @Query("SELECT * FROM diary_entries ORDER BY timestamp DESC")
    suspend fun getAllEntriesOnce(): List<DiaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DiaryEntry>)
}

// ============================================================
// DATABASE — version 4 (mediaPaths column added with @ColumnInfo)
// ============================================================
@Database(
    entities = [DiaryEntry::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao

    companion object {
        @Volatile
        private var INSTANCE: DiaryDatabase? = null

        fun getDatabase(context: android.content.Context): DiaryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DiaryDatabase::class.java,
                    "diary_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
