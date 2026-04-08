<#
.SYNOPSIS
Installs the Fleet Rich Presence plugin and local Discord bridge on Windows.

.DESCRIPTION
This script builds the Fleet plugin and the local Discord bridge, writes the
user-level bridge.properties file, detects the local Fleet installation, and
patches Fleet.cfg so Fleet loads the plugin from this repository.
If the project is a Git checkout, the script also tries to pull the latest
changes before building so re-running Setup.ps1 can act as an update.

.PARAMETER ClientId
Your Discord application client ID. If omitted, the script prompts for it.

.PARAMETER FleetConfigPath
Optional explicit path to Fleet.cfg. If omitted, the script searches common
Fleet installation locations automatically.

.PARAMETER ForceCloseFleet
Closes running Fleet processes automatically so the plugin JAR can be rebuilt.

.PARAMETER LaunchFleet
Launches Fleet after the setup completed successfully.

.PARAMETER SkipBuild
Skips the Gradle build step. Useful only if the repository was already built.

.PARAMETER SkipRepositoryUpdate
Skips the automatic Git update step and uses the current local repository state.

.EXAMPLE
.\Setup.ps1 -ClientId 1489614423684550716 -LaunchFleet

.EXAMPLE
.\Setup.ps1 -ClientId 1489614423684550716 -ForceCloseFleet

.EXAMPLE
.\Setup.ps1 -ClientId 1489614423684550716 -SkipRepositoryUpdate
#>
[CmdletBinding()]
param(
    [string]$ClientId,
    [string]$FleetConfigPath,
    [switch]$ForceCloseFleet,
    [switch]$LaunchFleet,
    [switch]$SkipBuild,
    [switch]$SkipRepositoryUpdate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Banner {
    param([string]$Title)

    $line = "=" * 72
    Write-Host $line -ForegroundColor Cyan
    Write-Host ("  {0}" -f $Title) -ForegroundColor Cyan
    Write-Host $line -ForegroundColor Cyan
}

function Write-Step {
    param([string]$Message)

    Write-Host ("`n[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message) -ForegroundColor White
}

function Write-InfoLine {
    param([string]$Message)

    Write-Host ("  - {0}" -f $Message) -ForegroundColor DarkGray
}

function Write-Ok {
    param([string]$Message)

    Write-Host ("  + {0}" -f $Message) -ForegroundColor Green
}

function Write-WarnLine {
    param([string]$Message)

    Write-Host ("  ! {0}" -f $Message) -ForegroundColor Yellow
}

function Fail {
    param([string]$Message)

    throw $Message
}

function Convert-ToForwardSlashPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return ([IO.Path]::GetFullPath($Path) -replace "\\", "/")
}

function Get-Newline {
    param([string]$Content)

    if ($Content -match "`r`n") {
        return "`r`n"
    }

    return "`n"
}

function Test-IsWindowsPlatform {
    return $env:OS -eq "Windows_NT"
}

function Resolve-ClientId {
    param([string]$InitialValue)

    $value = $InitialValue
    while ([string]::IsNullOrWhiteSpace($value)) {
        $value = Read-Host "Enter your Discord application client ID"
    }

    $trimmed = $value.Trim()
    if ($trimmed -notmatch "^\d+$") {
        Fail "The Discord client ID must contain digits only."
    }

    return $trimmed
}

function Join-OptionalPath {
    param(
        [string]$BasePath,
        [string]$ChildPath
    )

    if ([string]::IsNullOrWhiteSpace($BasePath)) {
        return $null
    }

    return Join-Path $BasePath $ChildPath
}

function Get-PropertyValue {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$PropertyName
    )

    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [switch]$IgnoreExitCode
    )

    Push-Location $WorkingDirectory
    try {
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if (-not $IgnoreExitCode -and $exitCode -ne 0) {
        $joinedArgs = $Arguments -join " "
        $message = @(
            "Command failed: $FilePath $joinedArgs"
            $output
        ) -join [Environment]::NewLine
        Fail $message
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output)
    }
}

function Find-GitExecutable {
    $command = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $command = Get-Command git -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    return $null
}

