package dev.emin.fleetrichpresence.bridge

fun main(args: Array<String>) {
    val config = try {
        BridgeConfig.parse(args)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        System.err.println()
        System.err.println(BridgeConfig.usage())
        return
    }

    val logger = System.getLogger("dev.emin.fleetrichpresence.bridge.Main")
    val lockFile = config.sessionsDirectory.parent.resolve("bridge").resolve("discord-bridge.lock")
    val guard = SingleInstanceGuard.acquire(lockFile, logger) ?: return
    Runtime.getRuntime().addShutdownHook(Thread({ guard.close() }, "discord-bridge-single-instance-shutdown"))

    DiscordBridge(config).run()
}
