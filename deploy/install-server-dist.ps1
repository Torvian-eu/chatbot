# Builds the server project using the Gradle wrapper and installs the distribution to the deploy/server/dist directory.

# Set the error action preference to stop on errors
$ErrorActionPreference = "Stop"

# Get the project root directory by getting the parent of the script's directory
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Define the path to the deploy directory
$DeployDir = Join-Path $ProjectRoot "deploy"

# Define the path to the Gradle server install directory (where Gradle will install the server distribution)
$GradleServerInstallDir = Join-Path $ProjectRoot "server\build\install\server"

# Define the path to the server install directory
$TargetServerInstallDir = Join-Path $DeployDir "server\dist"

# Build & Install the server project using the Gradle wrapper
Write-Host "Building & Installing the server project..."
Push-Location $ProjectRoot
try {
    ./gradlew server:installDist

    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    Write-Host "Server project built and installed successfully"
}
finally {
    Pop-Location
}

# Remove the existing lib/ directory in the target server install directory if it exists
$TargetLibDir = Join-Path $TargetServerInstallDir "lib"
if (Test-Path $TargetLibDir) {
    Write-Host "Removing existing lib/ directory at $TargetLibDir"
    Remove-Item -Recurse -Force $TargetLibDir
}

# Ensure target directory exists
if (-not (Test-Path $TargetServerInstallDir)) {
    New-Item -ItemType Directory -Path $TargetServerInstallDir | Out-Null
}

# Conditionally exclude config/ if it already exists in target
$TargetConfigDir = Join-Path $TargetServerInstallDir "config"

if (Test-Path $TargetConfigDir) {
    Write-Host "Target config directory already exists at $TargetConfigDir - preserving it"

    Get-ChildItem -Path $GradleServerInstallDir | Where-Object { $_.Name -ne "config" } | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination $TargetServerInstallDir -Recurse -Force
    }
}
else {
    Write-Host "Copying full server distribution from $GradleServerInstallDir to $TargetServerInstallDir"
    Copy-Item -Path (Join-Path $GradleServerInstallDir "*") -Destination $TargetServerInstallDir -Recurse -Force
}

