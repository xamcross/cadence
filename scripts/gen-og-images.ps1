# Cadence Open Graph card generator (System.Drawing, no downloads - Principle X).
#
# Renders the 1200x630 social cards committed under frontend/src/assets/: the base brand card
# (og-cadence.png) and one card per article/marketing page (assets/og/<name>.png). Re-run after
# adding a page or changing a title, then commit the PNGs. Pure ASCII file (Principle V).
#
#   powershell -ExecutionPolicy Bypass -File scripts\gen-og-images.ps1

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $repoRoot 'frontend\src\assets'
$ogDir = Join-Path $assets 'og'
if (-not (Test-Path $ogDir)) { New-Item -ItemType Directory -Path $ogDir | Out-Null }

# Brand: the favicon blue (#1c5fd8) with a darker bottom edge; white bars motif from favicon.svg.
$brandBlue = [System.Drawing.Color]::FromArgb(0x1c, 0x5f, 0xd8)
$brandDark = [System.Drawing.Color]::FromArgb(0x14, 0x3f, 0x8f)
$white = [System.Drawing.Color]::White
$softWhite = [System.Drawing.Color]::FromArgb(0xd9, 0xe4, 0xf7)

function New-RoundedRectPath([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function Split-TitleLines([System.Drawing.Graphics]$g, [string]$title, [System.Drawing.Font]$font, [single]$maxWidth) {
    $words = $title -split ' '
    $lines = @()
    $cur = ''
    foreach ($w in $words) {
        $probe = if ($cur -eq '') { $w } else { "$cur $w" }
        $size = $g.MeasureString($probe, $font)
        if ($size.Width -gt $maxWidth -and $cur -ne '') {
            $lines += $cur
            $cur = $w
        } else {
            $cur = $probe
        }
    }
    if ($cur -ne '') { $lines += $cur }
    return $lines
}

function New-OgCard([string]$outPath, [string]$kicker, [string]$title) {
    $bmp = New-Object System.Drawing.Bitmap(1200, 630)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

    # Background: vertical brand gradient.
    $rect = New-Object System.Drawing.Rectangle(0, 0, 1200, 630)
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $brandBlue, $brandDark, 90.0)
    $g.FillRectangle($bg, $rect)
    $bg.Dispose()

    # The three-bar mark (from favicon.svg geometry, scaled and vertically centered like the favicon).
    $whiteBrush = New-Object System.Drawing.SolidBrush($white)
    foreach ($bar in @(@(80, 116, 22, 70), @(119, 86, 22, 100), @(158, 141, 22, 45))) {
        $p = New-RoundedRectPath $bar[0] $bar[1] $bar[2] $bar[3] 11
        $g.FillPath($whiteBrush, $p)
        $p.Dispose()
    }

    # Wordmark next to the mark.
    $wordFont = New-Object System.Drawing.Font('Segoe UI', 44, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString('Cadence', $wordFont, $whiteBrush, 202, 108)
    $wordFont.Dispose()

    # Kicker (small caps line above the title).
    $kickerFont = New-Object System.Drawing.Font('Segoe UI', 26, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $kickerBrush = New-Object System.Drawing.SolidBrush($softWhite)
    $g.DrawString($kicker.ToUpperInvariant(), $kickerFont, $kickerBrush, 76, 300)
    $kickerFont.Dispose()

    # Title, wrapped to at most three lines.
    $titleFont = New-Object System.Drawing.Font('Segoe UI Semibold', 64, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $lines = Split-TitleLines $g $title $titleFont 1048
    if ($lines.Count -gt 3) { $lines = $lines[0..2]; $lines[2] = $lines[2] + '...' }
    $y = 348
    foreach ($line in $lines) {
        $g.DrawString($line, $titleFont, $whiteBrush, 72, $y)
        $y += 82
    }
    $titleFont.Dispose()

    $kickerBrush.Dispose()
    $whiteBrush.Dispose()
    $g.Dispose()
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $outPath"
}

# Base brand card (also the Organization logo target).
New-OgCard (Join-Path $assets 'og-cadence.png') 'Cadence' 'Interview scheduling that respects candidates'

# Articles.
New-OgCard (Join-Path $ogDir 'reducing-interview-no-shows.png') 'Recruiting guide' 'How to reduce interview no-shows'
New-OgCard (Join-Path $ogDir 'candidate-experience-best-practices.png') 'Recruiting guide' 'Candidate experience best practices for recruiters'
New-OgCard (Join-Path $ogDir 'gdpr-safe-recruiting.png') 'Recruiting guide' 'Privacy-safe and GDPR-conscious recruiting'
New-OgCard (Join-Path $ogDir 'interview-scheduling-and-calendar-coordination.png') 'Recruiting guide' 'Interview scheduling and calendar coordination'

# Marketing pages.
New-OgCard (Join-Path $ogDir 'features.png') 'Product' 'Interview scheduling software features'
New-OgCard (Join-Path $ogDir 'pricing.png') 'Product' 'Free while in early access'
New-OgCard (Join-Path $ogDir 'integrations.png') 'Integrations' 'Calendars, ATS, and CSV import'
New-OgCard (Join-Path $ogDir 'integrations-google-calendar.png') 'Integration' 'Google Calendar'
New-OgCard (Join-Path $ogDir 'integrations-microsoft-365.png') 'Integration' 'Microsoft 365 calendars'
New-OgCard (Join-Path $ogDir 'integrations-greenhouse.png') 'Integration' 'Greenhouse ATS sync'
New-OgCard (Join-Path $ogDir 'integrations-lever.png') 'Integration' 'Lever ATS sync'
New-OgCard (Join-Path $ogDir 'vs-calendly.png') 'Comparison' 'Cadence vs Calendly for recruiting teams'

Write-Host 'done'
