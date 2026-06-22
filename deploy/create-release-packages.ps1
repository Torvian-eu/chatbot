param (
    [Parameter(Mandatory=$true)]
    [Alias("tag")]
    [string]$ReleaseTag,

    [Parameter(Mandatory=$false)]
    [string]$WslDistro = "UbuntuDev",

    [Parameter(Mandatory=$false)]
    [string]$WslProjectPath = "~/projects/chatbot"
)

# 1. Setup Paths
$DeployDir = $PSScriptRoot
$ProjectRoot = (Get-Item $DeployDir).Parent.FullName
$PackageDir = Join-Path $DeployDir "packages"

# Ensure the Packages directory exists before we start
if (!(Test-Path $PackageDir)) { New-Item -ItemType Directory -Path $PackageDir | Out-Null }

$WindowsConfigs = @(
    @{ Name = "Server"; Task = "server:installDist"; Source = "server/build/install/server"; Prefix = "Chatbot_server" },
    @{ Name = "Worker"; Task = "worker:installDist"; Source = "worker/build/install/worker"; Prefix = "Chatbot_worker" },
    @{ Name = "Windows Client"; Task = "app:createDistributable"; Source = "app/build/compose/binaries/main/app/Chatbot"; Prefix = "Chatbot_client_windows" },
    @{ Name = "Web Client"; Task = "app:wasmJsBrowserDistribution"; Source = "app/build/dist/wasmJs/productionExecutable"; Prefix = "Chatbot_client_web" }
)

Write-Host "--- Starting Full Multi-Platform Build for $ReleaseTag ---" -ForegroundColor Cyan

# 2. Run Windows/Web Gradle Tasks
Push-Location $ProjectRoot
Write-Host "[1/3] Building Windows and Web modules..." -ForegroundColor Gray
$Tasks = $WindowsConfigs.Task
./gradlew $Tasks
if ($LASTEXITCODE -ne 0) { Write-Error "Windows build failed."; exit $LASTEXITCODE }
Pop-Location

# 3. Handle WSL Linux Build
Write-Host "[2/3] Starting Linux build via WSL ($WslDistro)..." -ForegroundColor Yellow

# Convert Windows Package path to WSL format (e.g., C:\Path -> /mnt/c/path)
# We use quotes to handle potential spaces in paths
$WinPathRaw = (Get-Item $PackageDir).FullName
$WslPackagePath = "/mnt/" + $WinPathRaw.Replace(":", "").Replace("\", "/").ToLower()

# Use 'bash -l -c' to ensure the environment (SSH keys, etc.) is loaded
$LinuxTarName = "Chatbot_client_linux_x64_$ReleaseTag.tar.gz"
$LinuxCommands = "cd $WslProjectPath && git pull origin master && ./gradlew :app:createDistributable && cd app/build/compose/binaries/main/app/Chatbot && tar -czf ../$LinuxTarName . && mv ../$LinuxTarName '$WslPackagePath/'"

Write-Host "Executing Linux build commands..." -ForegroundColor Gray
wsl -d $WslDistro bash -l -c $LinuxCommands

if ($LASTEXITCODE -ne 0) {
    Write-Warning "Linux build process encountered an error. Check WSL output above."
} else {
    Write-Host "Linux package created and copied successfully." -ForegroundColor Green
}

# 4. Package Windows/Web results into Zips
Write-Host "[3/3] Packaging Windows and Web files..." -ForegroundColor Gray
foreach ($Config in $WindowsConfigs) {
    $FullSourcePath = Join-Path $ProjectRoot $Config.Source
    $ZipFileName = "$($Config.Prefix)_$ReleaseTag.zip"
    $ZipFilePath = Join-Path $PackageDir $ZipFileName

    if (Test-Path $FullSourcePath) {
        if (Test-Path $ZipFilePath) { Remove-Item $ZipFilePath -Force }
        Compress-Archive -Path "$FullSourcePath\*" -DestinationPath $ZipFilePath
        Write-Host "Created: $ZipFileName" -ForegroundColor Green
    }
}

Write-Host "`n--- All builds complete! ---" -ForegroundColor Cyan