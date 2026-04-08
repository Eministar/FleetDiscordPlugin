package dev.emin.fleetrichpresence.frontendImpl

import fleet.kernel.plugins.ContributionScope
import fleet.kernel.plugins.Plugin
import fleet.kernel.plugins.PluginScope
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FleetRichPresencePlugin : Plugin<Unit> {
    companion object : Plugin.Key<Unit> {
        private val loaded = AtomicBoolean(false)
    }

    override val key: Plugin.Key<Unit> = FleetRichPresencePlugin

    override fun ContributionScope.load(pluginScope: PluginScope) {
        if (loaded.compareAndSet(false, true)) {
            BridgeAutoLauncher.startIfConfigured()
            SessionReporter.start()
        }
    }
}

private object SessionReporter {
    private val logger = System.getLogger(SessionReporter::class.java.name)
    private val started = AtomicBoolean(false)
    private val startedAtEpochSecond = Instant.now().epochSecond
    private val sessionId = UUID.randomUUID().toString()
    private val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)
    private val sessionsDirectory = Path.of(
        System.getProperty("user.home"),
        ".fleet-rich-presence",
        "sessions",
    )
    private val sessionFile = sessionsDirectory.resolve("fleet-$pid-$sessionId.properties")
    private val scheduler = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "fleet-rich-presence-heartbeat").apply {
                isDaemon = true
            }
        },
    )

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        try {
            Files.createDirectories(sessionsDirectory)
            writeStatus()
            scheduler.scheduleAtFixedRate(::safeWriteStatus, 15, 15, TimeUnit.SECONDS)
            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        scheduler.shutdownNow()
                        runCatching { Files.deleteIfExists(sessionFile) }
                            .onFailure { error ->
                                logger.log(System.Logger.Level.WARNING, "Failed to delete Fleet Rich Presence session file.", error)
                            }
                    },
                    "fleet-rich-presence-shutdown",
                ),
            )
        } catch (error: IOException) {
            logger.log(System.Logger.Level.ERROR, "Failed to initialize Fleet Rich Presence session reporting.", error)
        }
    }

    private fun safeWriteStatus() {
        runCatching(::writeStatus).onFailure { error ->
            logger.log(System.Logger.Level.WARNING, "Failed to refresh Fleet Rich Presence heartbeat.", error)
        }
    }

    @Throws(IOException::class)
    private fun writeStatus() {
        val now = Instant.now().epochSecond
        val contents = buildString {
            appendLine("schemaVersion=1")
            appendLine("source=fleet-plugin")
            appendLine("pluginId=dev.emin.fleet.richpresence")
            appendLine("sessionId=$sessionId")
            appendLine("pid=$pid")
            appendLine("details=Coding in JetBrains Fleet")
            appendLine("projectName=")
            appendLine("fileName=")
            appendLine("language=")
            appendLine("startedAtEpochSecond=$startedAtEpochSecond")
            appendLine("lastHeartbeatEpochSecond=$now")
            appendLine("contextMode=static-fallback")
        }

        Files.writeString(
            sessionFile,
            contents,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}
