package dev.emin.fleetrichpresence.bridge

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal class SingleInstanceGuard private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(lockFile: Path, logger: System.Logger): SingleInstanceGuard? {
            return try {
                Files.createDirectories(lockFile.parent)
                val channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                val lock = channel.tryLock()
                if (lock == null) {
                    channel.close()
                    logger.log(System.Logger.Level.INFO, "Fleet Rich Presence bridge is already running.")
                    null
                } else {
                    val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)
                    channel.truncate(0)
                    channel.write(StandardCharsets.UTF_8.encode("pid=$pid${System.lineSeparator()}startedAt=${Instant.now()}"))
                    SingleInstanceGuard(channel, lock)
                }
            } catch (error: IOException) {
                logger.log(System.Logger.Level.WARNING, "Failed to acquire Fleet Rich Presence bridge lock.", error)
                null
            }
        }
    }
}
