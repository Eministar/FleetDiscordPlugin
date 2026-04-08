package dev.emin.fleetrichpresence.bridge

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

data class WindowContext(
    val pid: Long,
    val rawTitle: String,
    val fileName: String?,
    val projectName: String?,
    val language: String?,
    val capturedAtEpochSecond: Long,
) {
    fun isFresh(now: Instant, ttl: Duration): Boolean =
        capturedAtEpochSecond >= now.minus(ttl).epochSecond

    companion object {
        fun fromTitle(pid: Long, title: String): WindowContext {
            val sanitizedTitle = title.trim()
            val parts = sanitizedTitle
                .split(" — ", " – ", " - ", " · ", " | ")
                .map(::cleanToken)
                .filter(String::isNotBlank)
                .filterNot(::isFleetLabel)

            val fileName = parts.firstOrNull()
                ?.takeIf(::looksLikeFileToken)
                ?.let(::extractFileName)

            val projectName = when {
                parts.size >= 2 -> parts.lastOrNull()?.takeIf { !looksLikeFileToken(it) }
                parts.size == 1 -> parts.single().takeIf { !looksLikeFileToken(it) }
                else -> null
            }

            return WindowContext(
                pid = pid,
                rawTitle = sanitizedTitle,
                fileName = fileName,
                projectName = projectName,
                language = FileTypeAssets.languageName(fileName),
                capturedAtEpochSecond = Instant.now().epochSecond,
            )
        }

        private fun cleanToken(token: String): String =
            token
                .trim()
                .removePrefix("● ")
                .removePrefix("• ")
                .removePrefix("* ")
                .removeSuffix(" *")
                .removeSuffix("*")
                .trim()

        private fun isFleetLabel(token: String): Boolean =
            token.equals("fleet", ignoreCase = true) ||
                token.equals("jetbrains fleet", ignoreCase = true)

        private fun looksLikeFileToken(token: String): Boolean {
            if (token.equals("dockerfile", ignoreCase = true) || token.startsWith('.')) {
                return true
            }

            if (token.contains('\\') || token.contains('/')) {
                return true
            }

            val extension = token.substringAfterLast('.', "")
            return extension.isNotBlank() && extension.length in 1..12 && !token.endsWith('.')
        }

        private fun extractFileName(token: String): String {
            val normalized = token.replace('\\', '/')
            return Path.of(normalized.substringAfterLast('/')).fileName.toString()
        }
    }
}
