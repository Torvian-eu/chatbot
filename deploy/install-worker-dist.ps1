# Builds the worker project using the Gradle wrapper and installs the distribution to the deploy/worker/dist directory.

# Set the error action preference to stop on errors
$ErrorActionPreference = "Stop"

# Get the project root directory by getting the parent of the script's directory
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Define the path to the deploy directory
$DeployDir = Join-Path $ProjectRoot "deploy"

# Define the path to the Gradle worker install directory (where Gradle will install the worker distribution)
$GradleWorkerInstallDir = Join-Path $ProjectRoot "worker\build\install\worker"

# Define the path to the worker install directory
$TargetWorkerInstallDir = Join-Path $DeployDir "worker\dist"

# Build & Install the worker project using the Gradle wrapper
Write-Host "Building & Installing the worker project..."
Push-Location $ProjectRoot
try {
    ./gradlew worker:installDist

    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    Write-Host "Worker project built and installed successfully"
}
finally {
    Pop-Location
}

# Remove and recreate target directory so it mirrors Gradle output exactly
if (Test-Path $TargetWorkerInstallDir) {
    Write-Host "Removing existing worker install directory at $TargetWorkerInstallDir"
    Remove-Item -Recurse -Force $TargetWorkerInstallDir
}

Write-Host "Creating worker install directory at $TargetWorkerInstallDir"
New-Item -ItemType Directory -Path $TargetWorkerInstallDir | Out-Null

Write-Host "Copying full worker distribution from $GradleWorkerInstallDir to $TargetWorkerInstallDir"
Copy-Item -Path (Join-Path $GradleWorkerInstallDir "*") -Destination $TargetWorkerInstallDir -Recurse -Force

# Copy the Docker startup script, overwriting if it exists
$DockerScriptSource = Join-Path $DeployDir "worker\start-worker-docker.sh"
$DockerScriptTarget = Join-Path $TargetWorkerInstallDir "start-worker.sh"
Write-Host "Copying Worker startup script from $DockerScriptSource to $DockerScriptTarget"
Copy-Item -Path $DockerScriptSource -Destination $DockerScriptTarget -Force
