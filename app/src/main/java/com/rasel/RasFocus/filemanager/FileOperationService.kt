package com.rasel.RasFocus.filemanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rasel.RasFocus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class FileOperationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val CHANNEL_ID = "file_operation_channel"
    private val NOTIFICATION_ID = 401

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_START") {
            val opId = intent.getStringExtra("OP_ID") ?: return START_NOT_STICKY
            val sourcePaths = intent.getStringArrayListExtra("SOURCE_PATHS") ?: emptyList<String>()
            val destPath = intent.getStringExtra("DEST_PATH") ?: return START_NOT_STICKY
            val isCut = intent.getBooleanExtra("IS_CUT", false)
            val sourceEnv = intent.getStringExtra("SOURCE_ENV") ?: "Local"
            
            // Only handle local to local for now, can extend later
            if (sourceEnv == "Local") {
                startForeground(NOTIFICATION_ID, createNotification("Starting file operation..."))
                startLocalOperation(opId, sourcePaths, destPath, isCut)
            }
        } else if (action == "ACTION_CANCEL") {
            val opId = intent.getStringExtra("OP_ID")
            if (opId != null) {
                FileOperationManager.updateOperation(opId) { it.copy(isCancelled = true) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startLocalOperation(opId: String, sources: List<String>, destDir: String, isCut: Boolean) {
        serviceScope.launch {
            val destDirFile = File(destDir)
            if (!destDirFile.exists()) destDirFile.mkdirs()
            
            var totalBytes = 0L
            for (path in sources) {
                val f = File(path)
                if (f.exists()) {
                    totalBytes += getFolderSize(f)
                }
            }

            FileOperationManager.updateOperation(opId) {
                it.copy(totalBytes = totalBytes)
            }

            var globalBytesProcessed = 0L
            var itemsProcessed = 0
            var speedWindowStart = System.currentTimeMillis()
            var speedWindowBytes = 0L

            for (path in sources) {
                val opState = FileOperationManager.operations.value.find { it.id == opId }
                if (opState?.isCancelled == true) break

                val src = File(path)
                if (!src.exists()) continue

                val dst = File(destDirFile, src.name)

                // Update source/dest paths for dialog display
                FileOperationManager.updateOperation(opId) {
                    it.copy(
                        currentSourcePath = src.absolutePath,
                        currentDestPath = dst.absolutePath
                    )
                }
                
                try {
                    copyFileOrDir(src, dst, opId) { bytesCopied ->
                        globalBytesProcessed += bytesCopied
                        speedWindowBytes += bytesCopied
                        val now = System.currentTimeMillis()
                        val elapsed = now - speedWindowStart
                        val speed = if (elapsed > 0) (speedWindowBytes * 1000L / elapsed) else 0L
                        // Reset speed window every second
                        if (elapsed >= 1000L) {
                            speedWindowStart = now
                            speedWindowBytes = 0L
                        }
                        FileOperationManager.updateOperation(opId) {
                            it.copy(
                                bytesProcessed = globalBytesProcessed,
                                speedBytesPerSec = speed
                            )
                        }
                        updateNotificationProgress(opState?.type ?: OperationType.COPY, globalBytesProcessed, totalBytes)
                    }
                    
                    if (isCut && FileOperationManager.operations.value.find { it.id == opId }?.isCancelled != true) {
                        src.deleteRecursively()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    FileOperationManager.updateOperation(opId) { it.copy(isError = true) }
                }
                
                itemsProcessed++
                FileOperationManager.updateOperation(opId) { it.copy(itemsProcessed = itemsProcessed) }
            }

            FileOperationManager.updateOperation(opId) { it.copy(isComplete = true) }
            
            // Check if there are other operations still running
            val hasRunning = FileOperationManager.operations.value.any { !it.isComplete && !it.isCancelled && !it.isError }
            if (!hasRunning) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    
    private suspend fun copyFileOrDir(src: File, dst: File, opId: String, onProgress: (Long) -> Unit) {
        val opState = FileOperationManager.operations.value.find { it.id == opId }
        if (opState?.isCancelled == true) return
        
        FileOperationManager.updateOperation(opId) { it.copy(currentFileName = src.name) }
        
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyFileOrDir(child, File(dst, child.name), opId, onProgress)
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    FileInputStream(src).use { fis ->
                        FileOutputStream(dst).use { fos ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } > 0) {
                                if (FileOperationManager.operations.value.find { it.id == opId }?.isCancelled == true) {
                                    break
                                }
                                fos.write(buffer, 0, bytesRead)
                                onProgress(bytesRead.toLong())
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }
            }
        }
    }

    private fun getFolderSize(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { size += getFolderSize(it) }
        return size
    }

    private fun createNotification(text: String, progress: Int = 0, max: Int = 100): android.app.Notification {
        val intent = Intent(this, FileManagerPlusActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("File Operation")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            
        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        
        return builder.build()
    }

    private var lastNotifTime = 0L
    private fun updateNotificationProgress(type: OperationType, current: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotifTime < 500) return // Throttle updates
        lastNotifTime = now
        
        val percent = if (total > 0) ((current.toFloat() / total) * 100).toInt() else 0
        val text = if (type == OperationType.COPY) "Copying... $percent%" else "Moving... $percent%"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text, percent, 100))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Operations",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

