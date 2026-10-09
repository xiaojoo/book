# ASCII only. Crops a rect from an image and un-dims it (the reference screenshots sit under a scrim).
# usage: powershell -NoProfile -File tools/crop-bright.ps1 -Image in.jpg -X 64 -Y 622 -W 232 -H 318 -Gain 2.52 -Out out.png
param(
    [Parameter(Mandatory = $true)][string]$Image,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$W,
    [Parameter(Mandatory = $true)][int]$H,
    [double]$Gain = 2.52,
    [string]$Out = 'out.png'
)

Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Image))
$dst = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $W, $H
for ($j = 0; $j -lt $H; $j++) {
    for ($i = 0; $i -lt $W; $i++) {
        $p = $src.GetPixel($X + $i, $Y + $j)
        $r = [Math]::Min(255, [int]($p.R * $Gain))
        $g = [Math]::Min(255, [int]($p.G * $Gain))
        $b = [Math]::Min(255, [int]($p.B * $Gain))
        $dst.SetPixel($i, $j, [System.Drawing.Color]::FromArgb(255, $r, $g, $b))
    }
}
$src.Dispose()
$dst.Save((Join-Path (Get-Location) $Out), [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose()
Write-Output ("SAVED " + $Out + " " + $W + "x" + $H)
