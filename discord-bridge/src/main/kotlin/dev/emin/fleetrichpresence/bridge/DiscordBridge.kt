package dev.emin.fleetrichpresence.bridge

import com.jagrosh.discordipc.IPCClient
import com.jagrosh.discordipc.IPCListener
import com.jagrosh.discordipc.entities.ActivityType
import com.jagrosh.discordipc.entities.Packet
import com.jagrosh.discordipc.entities.RichPresence
import com.jagrosh.discordipc.entities.User
import com.jagrosh.discordipc.exceptions.NoDiscordClientException
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors

class DiscordBridge(private val config: BridgeConfig) {
    private val logger = System.getLogger(DiscordBridge::class.java.name)
    private val staleAfter = Duration.ofSeconds(45)
    private val running = AtomicBoolean(true)
    private val windowContextProvider = WindowContextProvider.forCurrentPlatform()

    private var client: IPCClient? = null
    private var lastFingerprint: String? = null
    private var discordUnavailableLogged = false
    private var lastWindowContext: WindowContext? = null

    fun run() {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    running.set(false)
                    disconnect()
                },
                "discord-bridge-shutdown",
            ),
        )

        logger.log(System.Logger.Level.INFO, "Watching Fleet session files in ${config.sessionsDirectory}.")

        while (running.get()) {
            try {
                val activeSession = loadActiveSession()
                if (activeSession == null) {
                    clearPresenceIfNeeded()
                } else {
                    publish(activeSession)
                }
            } catch (error: Exception) {
                logger.log(System.Logger.Level.WARNING, "Bridge loop failed; retrying on next poll.", error)
                disconnect()
            }

            Thread.sleep(config.pollInterval.toMillis())
        }
    }

    private fun publish(session: SessionStatus) {
        ensureConnected()
        val currentClient = client ?: return
        val windowContext = currentWindowContext(session)
        val fileName = windowContext?.fileName ?: session.fileName
        val projectName = windowContext?.projectName ?: session.projectName
        val details = session.details.ifBlank { "Coding in JetBrains Fleet" }
        val state = buildState(projectName, fileName, session.language)
        val largeImageAsset = FileTypeAssets.resolve(fileName)
            ?: config.defaultLargeImageKey?.let { key ->
                LargeImageAsset(key = key, text = config.defaultLargeImageText ?: "Coding in JetBrains Fleet")
            }
        val fingerprint = listOf(
            session.fingerprint(),
            details,
            state.orEmpty(),
            windowContext?.rawTitle.orEmpty(),
            largeImageAsset?.key.orEmpty(),
        ).joinToString("|")
        if (fingerprint == lastFingerprint) {
            return
        }

        val presenceBuilder = RichPresence.Builder()
            .setActivityType(ActivityType.Playing)
            .setDetails(details)
            .setStartTimestamp(session.startedAtEpochSecond)

        state?.let(presenceBuilder::setState)

        if (largeImageAsset != null) {
            presenceBuilder.setLargeImage(largeImageAsset.key, largeImageAsset.text)
        }
        if (!config.smallImageKey.isNullOrBlank()) {
            presenceBuilder.setSmallImage(config.smallImageKey, config.smallImageText)
        }

        currentClient.sendRichPresence(presenceBuilder.build())
        lastFingerprint = fingerprint
        logger.log(System.Logger.Level.INFO, "Updated Discord Rich Presence from ${session.sourcePath.fileName}.")
    }

    private fun buildState(projectName: String?, fileName: String?, language: String?): String {
        val parts = buildList {
            projectName?.takeIf(String::isNotBlank)?.let { add(it) }
            fileName?.takeIf(String::isNotBlank)?.let { add(it) }
        }
        val rawState = when {
            parts.isNotEmpty() -> parts.joinToString(" | ")
            !language.isNullOrBlank() -> language
            else -> "Fleet session"
        }
        return truncateDiscordField(rawState)
    }

    private fun truncateDiscordField(value: String, maxLength: Int = 128): String =
        if (value.length <= maxLength) value else value.take(maxLength - 3) + "..."

    private fun ensureConnected() {
        val currentClient = client
        if (currentClient != null) {
            return
        }

        try {
            val newClient = IPCClient(config.clientId, config.debug)
            newClient.setListener(LoggingListener(logger))
            newClient.connect()
            client = newClient
            discordUnavailableLogged = false
            logger.log(System.Logger.Level.INFO, "Connected to local Discord IPC.")
        } catch (error: NoDiscordClientException) {
            if (!discordUnavailableLogged) {
                logger.log(
                    System.Logger.Level.WARNING,
                    "Discord desktop client is not available. The bridge will keep retrying.",
                    error,
                )
                discordUnavailableLogged = true
            }
        }
    }

    private fun clearPresenceIfNeeded() {
        if (client == null) {
            return
        }

        logger.log(System.Logger.Level.INFO, "No active Fleet session found; closing Discord IPC connection.")
        disconnect()
    }

    private fun disconnect() {
        runCatching { client?.close() }
            .onFailure { error ->
                logger.log(System.Logger.Level.DEBUG, "Ignoring Discord IPC close failure.", error)
            }
        client = null
        lastFingerprint = null
    }

    private fun currentWindowContext(session: SessionStatus): WindowContext? {
        val captured = windowContextProvider.capture(session.pid, logger)
        if (captured != null) {
            lastWindowContext = captured
            return captured
        }

        val retained = lastWindowContext
        return if (retained != null && retained.pid == session.pid) retained else null
    }

    private fun loadActiveSession(): SessionStatus? {
        if (!Files.isDirectory(config.sessionsDirectory)) {
            return null
        }

        val now = Instant.now()
        Files.list(config.sessionsDirectory).use { paths ->
            val sessions = paths
                .filter(Files::isRegularFile)
                .map(::loadSafely)
                .filter { session -> session != null && session.isAlive(now, staleAfter) }
                .map { it!! }
                .collect(Collectors.toList())

            return sessions.maxWithOrNull(
                compareBy<SessionStatus>({ it.lastHeartbeatEpochSecond }, { it.startedAtEpochSecond }),
            )
        }
    }

    private fun loadSafely(path: java.nio.file.Path): SessionStatus? =
        try {
            SessionStatus.fromFile(path)
        } catch (error: IOException) {
            logger.log(System.Logger.Level.WARNING, "Failed to read session file $path.", error)
            null
        }

    private class LoggingListener(private val logger: System.Logger) : IPCListener {
        override fun onPacketSent(client: IPCClient, packet: Packet) = Unit

        override fun onPacketReceived(client: IPCClient, packet: Packet) = Unit

        override fun onActivityJoin(client: IPCClient, secret: String) = Unit

        override fun onActivitySpectate(client: IPCClient, secret: String) = Unit

        override fun onActivityJoinRequest(client: IPCClient, secret: String, user: User) = Unit

        override fun onReady(client: IPCClient) {
            logger.log(System.Logger.Level.DEBUG, "Discord IPC listener signalled ready.")
        }

        override fun onClose(client: IPCClient, json: com.google.gson.JsonObject?) {
            logger.log(System.Logger.Level.DEBUG, "Discord IPC listener signalled close: ${json ?: "<no payload>"}")
        }

        override fun onDisconnect(client: IPCClient, t: Throwable?) {
            if (t == null) {
                logger.log(System.Logger.Level.WARNING, "Discord IPC listener signalled disconnect without a cause.")
            } else {
                logger.log(System.Logger.Level.WARNING, "Discord IPC listener signalled disconnect.", t)
            }
        }
    }
}
