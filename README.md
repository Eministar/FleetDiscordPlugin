# Fleet Rich Presence for Discord 🚀

> [!NOTE]
> JetBrains has discontinued Fleet, but if you still enjoy using it, this project keeps Discord Rich Presence alive with a local bridge.

A small workaround for a niche problem - and maybe exactly your kind of niche.
If you are still coding in Fleet because it is cool, this is for you. 💙

## ✨ What this does

- Builds and installs a local Fleet plugin + Discord bridge.
- Configures Fleet to load the plugin from this repository.
- Writes user-level `bridge.properties` automatically.
- Starts the bridge through Fleet so your Discord activity updates while you work.

## 🪟 Quick Start (Windows)

Run from the project root:

```powershell
.\Setup.ps1 -ClientId 1489614423684550716 -LaunchFleet
```

If Fleet is open and should be closed automatically:

```powershell
.\Setup.ps1 -ClientId 1489614423684550716 -ForceCloseFleet -LaunchFleet
```

## 🧠 What `Setup.ps1` handles for you

`Setup.ps1` is an all-in-one installer for this repo. It will:

1. Validate your Discord Client ID.
2. Detect your Fleet installation (`Fleet.cfg`) automatically (or use `-FleetConfigPath`).
3. Build required Gradle modules (unless `-SkipBuild` is used).
4. Generate and write `~/.fleet-rich-presence/bridge.properties`.
5. Patch Fleet config with a custom plugin configuration path.
6. Create backups before changing files.

## 🎮 Client ID and assets

By default, examples use this Client ID:

```text
1489614423684550716
```

- You can use this ID if you want to reuse the prepared assets.
- If you want your own branding, create a Discord application and assets, then pass your own `-ClientId`.

See `bridge.properties.example` for configurable fields like image keys, labels, polling interval, and debug mode.

## ⚠️ Important notes

- `Setup.ps1` currently supports **Windows only**.
- Close Fleet before building, or use `-ForceCloseFleet`.
- If auto-detection fails, provide `-FleetConfigPath` manually.
- The installer creates backups in:
  - `~/.fleet-rich-presence/backups/<timestamp>/`

## 🛠️ Troubleshooting

- **Gradle build fails**: run the script again and let it complete dependency downloads.
- **No Rich Presence appears**: verify `clientId` and image keys in your Discord app assets.
- **Wrong Fleet installation patched**: rerun with explicit `-FleetConfigPath`.

## ❤️ Why this exists

No big promises. No huge productivity boost.
Just a fun way to keep Fleet + Discord presence working for people who still like Fleet.

Have fun with it. 🙌

