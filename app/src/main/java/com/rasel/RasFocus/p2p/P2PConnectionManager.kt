package com.rasel.RasFocus.p2p

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket

sealed class P2PMessage {
    data class Text(val message: String, val isMe: Boolean = false) : P2PMessage()
    data class FileMsg(val fileName: String, val filePath: String, val isMe: Boolean = false) : P2PMessage()
    data class Voice(val filePath: String, val isMe: Boolean = false) : P2PMessage()
}

class P2PConnectionManager(private val downloadDir: File) {
    private var serverSocket: ServerSocket? = null
    var clientSocket: Socket? = null
        private set
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    private val _messages = MutableSharedFlow<P2PMessage>()
    val messages: SharedFlow<P2PMessage> = _messages

    val isConnected: Boolean
        get() = clientSocket?.isConnected == true

    fun startServer(port: Int, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.d("P2P", "Server started on port $port")
                while (true) {
                    val socket = serverSocket!!.accept()
                    handleSocket(socket, scope)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun connectToDevice(ip: String, port: Int, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(ip, port)
                handleSocket(socket, scope)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleSocket(socket: Socket, scope: CoroutineScope) {
        clientSocket = socket
        dataIn = DataInputStream(socket.getInputStream())
        dataOut = DataOutputStream(socket.getOutputStream())
        Log.d("P2P", "Connected to ${socket.inetAddress.hostAddress}")
        
        // Listen for messages
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val type = dataIn!!.readInt()
                    when (type) {
                        1 -> { // Text
                            val msg = dataIn!!.readUTF()
                            _messages.emit(P2PMessage.Text(msg))
                        }
                        2 -> { // File
                            val fileName = dataIn!!.readUTF()
                            val length = dataIn!!.readLong()
                            val file = File(downloadDir, fileName)
                            val fos = FileOutputStream(file)
                            val buffer = ByteArray(4096)
                            var remaining = length
                            while (remaining > 0) {
                                val read = dataIn!!.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                remaining -= read
                            }
                            fos.close()
                            _messages.emit(P2PMessage.FileMsg(fileName, file.absolutePath))
                        }
                        3 -> { // Voice
                            val length = dataIn!!.readLong()
                            val file = File(downloadDir, "voice_${System.currentTimeMillis()}.m4a")
                            val fos = FileOutputStream(file)
                            val buffer = ByteArray(4096)
                            var remaining = length
                            while (remaining > 0) {
                                val read = dataIn!!.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                remaining -= read
                            }
                            fos.close()
                            _messages.emit(P2PMessage.Voice(file.absolutePath))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun sendText(msg: String) {
        withContext(Dispatchers.IO) {
            try {
                dataOut?.apply {
                    writeInt(1)
                    writeUTF(msg)
                    flush()
                }
                _messages.emit(P2PMessage.Text(msg, true))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    suspend fun sendFile(file: File) {
        withContext(Dispatchers.IO) {
            try {
                dataOut?.apply {
                    writeInt(2)
                    writeUTF(file.name)
                    writeLong(file.length())
                    val fis = file.inputStream()
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        write(buffer, 0, read)
                    }
                    fis.close()
                    flush()
                }
                _messages.emit(P2PMessage.FileMsg(file.name, file.absolutePath, true))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    suspend fun sendVoice(file: File) {
        withContext(Dispatchers.IO) {
            try {
                dataOut?.apply {
                    writeInt(3)
                    writeLong(file.length())
                    val fis = file.inputStream()
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        write(buffer, 0, read)
                    }
                    fis.close()
                    flush()
                }
                _messages.emit(P2PMessage.Voice(file.absolutePath, true))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) {}
        try { clientSocket?.close() } catch (e: Exception) {}
    }
}
