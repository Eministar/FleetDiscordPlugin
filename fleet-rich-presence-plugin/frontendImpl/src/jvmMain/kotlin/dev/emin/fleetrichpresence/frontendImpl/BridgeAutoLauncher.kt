package dev.emin.fleetrichpresence.frontendImpl

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

internal object BridgeAutoLauncher {
    private val logger = System.getLogger(BridgeAutoLauncher::class.java.name)
    private val started = AtomicBoolean(false)
    private val bridgeDirectory = Path.of(System.getProperty("user.home"), ".fleet-rich-presence")
    private val bridgePropertiesPath = bridgeDirectory.resolve("bridge.properties")
    private val logsDirectory = bridgeDirectory.resolve("logs")
    private val bridgeLogPath = logsDirectory.resolve("discord-bridge.log")

    fun startIfConfigured() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val properties = loadProperties()
        if (!isEnabled(properties)) {
            logger.log(System.Logger.Level.INFO, "Fleet Rich Presence bridge autostart is disabled.")
            return
        }

        val clientId = resolveClientId(properties)
        if (clientId == null) {
            logger.log(
                System.Logger.Level.INFO,
                "Fleet Rich Presence bridge autostart skipped because no client ID is configured.",
            )
            return
        }

        val launcherPath = resolveLauncherPath(properties)
        if (launcherPath == null) {
            logger.log(
                System.Logger.Level.WARNING,
                "Fleet Rich Presence bridge autostart skipped because no launcher was found.",
            )
            return
        }

        try {
            Files.createDirectories(logsDirectory)
            val process = ProcessBuilder(buildCommand(launcherPath, clientId, properties))
                .directory(launcherPath.parent.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(bridgeLogPath.toFile()))
                .redirectErrorStream(true)
                .start()

            logger.log(
                System.Logger.Level.INFO,
                "Started Fleet Rich Presence bridge process ${process.pid()} via $launcherPath.",
            )
        } catch (error: IOException) {
            logger.log(System.Logger.Level.WARNING, "Failed to auto-start Fleet Rich Presence bridge.", error)
        }
    }

    private fun loadProperties(): Properties {
        val properties = Properties()
        if (!Files.isRegularFile(bridgePropertiesPath)) {
            return properties
        }

        return try {
            Files.newBufferedReader(bridgePropertiesPath, StandardCharsets.UTF_8).use(properties::load)
            properties
        } catch (error: IOException) {
            logger.log(System.Logger.Level.WARNING, "Failed to read Fleet Rich Presence bridge config.", error)
            Properties()
        }
    }

    private fun isEnabled(properties: Properties): Boolean =
        properties.getProperty("enabled")?.trim()?.lowercase() != "false"

    private fun resolveClientId(properties: Properties): String? =
        properties.getProperty("clientId")?.trim()?.takeIf(String::isNotBlank)
            ?: System.getenv("DISCORD_CLIENT_ID")?.trim()?.takeIf(String::isNotBlank)

    private fun resolveLauncherPath(properties: Properties): Path? {
        val configured = properties.getProperty("launcherPath")?.trim()?.takeIf(String::isNotBlank)?.let(Path::of)
        if (configured != null && Files.isRegularFile(configured)) {
            return configured
        }

        val resolvedPluginsConfiguration = System.getProperty("fleet.custom.resolved-plugins-configuration.path")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: return null

        val projectRoot = resolvedPluginsConfiguration.parent?.parent?.parent ?: return null
        val candidates = listOf(
            projectRoot.resolve("discord-bridge").resolve("build").resolve("install").resolve("discord-bridge").resolve("bin").resolve("discord-bridge.bat"),
            projectRoot.resolve("discord-bridge").resolve("build").resolve("install").resolve("discord-bridge").resolve("bin").resolve("discord-bridge"),
            projectRoot.resolve("discord-bridge").resolve("build").resolve("scripts").resolve("discord-bridge.bat"),
            projectRoot.resolve("discord-bridge").resolve("build").resolve("scripts").resolve("discord-bridge"),
        )
        return candidates.firstOrNull(Files::isRegularFile)
    }

    private fun buildCommand(launcherPath: Path, clientId: String, properties: Properties): List<String> {
        val command = mutableListOf<String>()
        if (launcherPath.toString().endsWith(".bat", ignoreCase = true) ||
            launcherPath.toString().endsWith(".cmd", ignoreCase = true)
        ) {
            command += listOf("cmd.exe", "/c", launcherPath.toString())
        } else {
            command += launcherPath.toString()
        }

        command += listOf("--client-id", clientId)
        appendOption(command, properties, "pollIntervalSeconds", "--poll-interval-seconds")
        appendOption(command, properties, "largeImageKey", "--large-image-key")
        appendOption(command, properties, "largeImageText", "--large-image-text")
        appendOption(command, properties, "smallImageKey", "--small-image-key")
        appendOption(command, properties, "smallImageText", "--small-image-text")
        appendOption(command, properties, "debug", "--debug")
        return command
    }

    private fun appendOption(
        command: MutableList<String>,
        properties: Properties,
        propertyName: String,
        commandLineName: String,
    ) {
        val value = properties.getProperty(propertyName)?.trim()?.takeIf(String::isNotBlank) ?: return
        command += listOf(commandLineName, value)
    }
}
