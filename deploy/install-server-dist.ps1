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

# Remove and recreate target directory so it mirrors Gradle output exactly
if (Test-Path $TargetServerInstallDir) {
    Write-Host "Removing existing server install directory at $TargetServerInstallDir"
    Remove-Item -Recurse -Force $TargetServerInstallDir
}

Write-Host "Creating server install directory at $TargetServerInstallDir"
New-Item -ItemType Directory -Path $TargetServerInstallDir | Out-Null

Write-Host "Copying full server distribution from $GradleServerInstallDir to $TargetServerInstallDir"
Copy-Item -Path (Join-Path $GradleServerInstallDir "*") -Destination $TargetServerInstallDir -Recurse -Force
