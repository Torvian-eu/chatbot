# Builds the WASM client project using the Gradle wrapper and installs the distribution to the deploy/client/dist directory.

# Set the error action preference to stop on errors
$ErrorActionPreference = "Stop"

# Get the project root directory by getting the parent of the script's directory
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Define the path to the deploy directory
$DeployDir = Join-Path $ProjectRoot "deploy"
$ClientDistDir = Join-Path $DeployDir "wasm-client\dist"

# Define the path to the Gradle client distribution directory (where Gradle will output the client distribution)
$GradleClientDistDir = Join-Path $ProjectRoot "app\build\dist\wasmJs\productionExecutable"

# Build & Install the client project using the Gradle wrapper
Write-Host "Building & Installing the client project..."
Push-Location $ProjectRoot
try {
    ./gradlew app:wasmJsBrowserDistribution

    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    Write-Host "Client project built and installed successfully"
} finally {
    Pop-Location
}

# Remove the existing client dist directory if it exists
if (Test-Path $ClientDistDir) {
    Write-Host "Removing existing client dist directory at $ClientDistDir"
    Remove-Item -Recurse -Force $ClientDistDir
}

# Ensure target directory exists
if (-not (Test-Path $ClientDistDir)) {
    New-Item -ItemType Directory -Path $ClientDistDir | Out-Null
}

# Copy the client distribution from the Gradle build output to the target client dist directory
Write-Host "Copying client distribution from $GradleClientDistDir to $ClientDistDir"
Copy-Item -Path (Join-Path $GradleClientDistDir "*") -Destination $ClientDistDir -Recurse -Force