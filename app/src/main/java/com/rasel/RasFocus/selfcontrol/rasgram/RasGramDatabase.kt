package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramDatabase.kt — WhatsApp-style local cache
//
// Architecture:
//   App open → Room DB থেকে instant load (offline ও কাজ করে)
//             → Background এ Firestore sync
//             → নতুন data → Room update → UI auto-refresh
//
// এটা DiaryDatabase.kt এর same pattern follow করে।
// ============================================================

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ============================================================
// ENTITIES
// ============================================================

@Entity(
    tableName = "cached_messages",
    indices = [Index("chatId"), Index("timestamp")]
)
data class CachedMessage(
    @PrimaryKey
    val id: String,                          // Firestore document ID
    val chatId: String,                      // "pvt_msg_<chatId>" prefix removed
    val text: String = "",                   // plain text (already decrypted)
    val senderMobile: String = "",
    val receiverMobile: String = "",
    val timestamp: Long = 0,
    val timeString: String = "",
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileType: String? = null,
    val fileSizeBytes: Long = 0,
    val thumbnailUrl: String? = null,
    val reaction: String? = null,
    val read: Boolean = false,
    val delivered: Boolean = false,
    val isCallLog: Boolean = false,
    val callStatus: String? = null,
    val callType: String? = null,
    val isPending: Boolean = false,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val isDeleted: Boolean = false,
    val isForwarded: Boolean = false,
    val isStarred: Boolean = false,
    val duration: Int = 0
)

@Entity(
    tableName = "cached_chat_previews",
    indices = [Index("lastTimestamp")]
)
data class CachedChatPreview(
    @PrimaryKey
    val contactMobile: String,               // contact এর phone number = unique key
    val contactName: String = "",
    val contactAvatarUrl: String = "",
    val lastMessageText: String = "",        // plain text
    val lastMessageSender: String = "",
    val lastTimestamp: Long = 0,
    val lastTimeString: String = "",
    val lastFileType: String? = null,
    val lastIsCallLog: Boolean = false,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false
)

// ============================================================
// TYPE CONVERTERS — room এর জন্য (waveform List<Float>)
// ============================================================
class RasGramConverters {
    @TypeConverter
    fun fromFloatList(list: List<Float>): String = list.joinToString(",")

    @TypeConverter
    fun toFloatList(data: String): List<Float> =
        if (data.isBlank()) emptyList()
        else data.split(",").mapNotNull { it.toFloatOrNull() }
}

// ============================================================
// DAOs
// ============================================================

@Dao
interface CachedMessageDao {

    // Chat screen এ: timestamp order এ সব message — Flow = auto UI update
    @Query("SELECT * FROM cached_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessages(chatId: String): Flow<List<CachedMessage>>

    // Last N messages — Chat preview এর জন্য latest message পেতে
    @Query("""
        SELECT * FROM cached_messages 
        WHERE chatId = :chatId 
        ORDER BY timestamp DESC 
        LIMIT 1
    """)
    suspend fun getLatestMessage(chatId: String): CachedMessage?

    // Firestore থেকে আসা message save/update
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: CachedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<CachedMessage>)

    // Delete (soft delete ও আলাদা, এটা full delete — chat clear এর জন্য)
    @Query("DELETE FROM cached_messages WHERE chatId = :chatId")
    suspend fun deleteChatMessages(chatId: String)

    // Unread count — chat list badge এর জন্য
    @Query("""
        SELECT COUNT(*) FROM cached_messages 
        WHERE chatId = :chatId AND senderMobile = :senderMobile AND read = 0
    """)
    suspend fun getUnreadCount(chatId: String, senderMobile: String): Int

    // Mark as read
    @Query("""
        UPDATE cached_messages SET read = 1 
        WHERE chatId = :chatId AND senderMobile = :senderMobile
    """)
    suspend fun markAsRead(chatId: String, senderMobile: String)

    // Star/unstar
    @Query("UPDATE cached_messages SET isStarred = :starred WHERE id = :messageId")
    suspend fun updateStarred(messageId: String, starred: Boolean)

    // Soft delete
    @Query("UPDATE cached_messages SET isDeleted = 1, text = '' WHERE id = :messageId")
    suspend fun softDelete(messageId: String)

    // Pending message sync — আগে pending flag দিয়ে save, Firestore confirm হলে update
    @Query("UPDATE cached_messages SET isPending = :pending WHERE id = :messageId")
    suspend fun updatePending(messageId: String, pending: Boolean)
}

