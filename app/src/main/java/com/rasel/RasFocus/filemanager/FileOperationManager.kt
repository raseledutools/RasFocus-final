package com.rasel.RasFocus.filemanager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class OperationType { COPY, MOVE, DELETE }

data class FileOperation(
    val id: String = UUID.randomUUID().toString(),
    val type: OperationType,
    val sourceCount: Int,
    val itemsProcessed: Int = 0,
    val totalBytes: Long = 0L,
    val bytesProcessed: Long = 0L,
    val currentFileName: String = "",
    val isComplete: Boolean = false,
    val isCancelled: Boolean = false,
    val isError: Boolean = false
) {
    val progress: Float
        get() = if (totalBytes > 0) (bytesProcessed.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

object FileOperationManager {
    private val _operations = MutableStateFlow<List<FileOperation>>(emptyList())
    val operations: StateFlow<List<FileOperation>> = _operations.asStateFlow()

    fun addOperation(operation: FileOperation) {
        _operations.update { it + operation }
    }

    fun updateOperation(id: String, update: (FileOperation) -> FileOperation) {
        _operations.update { list ->
            list.map { if (it.id == id) update(it) else it }
        }
    }

    fun removeOperation(id: String) {
        _operations.update { list -> list.filter { it.id != id } }
    }

    fun clearCompleted() {
        _operations.update { list -> list.filter { !it.isComplete && !it.isCancelled && !it.isError } }
    }
}
