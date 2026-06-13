# deploy-frontend.ps1
# Builds the Angular SPA (production) and deploys to Cloudflare Pages via wrangler.
#
# Prerequisites:
#   Node.js 20+   - https://nodejs.org/
#   Angular CLI   - npm install -g @angular/cli
#   Wrangler CLI  - npm install -g wrangler
#   Authenticated - run: wrangler login
#
# Usage:
#   .\scripts\deploy-frontend.ps1
#   .\scripts\deploy-frontend.ps1 -Branch "staging"   # deploy to a preview branch

param(
    [string]$Branch = "main"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path "$PSScriptRoot\.."
$FrontendDir = Join-Path $RepoRoot "frontend"

# Angular 17 (esbuild builder) outputs to dist/<project>/browser/
# Adjust DistDir if using a different project name or classic builder.
$DistDir = Join-Path $FrontendDir "dist\cadence\browser"

Write-Host "=== Cadence Frontend Deploy ===" -ForegroundColor Cyan

# Check prerequisites
if (-not (Get-Command ng -ErrorAction SilentlyContinue)) {
    Write-Error "Angular CLI not found. Run: npm install -g @angular/cli"
    exit 1
}
if (-not (Get-Command wrangler -ErrorAction SilentlyContinue)) {
    Write-Error "Wrangler CLI not found. Run: npm install -g wrangler"
    exit 1
}
if (-not (Test-Path $FrontendDir)) {
    Write-Error "Frontend directory not found: $FrontendDir"
    exit 1
}

# Install npm dependencies if node_modules is missing
$NodeModules = Join-Path $FrontendDir "node_modules"
if (-not (Test-Path $NodeModules)) {
    Write-Host "Installing npm dependencies..." -ForegroundColor Yellow
    Push-Location $FrontendDir
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { Write-Error "npm ci failed."; exit 1 }
    } finally {
        Pop-Location
    }
}

# Production build
Write-Host "[1/2] Building Angular app (production)..." -ForegroundColor Yellow
Push-Location $FrontendDir
try {
    & ng build --configuration production
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Angular build failed."
        exit 1
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $DistDir)) {
    Write-Error "Build output not found at $DistDir. Check angular.json outputPath."
    exit 1
}

# Deploy to Cloudflare Pages
Write-Host "[2/2] Deploying to Cloudflare Pages (branch: $Branch)..." -ForegroundColor Yellow
& wrangler pages deploy $DistDir --project-name cadence --branch $Branch
if ($LASTEXITCODE -ne 0) {
    Write-Error "Cloudflare Pages deploy failed."
    exit 1
}

Write-Host ""
Write-Host "Frontend deployed." -ForegroundColor Green
