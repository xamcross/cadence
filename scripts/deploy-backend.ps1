# deploy-backend.ps1
# Builds the Spring Boot JAR and deploys to Fly.io (single Machine).
# MongoDB schema migrations (Mongock) run automatically on application startup.
#
# Prerequisites:
#   fly CLI  - https://fly.io/docs/hands-on/install-flyctl/
#   Java 21  - must be on PATH
#   Secrets already set via: fly secrets set MONGODB_URI=... JWT_SECRET=... etc.
#
# Usage:
#   .\scripts\deploy-backend.ps1
#   .\scripts\deploy-backend.ps1 -RemoteBuild   # build Docker image on Fly.io infra

param(
    [switch]$RemoteBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path "$PSScriptRoot\.."
$BackendDir = Join-Path $RepoRoot "backend"
$FlyConfig = Join-Path $RepoRoot "fly.toml"

Write-Host "=== Cadence Backend Deploy ===" -ForegroundColor Cyan

# Check prerequisites
if (-not (Get-Command fly -ErrorAction SilentlyContinue)) {
    Write-Error "fly CLI not found. Install from: https://fly.io/docs/hands-on/install-flyctl/"
    exit 1
}
if (-not (Test-Path $FlyConfig)) {
    Write-Error "fly.toml not found at $FlyConfig. Create it with: fly launch"
    exit 1
}
if (-not (Test-Path (Join-Path $BackendDir "gradlew.bat"))) {
    Write-Error "Gradle wrapper not found. Expected: $BackendDir\gradlew.bat"
    exit 1
}

# Build the Spring Boot JAR
Write-Host "[1/2] Building Spring Boot JAR..." -ForegroundColor Yellow
Push-Location $BackendDir
try {
    & .\gradlew.bat clean bootJar
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle build failed."
        exit 1
    }
} finally {
    Pop-Location
}

# Deploy to Fly.io
# fly deploy reads Dockerfile path from fly.toml [build] section.
# Mongock changelog runs on startup - no separate migration step needed.
Write-Host "[2/2] Deploying to Fly.io..." -ForegroundColor Yellow
$FlyArgs = @("deploy", "--config", $FlyConfig)
if ($RemoteBuild) {
    $FlyArgs += "--remote-only"
}
& fly @FlyArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "fly deploy failed."
    exit 1
}

Write-Host ""
Write-Host "Backend deployed. Polling /actuator/health..." -ForegroundColor Yellow

$MaxAttempts = 30
$SleepSeconds = 10
$Healthy = $false

for ($i = 1; $i -le $MaxAttempts; $i++) {
    Write-Host "Health poll attempt $i/$MaxAttempts..."
    $ProxyJob = $null
    try {
        $ProxyJob = Start-Job -ScriptBlock { & fly proxy 8081:8081 2>$null }
        Start-Sleep -Seconds 3
        $Response = Invoke-WebRequest -Uri "http://localhost:8081/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($Response.StatusCode -eq 200) {
            $Healthy = $true
        }
    } catch {
        # Connection refused or timeout - not yet healthy, continue polling
    } finally {
        if ($ProxyJob) {
            Stop-Job $ProxyJob -ErrorAction SilentlyContinue
            Remove-Job $ProxyJob -ErrorAction SilentlyContinue
        }
    }
    if ($Healthy) { break }
    Start-Sleep -Seconds $SleepSeconds
}

if (-not $Healthy) {
    Write-Error "Deployment failed: backend not healthy after 5 minutes. Run: fly logs"
    exit 1
}

Write-Host "Backend deployed and healthy." -ForegroundColor Green
Write-Host "Mongock migrations applied on startup - check logs with: fly logs" -ForegroundColor Cyan
