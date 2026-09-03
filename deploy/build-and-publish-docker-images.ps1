<#
.SYNOPSIS
    Builds (and optionally publishes) the Torvian chatbot Docker images for the server and worker.
.DESCRIPTION
    Installs the server and worker distributions, then builds their Docker images.
    By default it tags production images as 'latest', non-production images as 'snapshot',
    and pushes the images to the GitHub Container Registry. Use the switches below to limit
    which images are built or to skip publishing entirely.
#>

param (
    [Parameter(Mandatory=$true, HelpMessage="The version tag for the release (e.g., v0.4.0 or v0.9.0-SNAPSHOT)")]
    [Alias("tag")]
    [string]$ReleaseTag,

    [Parameter(Mandatory=$false, HelpMessage="The GitHub username used to authenticate against the container registry (defaults to 'rwachters')")]
    [string]$GitHubUser = "rwachters",

    [Parameter(Mandatory=$false, HelpMessage="Build and tag the images locally but skip Docker login and pushing to the registry")]
    [switch]$NoPublish,

    [Parameter(Mandatory=$false, HelpMessage="Build and publish only the chatbot-server image (mutually exclusive with WorkerOnly)")]
    [switch]$ServerOnly,

    [Parameter(Mandatory=$false, HelpMessage="Build and publish only the chatbot-worker image (mutually exclusive with ServerOnly)")]
    [switch]$WorkerOnly
)

# 1. Path Setup
$DeployDir = $PSScriptRoot
$Registry = "ghcr.io"
$Org = "torvian-eu"

# 2. Release Type Detection
# Production tags strictly follow semantic versioning (e.g., v1.0.0 or 1.0.0).
# If it has a suffix like "-SNAPSHOT", "-rc1", or "-beta", we treat it as non-production.
$IsProduction = $ReleaseTag -match '^v?\d+\.\d+\.\d+$'

# Validate that ServerOnly and WorkerOnly are not both specified (they are mutually exclusive).
if ($ServerOnly -and $WorkerOnly) {
    Write-Error "ServerOnly and WorkerOnly cannot be used together. Aborting."
    exit 1
}

Write-Host "--- Starting Docker Build/Push Pipeline for $ReleaseTag ---" -ForegroundColor Cyan
if ($IsProduction) {
    Write-Host "Detected PRODUCTION release. The 'latest' tag WILL be updated." -ForegroundColor Green
} else {
    Write-Host "Detected NON-PRODUCTION / SNAPSHOT release. The 'latest' tag WILL NOT be updated." -ForegroundColor Yellow
}

# 3. Authentication Logic (Environment Variable > Prompt ONLY)
# Login is only required when actually pushing images.
if (-not $NoPublish) {
    Write-Host "`nAttempting to retrieve GitHub Access Token..." -ForegroundColor Gray
    $GitHubToken = $null
    if ($env:GH_PAT) {
        $GitHubToken = $env:GH_PAT
        Write-Host "Using GitHub Token from environment variable (GH_PAT)." -ForegroundColor Green
    } else {
        Write-Host "Environment variable GH_PAT not found. Prompting for token." -ForegroundColor Yellow
        $SecureToken = Read-Host "Enter GitHub Access Token (needs 'write:packages' scope)" -AsSecureString
        $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureToken)
        $GitHubToken = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($ptr)
    }

    # Validate that a token was obtained
    if ([string]::IsNullOrEmpty($GitHubToken)) {
        Write-Error "No GitHub Access Token provided or found. Aborting."
        exit 1
    }
} else {
    Write-Host "`n-NoPublish specified. Skipping authentication and push." -ForegroundColor Yellow
}

# 4. Docker Login
# Only log in when publishing, since the registry credentials are not needed for local builds.
if (-not $NoPublish) {
    Write-Host "Logging in to $Registry as $GitHubUser..." -ForegroundColor Gray
    $GitHubToken | docker login $Registry -u $GitHubUser --password-stdin
    if ($LASTEXITCODE -ne 0) { Write-Error "Docker login failed. Check your token and username."; exit $LASTEXITCODE }
    Write-Host "Docker login successful." -ForegroundColor Green
}

# 5. Execute your existing installation scripts
Write-Host "Installing server and worker distributions using local scripts..." -ForegroundColor Gray
try {
    & "$DeployDir\install-server-dist.ps1"
    & "$DeployDir\install-worker-dist.ps1"
} catch {
    Write-Error "One of the installation scripts failed: $_"
    exit 1
}
Write-Host "Distribution installation complete." -ForegroundColor Green

# 6. Define Images and Contexts
$Images = @(
    @{ Name = "chatbot-server"; Context = "server" },
    @{ Name = "chatbot-worker"; Context = "worker" }
)

# Restrict to a single image when ServerOnly or WorkerOnly is specified.
if ($ServerOnly) {
    $Images = $Images | Where-Object { $_.Name -eq "chatbot-server" }
    Write-Host "ServerOnly specified. Only the server image will be processed." -ForegroundColor Yellow
} elseif ($WorkerOnly) {
    $Images = $Images | Where-Object { $_.Name -eq "chatbot-worker" }
    Write-Host "WorkerOnly specified. Only the worker image will be processed." -ForegroundColor Yellow
}

# 7. Docker Build, Tag, and Push Loop
foreach ($Img in $Images) {
    $ImgName = $Img.Name
    $ContextPath = Join-Path $DeployDir $Img.Context

    $VersionTag = "$Registry/$Org/${ImgName}:$ReleaseTag"
    $ChannelTagName = if ($IsProduction) { "latest" } else { "snapshot" }
    $ChannelTag = "$Registry/$Org/${ImgName}:$ChannelTagName"

    Write-Host "`n--- Processing Image: $ImgName ---" -ForegroundColor Cyan

    # Build direct to version tag (using context deploy/server/ or deploy/worker/)
    Write-Host "Building Docker image: $VersionTag (using context $ContextPath)" -ForegroundColor Gray
    docker build -t $VersionTag $ContextPath
    if ($LASTEXITCODE -ne 0) { Write-Error "Docker build failed for $ImgName"; continue }
    Write-Host "Image build successful." -ForegroundColor Green

    # Add a mutable channel tag: stable releases use 'latest', while all other releases use
    # 'snapshot'. The version-specific tag remains available for reproducible deployments.
    Write-Host "Tagging image: $ChannelTag" -ForegroundColor Gray
    docker tag $VersionTag $ChannelTag
    if ($LASTEXITCODE -ne 0) { Write-Error "Tagging failed for $ImgName"; continue }
    Write-Host "Tagging successful." -ForegroundColor Green

    # Push (skipped when -NoPublish is specified; both tags still exist locally)
    if ($NoPublish) {
        Write-Host "Skipping push for $ImgName (NoPublish)." -ForegroundColor Yellow
        continue
    }

    Write-Host "Pushing images to $Registry..." -ForegroundColor Gray
    docker push $VersionTag
    if ($LASTEXITCODE -ne 0) { Write-Error "Failed to push $VersionTag"; continue }

    docker push $ChannelTag
    if ($LASTEXITCODE -ne 0) { Write-Error "Failed to push $ChannelTag"; continue }
    Write-Host "Successfully pushed $ImgName ($ChannelTagName & $ReleaseTag)" -ForegroundColor Green
}

Write-Host "`n--- Docker Pipeline Complete ---" -ForegroundColor Cyan