function Update-RepositoryIfPossible {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [switch]$Skip
    )

    if ($Skip) {
        Write-WarnLine "Skipping repository update because -SkipRepositoryUpdate was specified."
        return
    }

    $gitDirectory = Join-Path $ProjectRoot ".git"
    if (-not (Test-Path -LiteralPath $gitDirectory)) {
        Write-WarnLine "No .git directory found. Setup will use the current local files without pulling updates."
        return
    }

    $git = Find-GitExecutable
    if ([string]::IsNullOrWhiteSpace($git)) {
        Write-WarnLine "Git is not available in PATH. Setup will use the current local files without pulling updates."
        return
    }

    Write-InfoLine "Checking repository state for updates."

    $remoteResult = Invoke-NativeCommand -FilePath $git -Arguments @("remote") -WorkingDirectory $ProjectRoot -IgnoreExitCode
    if ($remoteResult.ExitCode -ne 0 -or -not ($remoteResult.Output -contains "origin")) {
        Write-WarnLine "No Git remote named 'origin' was found. Setup will use the current local files."
        return
    }

    $remoteHeadsResult = Invoke-NativeCommand `
        -FilePath $git `
        -Arguments @("ls-remote", "--heads", "origin") `
        -WorkingDirectory $ProjectRoot `
        -IgnoreExitCode

    if ($remoteHeadsResult.ExitCode -ne 0) {
        Write-WarnLine "The remote repository could not be inspected. Setup will use the current local files."
        return
    }

    if ($remoteHeadsResult.Output.Count -eq 0 -or [string]::IsNullOrWhiteSpace(($remoteHeadsResult.Output -join "").Trim())) {
        Write-WarnLine "The Git remote does not contain any pushed branches yet. Auto-update will work only after the first push."
        return
    }

    $headResult = Invoke-NativeCommand `
        -FilePath $git `
        -Arguments @("rev-parse", "--verify", "HEAD") `
        -WorkingDirectory $ProjectRoot `
        -IgnoreExitCode

    if ($headResult.ExitCode -ne 0) {
        Write-WarnLine "The local repository has no commit yet. Create and push the first commit before using auto-update."
        return
    }

    $statusResult = Invoke-NativeCommand `
        -FilePath $git `
        -Arguments @("status", "--porcelain", "--untracked-files=no") `
        -WorkingDirectory $ProjectRoot `
        -IgnoreExitCode

    if ($statusResult.ExitCode -ne 0) {
        Write-WarnLine "Git status could not be read. Setup will use the current local files."
        return
    }

    if ($statusResult.Output.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace(($statusResult.Output -join "").Trim())) {
        Write-WarnLine "Tracked local changes were found. Skipping automatic repository update to avoid overwriting work."
        return
    }

    $branchResult = Invoke-NativeCommand `
        -FilePath $git `
        -Arguments @("rev-parse", "--abbrev-ref", "HEAD") `
        -WorkingDirectory $ProjectRoot `
        -IgnoreExitCode

    if ($branchResult.ExitCode -ne 0) {
        Write-WarnLine "Could not determine the current Git branch. Setup will use the current local files."
        return
    }

    $branch = ($branchResult.Output | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($branch) -or $branch -eq "HEAD") {
        Write-WarnLine "Repository is in detached HEAD state. Setup will use the current local files."
        return
    }

    Write-InfoLine ("Pulling the latest changes from origin/{0}." -f $branch)
    $pullResult = Invoke-NativeCommand `
        -FilePath $git `
        -Arguments @("pull", "--ff-only", "origin", $branch) `
        -WorkingDirectory $ProjectRoot `
        -IgnoreExitCode

    if ($pullResult.ExitCode -ne 0) {
        Write-WarnLine "Git pull failed. Setup will continue with the current local files."
        foreach ($line in $pullResult.Output) {
            if (-not [string]::IsNullOrWhiteSpace($line)) {
                Write-InfoLine $line
            }
        }
        return
    }

    $summary = ($pullResult.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
    if ([string]::IsNullOrWhiteSpace($summary)) {
        Write-Ok "Repository is already up to date."
    } else {
        Write-Ok ("Repository update result: {0}" -f $summary)
    }
}

function Get-FleetProcesses {
    return @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -eq "Fleet" })
}

function Ensure-FleetIsClosed {
    param([switch]$ForceClose)

    $fleetProcesses = Get-FleetProcesses
    if (-not $fleetProcesses) {
        Write-Ok "Fleet is not running."
        return
    }

    Write-WarnLine "Fleet is currently running. The plugin JAR cannot be rebuilt while Fleet keeps it locked."

    if ($ForceClose) {
        Write-InfoLine "Closing Fleet automatically because -ForceCloseFleet was specified."
        $fleetProcesses | Stop-Process -Force
    } else {
        $answer = Read-Host "Close Fleet automatically now? [Y/n]"
        if ($answer -match "^(|y|yes)$") {
            $fleetProcesses | Stop-Process -Force
        } else {
            Fail "Please close Fleet and run Setup.ps1 again, or use -ForceCloseFleet."
        }
    }

    Start-Sleep -Seconds 2

    $stillRunning = Get-FleetProcesses
    if ($stillRunning) {
        Fail "Fleet is still running. Close it completely and re-run Setup.ps1."
    }

    Write-Ok "Fleet has been closed."
}

