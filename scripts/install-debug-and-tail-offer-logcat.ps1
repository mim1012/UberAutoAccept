param(
    [string]$Device = "",
    [switch]$NoBuild,
    [switch]$NoTail
)

$ErrorActionPreference = "Stop"

function Get-AdbArgs([string[]]$ExtraArgs) {
    if ([string]::IsNullOrWhiteSpace($Device)) {
        return $ExtraArgs
    }
    return @('-s', $Device) + $ExtraArgs
}

function Invoke-Adb([string[]]$ExtraArgs) {
    & adb @(Get-AdbArgs $ExtraArgs)
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($ExtraArgs -join ' ')"
    }
}

Write-Host "[1/5] adb devices"
adb devices
if ($LASTEXITCODE -ne 0) { throw "adb devices failed" }

if (-not $NoBuild) {
    Write-Host "[2/5] assembleDebug"
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "assembleDebug failed"
    }
} else {
    Write-Host "[2/5] assembleDebug skipped"
}

$apkPath = Resolve-Path "app/build/outputs/apk/debug/app-debug.apk"
Write-Host "[3/5] install debug apk: $apkPath"
Invoke-Adb @('install', '-r', $apkPath)

Write-Host "[4/5] clear logcat + launch debug app + capture current UI dump"
Invoke-Adb @('logcat', '-c')
Invoke-Adb @('shell', 'am', 'start', '-n', 'com.uber.autoaccept.debug/com.uber.autoaccept.ui.MainActivity')
Start-Sleep -Seconds 2
New-Item -ItemType Directory -Force '.android-user' | Out-Null
$remoteDump = '/sdcard/uaa-live.xml'
Invoke-Adb @('shell', 'uiautomator', 'dump', $remoteDump)
Invoke-Adb @('pull', $remoteDump, '.android-user/uaa-live.xml')

[xml]$xml = Get-Content -Raw '.android-user/uaa-live.xml'
$tokens = @('offer', 'dispatch', 'pickup', 'dropoff', 'accept', 'upfront', 'pulse')
$resourceIds = @(
    Select-Xml -Xml $xml -XPath '//node[@resource-id]' |
        ForEach-Object { $_.Node.'resource-id' } |
        Where-Object { $_ } |
        ForEach-Object { ($_ -split ':id/')[-1] }
)
$matchingIds = $resourceIds |
    Where-Object { $id = $_; $tokens | Where-Object { $id -like "*$_*" } } |
    Sort-Object -Unique

Write-Host "Current UI offer-ish resource ids:"
if ($matchingIds.Count -eq 0) {
    Write-Host "  (none)"
} else {
    $matchingIds | ForEach-Object { Write-Host "  - $_" }
}

$tailArgs = @('logcat', '-v', 'time', 'UAA_OFFER:I', 'UAA:I', 'UberAccessibilityService:I', 'UberOfferParser:I', '*:S')
$tailPreview = if ([string]::IsNullOrWhiteSpace($Device)) {
    'adb ' + ($tailArgs -join ' ')
} else {
    'adb -s ' + $Device + ' ' + ($tailArgs -join ' ')
}
Write-Host "[5/5] offer logcat command: $tailPreview"

if (-not $NoTail) {
    & adb @(Get-AdbArgs $tailArgs)
} else {
    Write-Host "Tail skipped (-NoTail)."
}
