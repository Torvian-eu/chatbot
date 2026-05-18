param (
    [Parameter(Mandatory=$true, HelpMessage="The version tag for the release (e.g., v0.4.0)")]
    [Alias("tag")]
    [string]$ReleaseTag,

    [Parameter(Mandatory=$false)]
    [string]$GitHubUser = "rwachters"
)

# 1. Path Setup
$DeployDir = $PSScriptRoot
$Registry = "ghcr.io"
$Org = "torvian-eu"

# 2. Authentication Logic (Environment Variable > Prompt ONLY)
Write-Host "Attempting to retrieve GitHub Access Token..." -ForegroundColor Gray
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

Write-Host "--- Starting Docker Build/Push Pipeline for $ReleaseTag ---" -ForegroundColor Cyan

# 3. Docker Login
Write-Host "Logging in to $Registry as $GitHubUser..." -ForegroundColor Gray
$GitHubToken | docker login $Registry -u $GitHubUser --password-stdin
if ($LASTEXITCODE -ne 0) { Write-Error "Docker login failed. Check your token and username."; exit $LASTEXITCODE }
Write-Host "Docker login successful." -ForegroundColor Green

# 4. Execute your existing installation scripts
Write-Host "Installing server and worker distributions using local scripts..." -ForegroundColor Gray
try {
    & "$DeployDir\install-server-dist.ps1"
    & "$DeployDir\install-worker-dist.ps1"
} catch {
    Write-Error "One of the installation scripts failed: $_"
    exit 1
}
Write-Host "Distribution installation complete." -ForegroundColor Green

# 5. Define Images and Contexts
$Images = @(
    @{ Name = "chatbot-server"; Context = "server" },
    @{ Name = "chatbot-worker"; Context = "worker" }
)

# 6. Docker Build, Tag, and Push Loop
foreach ($Img in $Images) {
    $ImgName = $Img.Name
    $ContextPath = Join-Path $DeployDir $Img.Context

    $LatestTag = "$Registry/$Org/${ImgName}:latest"
    $VersionTag = "$Registry/$Org/${ImgName}:$ReleaseTag"

    Write-Host "`n--- Processing Image: $ImgName ---" -ForegroundColor Cyan

    # Build (Dockerfile is expected inside deploy/server/ or deploy/worker/)
    Write-Host "Building Docker image: $LatestTag (using context $ContextPath)" -ForegroundColor Gray
    docker build -t $LatestTag $ContextPath
    if ($LASTEXITCODE -ne 0) { Write-Error "Docker build failed for $ImgName"; continue }
    Write-Host "Image build successful." -ForegroundColor Green

    # Tag
    Write-Host "Tagging image: $VersionTag" -ForegroundColor Gray
    docker tag $LatestTag $VersionTag
    Write-Host "Tagging successful." -ForegroundColor Green

    # Push
    Write-Host "Pushing images to $Registry..." -ForegroundColor Gray
    docker push $LatestTag
    docker push $VersionTag

    Write-Host "Successfully pushed $ImgName (latest & $ReleaseTag)" -ForegroundColor Green
}

Write-Host "`n--- Docker Pipeline Complete ---" -ForegroundColor Cyan