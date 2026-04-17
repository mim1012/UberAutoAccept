param(
    [string]$Device = "",
    [int]$TargetOffers = 5,
    [int]$PollSeconds = 8
)

$ts = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$outDir = Join-Path '.omx/logs' "offer-monitor-$ts"
New-Item -ItemType Directory -Force $outDir | Out-Null
$stdout = Join-Path $outDir 'monitor.stdout.log'
$stderr = Join-Path $outDir 'monitor.stderr.log'
$args = @('-ExecutionPolicy','Bypass','-File',(Resolve-Path '.\scripts\monitor-live-offers.ps1').Path,'-TargetOffers',$TargetOffers,'-PollSeconds',$PollSeconds,'-OutputDir',$outDir)
if (-not [string]::IsNullOrWhiteSpace($Device)) { $args += @('-Device',$Device) }
$proc = Start-Process -FilePath 'powershell' -ArgumentList $args -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
$status = [ordered]@{
    started_at_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'
    pid = $proc.Id
    output_dir = (Resolve-Path $outDir).Path
    stdout_log = (Resolve-Path $stdout).Path
    stderr_log = (Resolve-Path $stderr).Path
}
$status | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $outDir 'launcher.json')
Write-Host "PID=$($proc.Id)"
Write-Host "OUTDIR=$((Resolve-Path $outDir).Path)"
