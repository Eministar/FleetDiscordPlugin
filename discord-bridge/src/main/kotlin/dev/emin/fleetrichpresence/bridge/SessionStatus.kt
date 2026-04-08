package dev.emin.fleetrichpresence.bridge

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Properties

data class SessionStatus(
    val sessionId: String,
    val pid: Long,
    val details: String,
    val projectName: String?,
    val fileName: String?,
    val language: String?,
    val startedAtEpochSecond: Long,
    val lastHeartbeatEpochSecond: Long,
    val sourcePath: Path,
) {
    fun isAlive(now: Instant, staleAfter: Duration): Boolean =
        lastHeartbeatEpochSecond >= now.minus(staleAfter).epochSecond && hasLiveProcess()

    fun hasLiveProcess(): Boolean {
        if (pid <= 0L) {
            return true
        }

        return ProcessHandle.of(pid)
            .map(ProcessHandle::isAlive)
            .orElse(false)
    }

    fun stateLine(): String? = when {
        !projectName.isNullOrBlank() -> projectName
        !fileName.isNullOrBlank() -> fileName
        !language.isNullOrBlank() -> language
        else -> "Fleet session"
    }

    fun fingerprint(): String =
        listOf(
            sessionId,
            details,
            projectName.orEmpty(),
            fileName.orEmpty(),
            language.orEmpty(),
            startedAtEpochSecond.toString(),
            lastHeartbeatEpochSecond.toString(),
        ).joinToString("|")

    companion object {
        @Throws(IOException::class)
        fun fromFile(path: Path): SessionStatus? {
            val properties = Properties()
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)

            val sessionId = properties.getProperty("sessionId")?.takeIf(String::isNotBlank) ?: return null
            val pid = properties.getProperty("pid")?.toLongOrNull() ?: -1L
            val details = properties.getProperty("details")?.ifBlank { "Coding in JetBrains Fleet" } ?: "Coding in JetBrains Fleet"
            val startedAt = properties.getProperty("startedAtEpochSecond")?.toLongOrNull() ?: Instant.now().epochSecond
            val heartbeat = properties.getProperty("lastHeartbeatEpochSecond")?.toLongOrNull() ?: startedAt

            return SessionStatus(
                sessionId = sessionId,
                pid = pid,
                details = details,
                projectName = properties.getProperty("projectName")?.takeIf(String::isNotBlank),
                fileName = properties.getProperty("fileName")?.takeIf(String::isNotBlank),
                language = properties.getProperty("language")?.takeIf(String::isNotBlank),
                startedAtEpochSecond = startedAt,
                lastHeartbeatEpochSecond = heartbeat,
                sourcePath = path,
            )
        }
    }
}
