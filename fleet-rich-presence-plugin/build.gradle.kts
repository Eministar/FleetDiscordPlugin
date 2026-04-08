plugins {
    base
    alias(libs.plugins.fleet.plugin)
}

version = "0.1.0"

fleetPlugin {
    id = "dev.emin.fleet.richpresence"

    metadata {
        readableName = "Fleet Rich Presence"
        description = "Publishes a minimal Fleet session feed for a local Discord Rich Presence bridge."
    }

    fleetRuntime {
        version = libs.versions.fleet.runtime
    }
}
