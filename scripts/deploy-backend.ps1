# deploy-backend.ps1
# Deploys the Spring Boot backend to Fly.io (single Machine).
# The Docker image (and the Spring Boot JAR inside it) is built by the Dockerfile
# builder stage - by default on Fly.io's remote builders, so no local Docker daemon
# or JDK is required. MongoDB schema migrations (Mongock) run on application startup.
#
# Prerequisites:
#   fly CLI  - https://fly.io/docs/hands-on/install-flyctl/
#   Secrets already set via: fly secrets set MONGODB_URI=... JWT_SECRET=... etc.
#   (-LocalBuild only) a running local Docker daemon
#
# Usage:
#   .\scripts\deploy-backend.ps1                 # remote build on Fly.io (default)
#   .\scripts\deploy-backend.ps1 -LocalBuild     # build the image with local Docker

param(
    [switch]$LocalBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path "$PSScriptRoot\.."
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

# Deploy to Fly.io.
# fly deploy reads the Dockerfile path from the fly.toml [build] section and builds the
# image (JAR included) in the Dockerfile builder stage - there is no separate host-side
# Gradle build to keep in sync. Remote build is the default (matches CI and needs no local
# Docker daemon); -LocalBuild builds with the local Docker daemon instead.
# Mongock changelog runs on startup - no separate migration step needed.
Write-Host "Deploying to Fly.io (image built from backend/Dockerfile)..." -ForegroundColor Yellow
$FlyArgs = @("deploy", "--config", $FlyConfig)
if (-not $LocalBuild) {
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
