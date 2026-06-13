# db-migrate.ps1
# Verifies connectivity to MongoDB Atlas and reports migration status.
#
# Schema migrations and indexes are managed by Mongock and applied automatically
# when the Spring Boot backend starts. This script exists for two purposes:
#   1. Pre-deploy connectivity check: confirm Atlas is reachable before deploying.
#   2. Migration status report: show which Mongock changesets have been applied.
#
# For a standalone migration run without a full backend redeploy, start the
# backend locally with the Atlas URI and let Mongock apply pending changesets:
#   MONGODB_URI=<atlas-uri> .\backend\gradlew.bat bootRun --args="--spring.profiles.active=migrate-only"
#
# Prerequisites:
#   mongosh - https://www.mongodb.com/docs/mongodb-shell/
#   MONGODB_URI environment variable set, or passed via -Uri parameter.
#
# Usage:
#   $env:MONGODB_URI = "mongodb+srv://..."
#   .\scripts\db-migrate.ps1
#   .\scripts\db-migrate.ps1 -Uri "mongodb+srv://..."

param(
    [string]$Uri = $env:MONGODB_URI,
    [string]$Database = "cadence"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "=== Cadence DB Migration Check ===" -ForegroundColor Cyan

# Check prerequisites
if (-not (Get-Command mongosh -ErrorAction SilentlyContinue)) {
    Write-Error "mongosh not found. Install from: https://www.mongodb.com/docs/mongodb-shell/"
    exit 1
}
if ([string]::IsNullOrWhiteSpace($Uri)) {
    Write-Error "MONGODB_URI is not set. Pass -Uri or set the environment variable."
    exit 1
}

# Ping Atlas to verify connectivity
Write-Host "[1/2] Pinging Atlas..." -ForegroundColor Yellow
$PingScript = "db.runCommand({ ping: 1 })"
& mongosh $Uri --eval $PingScript --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Error "Atlas connectivity check failed. Verify MONGODB_URI and network access rules."
    exit 1
}
Write-Host "Atlas connectivity OK." -ForegroundColor Green

# Report Mongock changeset status
Write-Host "[2/2] Mongock changeset status (database: $Database)..." -ForegroundColor Yellow
$StatusScript = @"
use $Database;
var count = db.mongockChangeLog.countDocuments();
print('Total changesets applied: ' + count);
db.mongockChangeLog.find({}, { changeId: 1, state: 1, timestamp: 1 })
  .sort({ timestamp: -1 })
  .limit(10)
  .forEach(function(d) {
    print(d.state + '  ' + d.changeId + '  ' + d.timestamp);
  });
"@
& mongosh $Uri --eval $StatusScript --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Host "Could not read mongockChangeLog (expected if database is empty)." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "To apply pending migrations: run .\scripts\deploy-backend.ps1" -ForegroundColor Cyan
Write-Host "Mongock applies all pending changesets on backend startup." -ForegroundColor Cyan
