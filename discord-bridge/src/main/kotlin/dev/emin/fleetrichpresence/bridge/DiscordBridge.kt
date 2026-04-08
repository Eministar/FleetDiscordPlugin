package dev.emin.fleetrichpresence.bridge

import com.google.gson.JsonNull
import com.google.gson.JsonObject
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
    private val retainedWindowContextTtl = Duration.ofSeconds(2)
    private val running = AtomicBoolean(true)
    private val windowContextProvider = WindowContextProvider.forCurrentPlatform()

    private var client: IPCClient? = null
    private var lastFingerprint: String? = null
    private var discordUnavailableLogged = false
    private val lastWindowContexts = mutableMapOf<String, WindowContext>()

    private data class ActiveSessionSnapshot(
        val session: SessionStatus,
        val windowContext: WindowContext?,
    )

    fun run() {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    running.set(false)
                    disconnect(clearActivity = true)
                },
                "discord-bridge-shutdown",
            ),
        )

        logger.log(System.Logger.Level.INFO, "Watching Fleet session files in ${config.sessionsDirectory}.")

        while (running.get()) {
            try {
                val activeSnapshot = loadActiveSnapshot()
                if (activeSnapshot == null) {
                    clearPresenceIfNeeded()
                } else {
                    publish(activeSnapshot)
                }
            } catch (error: Exception) {
                logger.log(System.Logger.Level.WARNING, "Bridge loop failed; retrying on next poll.", error)
                disconnect()
            }

            Thread.sleep(config.pollInterval.toMillis())
        }
    }

    private fun publish(snapshot: ActiveSessionSnapshot) {
        ensureConnected()
        val currentClient = client ?: return
        val session = snapshot.session
        val windowContext = snapshot.windowContext
        val fileName = windowContext?.fileName ?: session.fileName
        val projectName = windowContext?.projectName ?: session.projectName
        val language = windowContext?.language ?: session.language ?: FileTypeAssets.languageName(fileName)
        val details = buildDetails(session.details, projectName, fileName, language)
        val state = buildState(projectName, language)
        val largeImageAsset = FileTypeAssets.resolve(fileName)
            ?: config.defaultLargeImageKey?.let { key ->
                LargeImageAsset(key = key, text = config.defaultLargeImageText ?: "Coding in JetBrains Fleet")
            }
        val fingerprint = listOf(
            session.sessionId,
            details,
            state.orEmpty(),
            fileName.orEmpty(),
            projectName.orEmpty(),
            language.orEmpty(),
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

    private fun buildDetails(defaultDetails: String, projectName: String?, fileName: String?, language: String?): String {
        val rawDetails = when {
            !fileName.isNullOrBlank() -> "Editing $fileName"
            !projectName.isNullOrBlank() -> "Browsing $projectName"
            !language.isNullOrBlank() -> "Working in $language"
            else -> defaultDetails.ifBlank { "Coding in JetBrains Fleet" }
        }
        return truncateDiscordField(rawDetails)
    }

    private fun buildState(projectName: String?, language: String?): String? {
        val parts = buildList {
            projectName?.takeIf(String::isNotBlank)?.let { add(it) }
            language?.takeIf(String::isNotBlank)?.let { add(it) }
        }
        return if (parts.isEmpty()) {
            null
        } else {
            truncateDiscordField(parts.joinToString(" | "))
        }
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
        disconnect(clearActivity = true)
    }

    private fun disconnect(clearActivity: Boolean = false) {
        val currentClient = client
        if (currentClient != null && clearActivity) {
            clearDiscordActivity(currentClient)
        }

        runCatching { currentClient?.close() }
            .onFailure { error ->
                logger.log(System.Logger.Level.DEBUG, "Ignoring Discord IPC close failure.", error)
            }
        client = null
        lastFingerprint = null
        if (clearActivity) {
            lastWindowContexts.clear()
        }
    }

    private fun clearDiscordActivity(currentClient: IPCClient) {
        runCatching {
            val pipeField = IPCClient::class.java.getDeclaredField("pipe").apply { isAccessible = true }
            val pipe = pipeField.get(currentClient) ?: return
            val args = JsonObject().apply {
                addProperty("pid", ProcessHandle.current().pid())
                add("activity", JsonNull.INSTANCE)
            }
            val payload = JsonObject().apply {
                addProperty("cmd", "SET_ACTIVITY")
                add("args", args)
            }
            pipe.javaClass
                .getMethod("send", Packet.OpCode::class.java, JsonObject::class.java)
                .invoke(pipe, Packet.OpCode.FRAME, payload)
        }.onFailure { error ->
            logger.log(System.Logger.Level.DEBUG, "Failed to clear Discord activity explicitly.", error)
        }
    }

    private fun currentWindowContext(session: SessionStatus, hintedWindowContext: WindowContext? = null): WindowContext? {
        hintedWindowContext
            ?.takeIf { canUseWindowContext(session, it) }
            ?.also {
                lastWindowContexts[session.sessionId] = it
                return it
            }

        val captured = windowContextProvider.capture(session.pid, logger)
        if (captured != null) {
            lastWindowContexts[session.sessionId] = captured
            return captured
        }

        val compatibleVisibleWindow = windowContextProvider.capture(0L, logger)
        if (compatibleVisibleWindow != null && canUseWindowContext(session, compatibleVisibleWindow)) {
            lastWindowContexts[session.sessionId] = compatibleVisibleWindow
            return compatibleVisibleWindow
        }

        val retained = lastWindowContexts[session.sessionId]
        return retained?.takeIf { canUseWindowContext(session, it) && it.isFresh(Instant.now(), retainedWindowContextTtl) }
    }

    private fun canUseWindowContext(session: SessionStatus, windowContext: WindowContext): Boolean {
        if (session.pid <= 0L) {
            return true
        }

        return session.pid == windowContext.pid || isAncestorOrDescendant(session.pid, windowContext.pid)
    }

    private fun isAncestorOrDescendant(firstPid: Long, secondPid: Long): Boolean {
        if (firstPid <= 0L || secondPid <= 0L) {
            return false
        }

        if (firstPid == secondPid) {
            return true
        }

        return lineageContains(firstPid, secondPid) || lineageContains(secondPid, firstPid)
    }

    private fun lineageContains(originPid: Long, targetPid: Long): Boolean {
        var current = ProcessHandle.of(originPid).orElse(null)
        repeat(8) {
            current = current?.parent()?.orElse(null)
            val currentPid = current?.pid() ?: return false
            if (currentPid == targetPid) {
                return true
            }
        }
        return false
    }

    private fun loadActiveSnapshot(): ActiveSessionSnapshot? {
        val sessions = loadActiveSessions()
        if (sessions.isEmpty()) {
            lastWindowContexts.clear()
            return null
        }

        val activeSessionIds = sessions.mapTo(mutableSetOf(), SessionStatus::sessionId)
        lastWindowContexts.keys.retainAll(activeSessionIds)

        val fallbackSession = sessions.maxWithOrNull(
            compareBy<SessionStatus>({ it.lastHeartbeatEpochSecond }, { it.startedAtEpochSecond }),
        ) ?: return null

        val foregroundWindowContext = windowContextProvider.capture(0L, logger)
        if (foregroundWindowContext != null) {
            if (sessions.size == 1) {
                return ActiveSessionSnapshot(fallbackSession, foregroundWindowContext)
            }

            sessions.firstOrNull { canUseWindowContext(it, foregroundWindowContext) }?.let { matchedSession ->
                return ActiveSessionSnapshot(matchedSession, foregroundWindowContext)
            }
        }

        return ActiveSessionSnapshot(fallbackSession, currentWindowContext(fallbackSession))
    }

    private fun loadActiveSessions(): List<SessionStatus> {
        if (!Files.isDirectory(config.sessionsDirectory)) {
            return emptyList()
        }

        val now = Instant.now()
        Files.list(config.sessionsDirectory).use { paths ->
            return paths
                .filter(Files::isRegularFile)
                .map(::loadSafely)
                .filter { session -> session != null && session.isAlive(now, staleAfter) }
                .map { it!! }
                .collect(Collectors.toList())
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
