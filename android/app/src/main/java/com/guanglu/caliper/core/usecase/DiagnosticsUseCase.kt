package com.guanglu.caliper.core.usecase

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A7：原始帧 / 指令 / 回包 / 错误码的环形日志，M0 实机排障用，可导出为 CSV。 */
class DiagnosticsUseCase(private val context: Context) {

    private val lines = ArrayDeque<String>()
    private val maxLines = 5000

    @Synchronized
    fun log(event: String) {
        if (lines.size >= maxLines) lines.removeFirst()
        lines.addLast("${System.currentTimeMillis()},${event.replace(",", ";")}")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val body = buildString {
            append("TIMESTAMP,EVENT\r\n")
            snapshot().forEach { append(it).append("\r\n") }
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return@withContext 0
        snapshot().size
    }
}