@Dao
interface CachedChatPreviewDao {

    // Chat list — timestamp desc order এ (সবচেয়ে recent উপরে)
    @Query("""
        SELECT * FROM cached_chat_previews 
        WHERE isArchived = 0
        ORDER BY isPinned DESC, lastTimestamp DESC
    """)
    fun getChatPreviews(): Flow<List<CachedChatPreview>>

    // Single preview update
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreview(preview: CachedChatPreview)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreviews(previews: List<CachedChatPreview>)

    // Unread count update
    @Query("UPDATE cached_chat_previews SET unreadCount = :count WHERE contactMobile = :mobile")
    suspend fun updateUnreadCount(mobile: String, count: Int)

    // Pin/mute/archive
    @Query("UPDATE cached_chat_previews SET isPinned = :pinned WHERE contactMobile = :mobile")
    suspend fun updatePinned(mobile: String, pinned: Boolean)

    @Query("UPDATE cached_chat_previews SET isMuted = :muted WHERE contactMobile = :mobile")
    suspend fun updateMuted(mobile: String, muted: Boolean)

    @Query("UPDATE cached_chat_previews SET isArchived = :archived WHERE contactMobile = :mobile")
    suspend fun updateArchived(mobile: String, archived: Boolean)

    // Get single preview (contact info update এর জন্য)
    @Query("SELECT * FROM cached_chat_previews WHERE contactMobile = :mobile LIMIT 1")
    suspend fun getPreview(mobile: String): CachedChatPreview?

    @Query("DELETE FROM cached_chat_previews WHERE contactMobile = :mobile")
    suspend fun deletePreview(mobile: String)
}

// ============================================================
// DATABASE
// ============================================================

@Database(
    entities = [CachedMessage::class, CachedChatPreview::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(RasGramConverters::class)
abstract class RasGramDatabase : RoomDatabase() {
    abstract fun messageDao(): CachedMessageDao
    abstract fun chatPreviewDao(): CachedChatPreviewDao

    companion object {
        @Volatile
        private var INSTANCE: RasGramDatabase? = null

        fun getInstance(context: Context): RasGramDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RasGramDatabase::class.java,
                    "rasgram_cache_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ============================================================
// REPOSITORY — UI এর সাথে DB ও Firestore এর bridge
// ============================================================

class RasGramRepository(context: Context) {
    private val db = RasGramDatabase.getInstance(context)
    val messageDao = db.messageDao()
    val chatPreviewDao = db.chatPreviewDao()

    // Message data class → CachedMessage conversion
    fun Message.toCached(chatId: String): CachedMessage = CachedMessage(
        id = id,
        chatId = chatId,
        text = text,
        senderMobile = senderMobile,
        receiverMobile = receiverMobile,
        timestamp = timestamp,
        timeString = timeString,
        fileUrl = fileUrl,
        fileName = fileName,
        fileType = fileType,
        fileSizeBytes = fileSizeBytes,
        thumbnailUrl = thumbnailUrl,
        reaction = reaction,
        read = read,
        delivered = delivered,
        isCallLog = isCallLog,
        callStatus = callStatus,
        callType = callType,
        isPending = isPending,
        replyToId = replyToId,
        replyToText = replyToText,
        replyToSender = replyToSender,
        isDeleted = isDeleted,
        isForwarded = isForwarded,
        isStarred = isStarred,
        duration = duration
    )

    // CachedMessage → Message conversion (UI data class)
    fun CachedMessage.toMessage(): Message = Message(
        id = id,
        text = text,
        senderMobile = senderMobile,
        receiverMobile = receiverMobile,
        timestamp = timestamp,
        timeString = timeString,
        fileUrl = fileUrl,
        fileName = fileName,
        fileType = fileType,
        fileSizeBytes = fileSizeBytes,
        thumbnailUrl = thumbnailUrl,
        reaction = reaction,
        read = read,
        delivered = delivered,
        isCallLog = isCallLog,
        callStatus = callStatus,
        callType = callType,
        isPending = isPending,
        replyToId = replyToId,
        replyToText = replyToText,
        replyToSender = replyToSender,
        isDeleted = isDeleted,
        isForwarded = isForwarded,
        isStarred = isStarred,
        duration = duration
    )

    companion object {
        @Volatile
        private var INSTANCE: RasGramRepository? = null

        fun getInstance(context: Context): RasGramRepository {
            return INSTANCE ?: synchronized(this) {
                RasGramRepository(context).also { INSTANCE = it }
            }
        }
    }
}
