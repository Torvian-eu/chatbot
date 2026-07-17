param (
    [Parameter(Mandatory=$true, HelpMessage="The version tag for the release (e.g., v0.4.0 or v0.9.0-SNAPSHOT)")]
    [Alias("tag")]
    [string]$ReleaseTag,

    [Parameter(Mandatory=$false)]
    [string]$GitHubUser = "rwachters"
)

# 1. Path Setup
$DeployDir = $PSScriptRoot
$Registry = "ghcr.io"
$Org = "torvian-eu"

# 2. Release Type Detection
# Production tags strictly follow semantic versioning (e.g., v1.0.0 or 1.0.0).
# If it has a suffix like "-SNAPSHOT", "-rc1", or "-beta", we treat it as non-production.
$IsProduction = $ReleaseTag -match '^v?\d+\.\d+\.\d+$'

Write-Host "--- Starting Docker Build/Push Pipeline for $ReleaseTag ---" -ForegroundColor Cyan
if ($IsProduction) {
    Write-Host "Detected PRODUCTION release. The 'latest' tag WILL be updated." -ForegroundColor Green
} else {
    Write-Host "Detected NON-PRODUCTION / SNAPSHOT release. The 'latest' tag WILL NOT be updated." -ForegroundColor Yellow
}

# 3. Authentication Logic (Environment Variable > Prompt ONLY)
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

# 4. Docker Login
Write-Host "Logging in to $Registry as $GitHubUser..." -ForegroundColor Gray
$GitHubToken | docker login $Registry -u $GitHubUser --password-stdin
if ($LASTEXITCODE -ne 0) { Write-Error "Docker login failed. Check your token and username."; exit $LASTEXITCODE }
Write-Host "Docker login successful." -ForegroundColor Green

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

# 7. Docker Build, Tag, and Push Loop
foreach ($Img in $Images) {
    $ImgName = $Img.Name
    $ContextPath = Join-Path $DeployDir $Img.Context

    $VersionTag = "$Registry/$Org/${ImgName}:$ReleaseTag"
    $LatestTag = "$Registry/$Org/${ImgName}:latest"

    Write-Host "`n--- Processing Image: $ImgName ---" -ForegroundColor Cyan

    # Build direct to version tag (using context deploy/server/ or deploy/worker/)
    Write-Host "Building Docker image: $VersionTag (using context $ContextPath)" -ForegroundColor Gray
    docker build -t $VersionTag $ContextPath
    if ($LASTEXITCODE -ne 0) { Write-Error "Docker build failed for $ImgName"; continue }
    Write-Host "Image build successful." -ForegroundColor Green

    # Process production tagging if applicable
    if ($IsProduction) {
        Write-Host "Tagging image: $LatestTag" -ForegroundColor Gray
        docker tag $VersionTag $LatestTag
        if ($LASTEXITCODE -ne 0) { Write-Error "Tagging failed for $ImgName"; continue }
        Write-Host "Tagging successful." -ForegroundColor Green
    }

    # Push
    Write-Host "Pushing images to $Registry..." -ForegroundColor Gray
    docker push $VersionTag
    if ($LASTEXITCODE -ne 0) { Write-Error "Failed to push $VersionTag"; continue }

    if ($IsProduction) {
        docker push $LatestTag
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to push $LatestTag"; continue }
        Write-Host "Successfully pushed $ImgName (latest & $ReleaseTag)" -ForegroundColor Green
    } else {
        Write-Host "Successfully pushed $ImgName ($ReleaseTag only)" -ForegroundColor Green
    }
}

Write-Host "`n--- Docker Pipeline Complete ---" -ForegroundColor Cyan