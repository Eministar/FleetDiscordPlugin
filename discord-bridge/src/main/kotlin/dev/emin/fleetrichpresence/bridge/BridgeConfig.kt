package dev.emin.fleetrichpresence.bridge

import java.nio.file.Path
import java.time.Duration

data class BridgeConfig(
    val clientId: Long,
    val sessionsDirectory: Path,
    val pollInterval: Duration,
    val defaultLargeImageKey: String?,
    val defaultLargeImageText: String?,
    val smallImageKey: String?,
    val smallImageText: String?,
    val debug: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): BridgeConfig {
            val rawArgs = args.toList()
            val options = mutableMapOf<String, String>()
            var index = 0
            while (index < rawArgs.size) {
                val key = rawArgs[index]
                require(key.startsWith("--")) { "Unexpected argument: $key" }
                require(index + 1 < rawArgs.size) { "Missing value for $key" }
                options[key] = rawArgs[index + 1]
                index += 2
            }

            val clientId = options["--client-id"]?.toLongOrNull()
                ?: System.getenv("DISCORD_CLIENT_ID")?.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "Missing Discord client ID. Pass --client-id <id> or set DISCORD_CLIENT_ID.",
                )

            val sessionsDirectory = options["--sessions-dir"]?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".fleet-rich-presence", "sessions")

            val pollInterval = when {
                options["--poll-interval-millis"] != null -> {
                    val millis = options["--poll-interval-millis"]?.toLongOrNull()
                        ?: throw IllegalArgumentException("--poll-interval-millis must be a whole number.")
                    require(millis > 0) { "--poll-interval-millis must be greater than zero." }
                    Duration.ofMillis(millis)
                }

                else -> {
                    val seconds = options["--poll-interval-seconds"]?.toLongOrNull() ?: 1L
                    require(seconds > 0) { "--poll-interval-seconds must be greater than zero." }
                    Duration.ofSeconds(seconds)
                }
            }

            val debug = options["--debug"]?.toBooleanStrictOrNull() ?: false

            return BridgeConfig(
                clientId = clientId,
                sessionsDirectory = sessionsDirectory,
                pollInterval = pollInterval,
                defaultLargeImageKey = options["--large-image-key"]?.takeIf(String::isNotBlank) ?: "coding",
                defaultLargeImageText = options["--large-image-text"]?.takeIf(String::isNotBlank) ?: "Coding in JetBrains Fleet",
                smallImageKey = options["--small-image-key"]?.takeIf(String::isNotBlank) ?: "fleet",
                smallImageText = options["--small-image-text"]?.takeIf(String::isNotBlank) ?: "JetBrains Fleet",
                debug = debug,
            )
        }

        fun usage(): String = """
            Usage:
              discord-bridge --client-id <discord_app_id> [--sessions-dir <path>] [--poll-interval-millis <n> | --poll-interval-seconds <n>] [--large-image-key <key>] [--large-image-text <text>] [--small-image-key <key>] [--small-image-text <text>] [--debug true|false]

            Environment fallback:
              DISCORD_CLIENT_ID=<discord_app_id>
        """.trimIndent()
    }
}
