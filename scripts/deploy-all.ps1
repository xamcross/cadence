# deploy-all.ps1
# Full release: DB check, backend deploy, frontend deploy.
# Run this after merging a completed feature to main.
#
# Order:
#   1. db-migrate.ps1   - verify Atlas is reachable before touching the backend
#   2. deploy-backend.ps1 - fly deploy (image built from Dockerfile; Mongock runs on startup)
#   3. deploy-frontend.ps1 - ng build + wrangler pages deploy
#
# Usage:
#   .\scripts\deploy-all.ps1
#   .\scripts\deploy-all.ps1 -AppName cadence-x    # override the target Fly app name
#   .\scripts\deploy-all.ps1 -LocalBuild           # build backend image with local Docker
#   .\scripts\deploy-all.ps1 -SkipFrontend         # backend only
#   .\scripts\deploy-all.ps1 -SkipDb               # skip Atlas connectivity check

param(
    [string]$AppName,
    [switch]$LocalBuild,
    [switch]$SkipFrontend,
    [switch]$SkipDb
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptsDir = $PSScriptRoot

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Cadence Full Deploy" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Step 1 - Atlas connectivity check
if (-not $SkipDb) {
    Write-Host ""
    Write-Host "--- Step 1/3: DB connectivity check ---" -ForegroundColor Magenta
    & "$ScriptsDir\db-migrate.ps1"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "DB check failed. Aborting deploy."
        exit 1
    }
} else {
    Write-Host "Skipping DB check (-SkipDb)." -ForegroundColor DarkGray
}

# Step 2 - Backend
Write-Host ""
Write-Host "--- Step 2/3: Backend deploy ---" -ForegroundColor Magenta
$BackendArgs = @()
if ($AppName) { $BackendArgs += @("-AppName", $AppName) }
if ($LocalBuild) { $BackendArgs += "-LocalBuild" }
& "$ScriptsDir\deploy-backend.ps1" @BackendArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Backend deploy failed. Aborting."
    exit 1
}

# Step 3 - Frontend
if (-not $SkipFrontend) {
    Write-Host ""
    Write-Host "--- Step 3/3: Frontend deploy ---" -ForegroundColor Magenta
    & "$ScriptsDir\deploy-frontend.ps1"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Frontend deploy failed."
        exit 1
    }
} else {
    Write-Host "Skipping frontend deploy (-SkipFrontend)." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Deploy complete." -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