function Get-FleetCandidates {
    $candidates = [System.Collections.Generic.List[pscustomobject]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

    $directPaths = @(
        (Join-OptionalPath -BasePath $env:LOCALAPPDATA -ChildPath "Programs\Fleet\app\Fleet.cfg"),
        (Join-OptionalPath -BasePath $env:ProgramFiles -ChildPath "JetBrains\Fleet\app\Fleet.cfg"),
        (Join-OptionalPath -BasePath ${env:ProgramFiles(x86)} -ChildPath "JetBrains\Fleet\app\Fleet.cfg")
    ) | Where-Object { $_ }

    foreach ($path in $directPaths) {
        if (Test-Path -LiteralPath $path) {
            $fullPath = [IO.Path]::GetFullPath($path)
            if ($seen.Add($fullPath)) {
                $candidates.Add([pscustomobject]@{
                    Path = $fullPath
                    Source = "Common install location"
                })
            }
        }
    }

    $toolboxRoots = @(
        (Join-Path $env:LOCALAPPDATA "JetBrains\Toolbox\apps\Fleet"),
        (Join-Path $env:LOCALAPPDATA "Programs\JetBrains Toolbox\apps\Fleet")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $toolboxRoots) {
        $matches = Get-ChildItem -Path $root -Filter "Fleet.cfg" -File -Recurse -ErrorAction SilentlyContinue
        foreach ($match in $matches) {
            $fullPath = [IO.Path]::GetFullPath($match.FullName)
            if ($seen.Add($fullPath)) {
                $candidates.Add([pscustomobject]@{
                    Path = $fullPath
                    Source = "JetBrains Toolbox"
                })
            }
        }
    }

    $uninstallRoots = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )

    foreach ($root in $uninstallRoots) {
        $entries = Get-ItemProperty -Path $root -ErrorAction SilentlyContinue
        foreach ($entry in $entries) {
            $displayName = Get-PropertyValue -Object $entry -PropertyName "DisplayName"
            $installLocation = Get-PropertyValue -Object $entry -PropertyName "InstallLocation"
            if ([string]::IsNullOrWhiteSpace($displayName) -or [string]::IsNullOrWhiteSpace($installLocation)) {
                continue
            }

            if ($displayName -notlike "*Fleet*") {
                continue
            }

            $candidatePath = Join-Path $installLocation "app\Fleet.cfg"
            if (Test-Path -LiteralPath $candidatePath) {
                $fullPath = [IO.Path]::GetFullPath($candidatePath)
                if ($seen.Add($fullPath)) {
                    $candidates.Add([pscustomobject]@{
                        Path = $fullPath
                        Source = "Windows uninstall registry"
                    })
                }
            }
        }
    }

    return $candidates | Sort-Object Path -Unique
}

function Resolve-FleetConfig {
    param([string]$ExplicitPath)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $fullPath = [IO.Path]::GetFullPath($ExplicitPath)
        if (-not (Test-Path -LiteralPath $fullPath)) {
            Fail "The supplied Fleet config path does not exist: $fullPath"
        }

        return $fullPath
    }

    $candidates = @(Get-FleetCandidates)
    if (-not $candidates) {
        Fail "No Fleet installation was found automatically. Use -FleetConfigPath <path-to-Fleet.cfg>."
    }

    if ($candidates.Count -eq 1) {
        Write-Ok ("Detected Fleet config automatically: {0}" -f $candidates[0].Path)
        return $candidates[0].Path
    }

    Write-WarnLine "Multiple Fleet installations were found."
    for ($index = 0; $index -lt $candidates.Count; $index++) {
        Write-Host ("  [{0}] {1} ({2})" -f ($index + 1), $candidates[$index].Path, $candidates[$index].Source) -ForegroundColor DarkGray
    }

    while ($true) {
        $selection = Read-Host ("Select the Fleet config to patch [1-{0}]" -f $candidates.Count)
        $number = 0
        if ([int]::TryParse($selection, [ref]$number) -and $number -ge 1 -and $number -le $candidates.Count) {
            return $candidates[$number - 1].Path
        }
    }
}

