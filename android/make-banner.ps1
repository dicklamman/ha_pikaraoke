# The banner is the picture a television shows for the app on its home
# page. It is 320x180 on an xhdpi screen, which is 16:9, so the square app
# icon cannot be used for it: the television crops it, and the microphone
# ends up cut in half.
#
# The banner is drawn from the app icon itself, so the two cannot drift
# apart: the microphone is the same artwork, on the same background, with
# the name of the app beside it.
#
# Run this from the android directory:  ./make-banner.ps1
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$res = Join-Path $here 'app/src/main/res'
$icon = Join-Path $res 'mipmap-xxxhdpi/ic_launcher.png'
$foreground = Join-Path $res 'drawable/ic_launcher_foreground.png'

# The colours of the app, taken from the icon.
$background = [System.Drawing.Color]::FromArgb(255, 0x14, 0x14, 0x18)
$text = [System.Drawing.Color]::FromArgb(255, 0xFF, 0xFF, 0xFF)

# A banner is wanted at every density a television may ask for.
$sizes = @{
    'mdpi'    = @(320, 180)
    'hdpi'    = @(480, 270)
    'xhdpi'   = @(640, 360)
    'xxhdpi'  = @(960, 540)
    'xxxhdpi' = @(1280, 720)
}

$iconBitmap = [System.Drawing.Bitmap]::FromFile($icon)
$foregroundBitmap = [System.Drawing.Bitmap]::FromFile($foreground)

foreach ($density in $sizes.Keys) {
    $width = $sizes[$density][0]
    $height = $sizes[$density][1]

    $banner = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($banner)
    $graphics.SmoothingMode = 'AntiAlias'
    $graphics.InterpolationMode = 'HighQualityBicubic'
    $graphics.TextRenderingHint = 'AntiAlias'
    $graphics.Clear($background)

    # The microphone of the app icon, taken from the adaptive foreground so
    # that the banner shows the same artwork the icon does.
    $micSize = [int]($height * 0.62)
    $micX = [int]($width * 0.10)
    $micY = [int](($height - $micSize) / 2)
    $graphics.DrawImage($foregroundBitmap, $micX, $micY, $micSize, $micSize)

    # The name of the app, beside the microphone.
    $fontSize = [float]($height * 0.22)
    $font = New-Object System.Drawing.Font('Arial', $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $brush = New-Object System.Drawing.SolidBrush($text)
    $textX = $micX + $micSize + [int]($width * 0.045)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Near
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $layout = New-Object System.Drawing.RectangleF(
        $textX, 0, ($width - $textX), $height)
    $graphics.DrawString('PiKaraoke', $font, $brush, $layout, $format)

    $graphics.Dispose()

    $folder = Join-Path $res "mipmap-$density"
    if (-not (Test-Path $folder)) {
        New-Item -ItemType Directory $folder | Out-Null
    }
    $path = Join-Path $folder 'ic_banner.png'
    $banner.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $banner.Dispose()

    Write-Output "wrote $path ($width x $height)"
}

$iconBitmap.Dispose()
$foregroundBitmap.Dispose()
