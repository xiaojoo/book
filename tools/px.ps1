# ASCII only. PowerShell 5.1 reads .ps1 as GBK otherwise.
# usage: powershell -NoProfile -File tools/px.ps1 -Png shots/a.png -Probe col:30:700:1100
param(
    [Parameter(Mandatory = $true)][string]$Image,
    [Parameter(Mandatory = $true)][string]$Probe,
    [string]$Out = 'crop.png'
)

Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Image))
Write-Output ("IMAGE " + $bmp.Width + "x" + $bmp.Height)

function Get-Px([int]$x, [int]$y) {
    $c = $bmp.GetPixel($x, $y)
    return @($c.R, $c.G, $c.B)
}
function Fmt([int[]]$p) { return ("#{0:X2}{1:X2}{2:X2}" -f $p[0], $p[1], $p[2]) }

$parts = $Probe.Split(':')
$mode = $parts[0]

if ($mode -eq 'crop') {
    $cx = [int]$parts[1]; $cy = [int]$parts[2]; $cw = [int]$parts[3]; $ch = [int]$parts[4]; $sc = [int]$parts[5]
    $dst = New-Object -TypeName System.Drawing.Bitmap -ArgumentList ($cw * $sc), ($ch * $sc)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $dstRect = [System.Drawing.Rectangle]::new(0, 0, ($cw * $sc), ($ch * $sc))
    $srcRect = [System.Drawing.Rectangle]::new($cx, $cy, $cw, $ch)
    $g.DrawImage($bmp, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $dst.Save((Join-Path (Get-Location) $Out), [System.Drawing.Imaging.ImageFormat]::Png)
    $dst.Dispose()
    Write-Output ("CROP " + (Join-Path (Get-Location) $Out))
}
elseif ($mode -eq 'point') {
    Write-Output ("POINT " + $parts[1] + "," + $parts[2] + " " + (Fmt (Get-Px ([int]$parts[1]) ([int]$parts[2]))))
}
elseif ($mode -eq 'col') {
    # scan one column, report every y where the color changes
    $x = [int]$parts[1]; $y0 = [int]$parts[2]; $y1 = [Math]::Min([int]$parts[3], $bmp.Height - 1)
    $prev = ''; $start = $y0
    for ($y = $y0; $y -le $y1; $y++) {
        $hex = Fmt (Get-Px $x $y)
        if ($hex -ne $prev) {
            if ($prev -ne '') { Write-Output ("COL y " + $start + "-" + ($y - 1) + " " + $prev) }
            $prev = $hex; $start = $y
        }
    }
    Write-Output ("COL y " + $start + "-" + $y1 + " " + $prev)
}
elseif ($mode -eq 'row') {
    $y = [int]$parts[1]; $x0 = [int]$parts[2]; $x1 = [Math]::Min([int]$parts[3], $bmp.Width - 1)
    $prev = ''; $start = $x0
    for ($x = $x0; $x -le $x1; $x++) {
        $hex = Fmt (Get-Px $x $y)
        if ($hex -ne $prev) {
            if ($prev -ne '') { Write-Output ("ROW x " + $start + "-" + ($x - 1) + " " + $prev) }
            $prev = $hex; $start = $x
        }
    }
    Write-Output ("ROW x " + $start + "-" + $x1 + " " + $prev)
}
elseif ($mode -eq 'inkrows') {
    # rows in [y0,y1] at column band x0..x1 that contain any pixel differing from the majority bg
    $x0 = [int]$parts[1]; $x1 = [int]$parts[2]; $y0 = [int]$parts[3]; $y1 = [int]$parts[4]
    $runs = @()
    for ($y = $y0; $y -le $y1; $y++) {
        $ink = 0
        for ($x = $x0; $x -le $x1; $x += 2) {
            $p = Get-Px $x $y
            $d = [Math]::Abs($p[0] - 246) + [Math]::Abs($p[1] - 247) + [Math]::Abs($p[2] - 249)
            if ($d -gt 60) { $ink++ }
        }
        $runs += , @($y, $ink)
    }
    $inRun = $false; $start = 0
    foreach ($r in $runs) {
        if ($r[1] -gt 0 -and -not $inRun) { $inRun = $true; $start = $r[0] }
        if ($r[1] -eq 0 -and $inRun) { $inRun = $false; Write-Output ("INK y " + $start + "-" + ($r[0] - 1)) }
    }
    if ($inRun) { Write-Output ("INK y " + $start + "-" + $y1) }
}
elseif ($mode -eq 'bands') {
    $bx0 = [int]$parts[1]; $bx1 = [Math]::Min([int]$parts[2], $bmp.Width - 1)
    $by0 = [int]$parts[3]; $by1 = [Math]::Min([int]$parts[4], $bmp.Height - 1)
    $minrun = [int]$parts[5]
    $bg = Get-Px $bx0 $by0
    $runs = @()
    $cur = -1
    for ($x = $bx0; $x -le $bx1; $x++) {
        $hit = 0
        for ($y = $by0; $y -le $by1; $y += 3) {
            $p = Get-Px $x $y
            $d = [Math]::Abs($p[0] - $bg[0]) + [Math]::Abs($p[1] - $bg[1]) + [Math]::Abs($p[2] - $bg[2])
            if ($d -gt 48) { $hit++ }
        }
        $notBg = $hit -gt 2
        if ($notBg -and $cur -lt 0) { $cur = $x }
        if (-not $notBg -and $cur -ge 0) { $runs += , @($cur, ($x - 1)); $cur = -1 }
    }
    if ($cur -ge 0) { $runs += , @($cur, $bx1) }
    foreach ($r in $runs) {
        if ($r[1] - $r[0] + 1 -ge $minrun) {
            Write-Output ("BAND x " + $r[0] + "-" + $r[1] + " w=" + ($r[1] - $r[0] + 1))
        }
    }
}
elseif ($mode -eq 'vbands') {
    $bx0 = [int]$parts[1]; $bx1 = [Math]::Min([int]$parts[2], $bmp.Width - 1)
    $by0 = [int]$parts[3]; $by1 = [Math]::Min([int]$parts[4], $bmp.Height - 1)
    $minrun = [int]$parts[5]
    $bg = Get-Px $bx0 $by0
    $cur = -1
    for ($y = $by0; $y -le $by1; $y++) {
        $hit = 0
        for ($x = $bx0; $x -le $bx1; $x += 3) {
            $p = Get-Px $x $y
            $d = [Math]::Abs($p[0] - $bg[0]) + [Math]::Abs($p[1] - $bg[1]) + [Math]::Abs($p[2] - $bg[2])
            if ($d -gt 48) { $hit++ }
        }
        $notBg = $hit -gt 2
        if ($notBg -and $cur -lt 0) { $cur = $y }
        if (-not $notBg -and $cur -ge 0) {
            if ($y - 1 - $cur + 1 -ge $minrun) { Write-Output ("VBAND y " + $cur + "-" + ($y - 1) + " h=" + ($y - $cur)) }
            $cur = -1
        }
    }
}
else { Write-Output 'MODE? use point:x:y | col:x:y0:y1 | row:y:x0:x1 | inkrows:x0:x1:y0:y1 | bands:x0:x1:y0:y1:minrun | vbands:x0:x1:y0:y1:minrun' }
$bmp.Dispose()