function Resolve-FleetExecutable {
    param([Parameter(Mandatory = $true)][string]$ResolvedFleetConfigPath)

    $configDirectory = Split-Path -Parent $ResolvedFleetConfigPath
    $installRoot = Split-Path -Parent $configDirectory
    $candidates = @(
        (Join-Path $installRoot "Fleet.exe"),
        (Join-Path $configDirectory "bin\fleet.exe")
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }

    return $null
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [switch]$Skip
    )

    if ($Skip) {
        Write-WarnLine "Skipping Gradle build because -SkipBuild was specified."
        return
    }

    $wrapper = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $wrapper)) {
        Fail "Could not find gradlew.bat in $ProjectRoot"
    }

    $tasks = @(
        ":discord-bridge:build",
        ":discord-bridge:installDist",
        ":fleet-rich-presence-plugin:frontendImpl:build",
        ":fleet-rich-presence-plugin:generatePluginDescriptorLocal",
        ":fleet-rich-presence-plugin:generateResolvedPluginConfiguration"
    )

    Write-InfoLine "Running Gradle tasks. This may download dependencies on the first run."
    & $wrapper @tasks
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle setup failed."
    }
}

function Backup-File {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$BackupDirectory
    )

    if (-not (Test-Path -LiteralPath $SourcePath)) {
        return $null
    }

    New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
    $backupPath = Join-Path $BackupDirectory ([IO.Path]::GetFileName($SourcePath))
    Copy-Item -LiteralPath $SourcePath -Destination $backupPath -Force
    return $backupPath
}

