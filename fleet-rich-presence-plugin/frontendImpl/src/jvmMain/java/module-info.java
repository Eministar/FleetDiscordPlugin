module dev.emin.fleetrichpresence.frontendImpl {
    requires fleet.kernel.plugins;

    exports dev.emin.fleetrichpresence.frontendImpl;
    provides fleet.kernel.plugins.Plugin with dev.emin.fleetrichpresence.frontendImpl.FleetRichPresencePlugin;
}