function Write-BridgeProperties {
    param(
        [Parameter(Mandatory = $true)][string]$ClientIdentifier,
        [Parameter(Mandatory = $true)][string]$LauncherPath,
        [Parameter(Mandatory = $true)][string]$BridgePropertiesPath
    )

    $normalizedLauncherPath = Convert-ToForwardSlashPath -Path $LauncherPath
    $content = @"
# Generated by Setup.ps1
enabled=true
clientId=$ClientIdentifier
launcherPath=$normalizedLauncherPath
largeImageKey=coding
largeImageText=Coding in JetBrains Fleet
smallImageKey=fleet
smallImageText=JetBrains Fleet
debug=false
"@

    $parentDirectory = Split-Path -Parent $BridgePropertiesPath
    New-Item -ItemType Directory -Force -Path $parentDirectory | Out-Null
    [IO.File]::WriteAllText($BridgePropertiesPath, $content.Trim() + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Update-FleetConfig {
    param(
        [Parameter(Mandatory = $true)][string]$ResolvedFleetConfigPath,
        [Parameter(Mandatory = $true)][string]$ResolvedPluginsConfigurationPath
    )

    $content = Get-Content -LiteralPath $ResolvedFleetConfigPath -Raw
    $newline = Get-Newline -Content $content
    $hadTrailingNewline = $content.EndsWith($newline)
    $pluginOptionLine = "java-options=-Dfleet.custom.resolved-plugins-configuration.path=$(Convert-ToForwardSlashPath -Path $ResolvedPluginsConfigurationPath)"
    $optionPattern = '^java-options=-Dfleet\.custom\.resolved-plugins-configuration\.path='

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($content -split "\r?\n")) {
        [void]$lines.Add($line)
    }

    $cleaned = [System.Collections.Generic.List[string]]::new()
    $javaOptionsHeaderIndex = -1
    $optionInserted = $false

    foreach ($line in $lines) {
        if ($line -match '^\[JavaOptions\]\s*$') {
            $javaOptionsHeaderIndex = $cleaned.Count
            [void]$cleaned.Add($line)
            continue
        }

        if ($line -match $optionPattern) {
            if (-not $optionInserted) {
                [void]$cleaned.Add($pluginOptionLine)
                $optionInserted = $true
            }
            continue
        }

        [void]$cleaned.Add($line)
    }

    if (-not $optionInserted) {
        if ($javaOptionsHeaderIndex -ge 0) {
            $cleaned.Insert($javaOptionsHeaderIndex + 1, $pluginOptionLine)
        } else {
            if ($cleaned.Count -gt 0 -and $cleaned[$cleaned.Count - 1] -ne "") {
                [void]$cleaned.Add("")
            }
            [void]$cleaned.Add("[JavaOptions]")
            [void]$cleaned.Add($pluginOptionLine)
        }
    }

    $updatedContent = [string]::Join($newline, $cleaned)
    if ($hadTrailingNewline) {
        $updatedContent += $newline
    }

    [IO.File]::WriteAllText($ResolvedFleetConfigPath, $updatedContent, [Text.UTF8Encoding]::new($false))
}

function Start-FleetIfRequested {
    param(
        [string]$FleetExecutablePath,
        [switch]$ShouldLaunch
    )

    if (-not $ShouldLaunch) {
        return
    }

    if ([string]::IsNullOrWhiteSpace($FleetExecutablePath) -or -not (Test-Path -LiteralPath $FleetExecutablePath)) {
        Write-WarnLine "Fleet was configured successfully, but no Fleet executable was found to start automatically."
        return
    }

    Write-InfoLine ("Launching Fleet: {0}" -f $FleetExecutablePath)
    Start-Process -FilePath $FleetExecutablePath | Out-Null
}

if (-not (Test-IsWindowsPlatform)) {
    Fail "Setup.ps1 currently supports Windows only."
}

$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$clientId = Resolve-ClientId -InitialValue $ClientId

Write-Banner "Fleet Rich Presence Setup"
Write-InfoLine ("Project root: {0}" -f $projectRoot)

Write-Step "Updating repository"
Update-RepositoryIfPossible -ProjectRoot $projectRoot -Skip:$SkipRepositoryUpdate

Write-Step "Checking Fleet state"
Ensure-FleetIsClosed -ForceClose:$ForceCloseFleet

Write-Step "Locating Fleet"
$resolvedFleetConfigPath = Resolve-FleetConfig -ExplicitPath $FleetConfigPath
$fleetExecutablePath = Resolve-FleetExecutable -ResolvedFleetConfigPath $resolvedFleetConfigPath
Write-Ok ("Using Fleet config: {0}" -f $resolvedFleetConfigPath)
if ($fleetExecutablePath) {
    Write-InfoLine ("Fleet executable: {0}" -f $fleetExecutablePath)
}

Write-Step "Building plugin and bridge"
Invoke-Gradle -ProjectRoot $projectRoot -Skip:$SkipBuild

$resolvedPluginsConfigurationPath = Join-Path $projectRoot "fleet-rich-presence-plugin\build\resolvedPluginsConfiguration.json"
$bridgeLauncherPath = Join-Path $projectRoot "discord-bridge\build\install\discord-bridge\bin\discord-bridge.bat"
$bridgePropertiesPath = Join-Path $HOME ".fleet-rich-presence\bridge.properties"
$backupRoot = Join-Path $HOME (".fleet-rich-presence\backups\" + (Get-Date -Format "yyyyMMdd-HHmmss"))

if (-not (Test-Path -LiteralPath $resolvedPluginsConfigurationPath)) {
    Fail "The generated resolvedPluginsConfiguration.json file was not found: $resolvedPluginsConfigurationPath"
}

if (-not (Test-Path -LiteralPath $bridgeLauncherPath)) {
    Fail "The generated Discord bridge launcher was not found: $bridgeLauncherPath"
}

Write-Step "Writing user bridge configuration"
$bridgeBackup = Backup-File -SourcePath $bridgePropertiesPath -BackupDirectory $backupRoot
Write-BridgeProperties -ClientIdentifier $clientId -LauncherPath $bridgeLauncherPath -BridgePropertiesPath $bridgePropertiesPath
Write-Ok ("Wrote bridge config: {0}" -f $bridgePropertiesPath)
if ($bridgeBackup) {
    Write-InfoLine ("Backup created: {0}" -f $bridgeBackup)
}

Write-Step "Patching Fleet.cfg"
$fleetBackup = Backup-File -SourcePath $resolvedFleetConfigPath -BackupDirectory $backupRoot
Update-FleetConfig -ResolvedFleetConfigPath $resolvedFleetConfigPath -ResolvedPluginsConfigurationPath $resolvedPluginsConfigurationPath
Write-Ok ("Fleet config updated: {0}" -f $resolvedFleetConfigPath)
if ($fleetBackup) {
    Write-InfoLine ("Backup created: {0}" -f $fleetBackup)
}

Write-Step "Setup complete"
Write-Ok "Fleet Rich Presence is installed."
Write-InfoLine "Start Fleet normally. The plugin will auto-start the local Discord bridge."
Write-InfoLine ("Small Discord image asset is fixed to 'fleet' for every installation.")
Write-InfoLine ("Backups directory: {0}" -f $backupRoot)

Start-FleetIfRequested -FleetExecutablePath $fleetExecutablePath -ShouldLaunch:$LaunchFleet
