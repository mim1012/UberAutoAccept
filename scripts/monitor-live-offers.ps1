param(
    [string]$Device = "",
    [int]$TargetOffers = 5,
    [int]$PollSeconds = 8,
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Get-AdbArgs([string[]]$ExtraArgs) {
    if ([string]::IsNullOrWhiteSpace($Device)) { return $ExtraArgs }
    return @('-s', $Device) + $ExtraArgs
}

function Invoke-AdbCapture([string[]]$ExtraArgs) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "adb"
    $psi.Arguments = ((Get-AdbArgs $ExtraArgs) | ForEach-Object {
        if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    $combined = @($stdout, $stderr) -ne '' -join "`n"
    return [pscustomobject]@{ Code = $proc.ExitCode; Output = $combined.Trim() }
}

function Invoke-Adb([string[]]$ExtraArgs) {
    $result = Invoke-AdbCapture $ExtraArgs
    if ($result.Code -ne 0) {
        throw "adb command failed: $($ExtraArgs -join ' ')`n$result"
    }
    return $result.Output
}

function Write-Json($Path, $Object) {
    $Object | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $Path
}

function Extract-Values([string]$XmlRaw, [string]$AttributeName) {
    return [regex]::Matches($XmlRaw, ($AttributeName + '="([^"]*)"')) | ForEach-Object { $_.Groups[1].Value }
}

function Decode-Text([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    return $Value.Replace('&quot;', '"').Replace('&amp;', '&').Replace('&lt;', '<').Replace('&gt;', '>').Replace('&apos;', "'")
}

function Test-LooksLikeAddress([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return $false }
    $candidate = $Text.Trim()
    if ($candidate.Length -lt 8 -or $candidate.EndsWith('쪽')) { return $false }
    $termHits = ([regex]::Matches($candidate, '시|구|동|로|길|역|터미널')).Count
    return $termHits -ge 2 -or $candidate.Contains(',')
}

function Get-OfferTextCluster([string[]]$Texts, [string[]]$ResourceIds) {
    $normalized = @($Texts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
    if ($normalized.Count -eq 0) {
        return [pscustomobject]@{
            IsLikelyOffer = $false
            Reason = 'no_text_content'
            TitleText = ''
            TripDurationText = ''
            PickupEtaText = ''
            PickupAddress = ''
            DropoffAddress = ''
            DirectionText = ''
            AcceptText = ''
            AddressCandidates = @()
            BlacklistTextHit = ''
        }
    }

    $blacklistHit = $normalized | Where-Object { $_ -match '지금은 요청이 없습니다|더 많은 요청이 들어오면 알려드리겠습니다|운행 리스트' } | Select-Object -First 1
    if ($blacklistHit) {
        return [pscustomobject]@{
            IsLikelyOffer = $false
            Reason = 'blacklist_text_present'
            TitleText = ''
            TripDurationText = ''
            PickupEtaText = ''
            PickupAddress = ''
            DropoffAddress = ''
            DirectionText = ''
            AcceptText = ''
            AddressCandidates = @()
            BlacklistTextHit = $blacklistHit
        }
    }

    $titleText = $normalized | Where-Object { $_ -match '가맹 전용 콜|일반 콜|XL' } | Select-Object -First 1
    $tripDurationText = $normalized | Where-Object { $_ -match '(?:\d+\s*시간\s*)?\d+\s*분\s*운행' } | Select-Object -First 1
    $pickupEtaText = $normalized | Where-Object { $_ -match '\d+\s*분\s*\([\d.]+\s*km\)\s*남음' } | Select-Object -First 1
    $acceptText = $normalized | Where-Object { $_ -match '콜 수락|수락|Accept|ACCEPT|accept|확인' } | Select-Object -First 1
    $directionText = $normalized | Where-Object { $_ -match '^[동서남북]{1,2}쪽$' -or $_.EndsWith('쪽') } | Select-Object -First 1
    $addressCandidates = @($normalized | Where-Object { Test-LooksLikeAddress $_ })
    $hasOfferishMapStructure = @($ResourceIds | Where-Object { $_ -like 'map_marker*' -or $_ -eq 'rxmap' -or $_ -eq 'map' }).Count -gt 0
    $isLikelyOffer = ($acceptText) -and ($tripDurationText) -and ($pickupEtaText) -and ($addressCandidates.Count -ge 2) -and $hasOfferishMapStructure

    return [pscustomobject]@{
        IsLikelyOffer = [bool]$isLikelyOffer
        Reason = $(if ($isLikelyOffer) { 'accept_trip_eta_and_two_addresses' } else { 'missing_offer_text_cluster' })
        TitleText = [string]($titleText ?? '')
        TripDurationText = [string]($tripDurationText ?? '')
        PickupEtaText = [string]($pickupEtaText ?? '')
        PickupAddress = [string]($addressCandidates | Select-Object -First 1)
        DropoffAddress = [string]($addressCandidates | Select-Object -Skip 1 -First 1)
        DirectionText = [string]($directionText ?? '')
        AcceptText = [string]($acceptText ?? '')
        AddressCandidates = $addressCandidates
        BlacklistTextHit = ''
    }
}

function Get-UiSnapshot([string]$LocalXmlPath) {
    $raw = Get-Content -Raw $LocalXmlPath
    $resourceIds = Extract-Values $raw 'resource-id' |
        Where-Object { $_ } |
        ForEach-Object { ($_ -split ':id/')[-1] }

    $classes = Extract-Values $raw 'class' |
        Where-Object { $_ } |
        ForEach-Object { ($_ -split '\.')[-1] }

    $texts = Extract-Values $raw 'text' | ForEach-Object { Decode-Text $_ }
    $descs = Extract-Values $raw 'content-desc' | ForEach-Object { Decode-Text $_ }
    $allText = @($texts + $descs) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    $strongMarkers = @(
        'ub__upfront_offer_view_v2',
        'pulse_view',
        'dispatch_view',
        'dispatch_container',
        'offer_container',
        'upfront_offer_view_v2_workflow'
    ) | Where-Object { $resourceIds -contains $_ }

    $blacklistIds = @(
        'ub__driver_job_offers_pill_title',
        'ub__driver_job_offers_pill_badge',
        'driver_offers_job_board_content_container',
        'driver_offers_job_board_toolbar'
    ) | Where-Object { $resourceIds -contains $_ }

    $offerishTokens = @('offer','dispatch','pickup','dropoff','accept','upfront','pulse','job_board','job_offers')
    $offerishIds = $resourceIds | Where-Object {
        $id = $_
        $offerishTokens | Where-Object { $id -like "*$_*" }
    } | Select-Object -Unique

    $pickupIds = @('uda_details_pickup_address_text_view','pick_up_address') | Where-Object { $resourceIds -contains $_ }
    $dropoffIds = @('uda_details_dropoff_address_text_view','drop_off_address') | Where-Object { $resourceIds -contains $_ }
    $acceptIds = @('upfront_offer_configurable_details_accept_button','uda_details_accept_button','upfront_offer_configurable_details_auditable_accept_button') | Where-Object { $resourceIds -contains $_ }

    $timeLikeTexts = $allText | Where-Object { $_ -match '\d+\s*분|\d+\s*min|ETA|예정|도착|남음|pickup|trip' } | Select-Object -Unique
    $addrLikeTexts = $allText | Where-Object { $_ -match '시|구|동|로|길|역|터미널' } | Select-Object -First 10
    $acceptTexts = $allText | Where-Object { $_ -match '콜 수락|수락|Accept|ACCEPT|확인' } | Select-Object -Unique
    $textCluster = Get-OfferTextCluster $allText $resourceIds

    $topIds = $resourceIds | Group-Object | Sort-Object Count -Descending | Select-Object -First 12 | ForEach-Object { "$($_.Name):$($_.Count)" }
    $topClasses = $classes | Group-Object | Sort-Object Count -Descending | Select-Object -First 8 | ForEach-Object { "$($_.Name):$($_.Count)" }

    $sampleType = 'UNKNOWN'
    $reason = 'candidate_without_confirmation'
    if ($strongMarkers.Count -gt 0 -or (($pickupIds.Count -gt 0) -and ($dropoffIds.Count -gt 0) -and (($acceptIds.Count -gt 0) -or ($acceptTexts.Count -gt 0)))) {
        $sampleType = 'REAL_OFFER'
        $reason = 'strong_marker_or_pickup_dropoff_accept_cluster'
    } elseif ($textCluster.IsLikelyOffer) {
        $sampleType = 'REAL_OFFER'
        $reason = $textCluster.Reason
    } elseif ($blacklistIds -contains 'driver_offers_job_board_content_container' -or $blacklistIds -contains 'driver_offers_job_board_toolbar') {
        $sampleType = 'NON_OFFER_JOB_BOARD'
        $reason = 'job_board_blacklist_ids_present'
    } elseif ($blacklistIds -contains 'ub__driver_job_offers_pill_title' -or $blacklistIds -contains 'ub__driver_job_offers_pill_badge') {
        $sampleType = 'NON_OFFER_QUEUE'
        $reason = 'queue_pill_blacklist_ids_present'
    } elseif (($allText | Where-Object { $_ -match '지금은 요청이 없습니다|더 많은 요청이 들어오면 알려드리겠습니다|운행 리스트' }).Count -gt 0) {
        $sampleType = 'NON_OFFER_EMPTY'
        $reason = 'empty_state_text_present'
    }

    return [pscustomobject]@{
        SampleType = $sampleType
        Reason = $reason
        StrongMarkers = @($strongMarkers)
        BlacklistIds = @($blacklistIds)
        OfferishIds = @($offerishIds)
        PickupIds = @($pickupIds)
        DropoffIds = @($dropoffIds)
        AcceptIds = @($acceptIds)
        AcceptTexts = @($acceptTexts)
        TimeLikeTexts = @($timeLikeTexts)
        AddrLikeTexts = @($addrLikeTexts)
        TextCluster = $textCluster
        TopIds = @($topIds)
        TopClasses = @($topClasses)
        Fingerprint = ((@($strongMarkers) + @($blacklistIds) + @($pickupIds) + @($dropoffIds) + @($acceptIds) + @($acceptTexts) + @($timeLikeTexts) + @($topIds)) -join '|')
    }
}

$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path '.omx/logs' "offer-monitor-$timestamp"
}
New-Item -ItemType Directory -Force $OutputDir | Out-Null
$rawLogPath = Join-Path $OutputDir 'logcat-raw.txt'
$samplesCsvPath = Join-Path $OutputDir 'offer-samples.csv'
$summaryPath = Join-Path $OutputDir 'status.json'
$eventsDir = Join-Path $OutputDir 'events'
New-Item -ItemType Directory -Force $eventsDir | Out-Null

"sample_id,sample_type,event_ts,root_class,strong_markers,offerish_ids,blacklist_ids,accept_ids,accept_texts,pickup_ids,dropoff_ids,time_texts,addr_texts,cluster_title,cluster_trip,cluster_eta,cluster_pickup,cluster_dropoff,cluster_direction,top_ids,top_classes,classification_reason,counts_toward_5,xml_path,screenshot_path" | Set-Content -Encoding utf8 $samplesCsvPath

$deviceLabel = if ([string]::IsNullOrWhiteSpace($Device)) { 'default' } else { $Device }

try { Invoke-Adb @('shell','pm','grant','com.uber.autoaccept.debug','android.permission.READ_PHONE_STATE') | Out-Null } catch {}
try { Invoke-Adb @('shell','pm','grant','com.uber.autoaccept.debug','android.permission.READ_PHONE_NUMBERS') | Out-Null } catch {}
try { Invoke-Adb @('shell','pm','grant','com.uber.autoaccept.debug','android.permission.POST_NOTIFICATIONS') | Out-Null } catch {}
try { Invoke-Adb @('shell','settings','delete','secure','enabled_accessibility_services') | Out-Null } catch {}
try { Invoke-Adb @('shell','settings','put','secure','enabled_accessibility_services','com.uber.autoaccept.debug/com.uber.autoaccept.service.UberAccessibilityService:com.teamviewer.quicksupport.addon.universal/com.teamviewer.quicksupport.addon.universal.TvAccessibilityService') | Out-Null } catch {}
try { Invoke-Adb @('shell','settings','put','secure','accessibility_enabled','1') | Out-Null } catch {}
try { Invoke-Adb @('shell','am','force-stop','com.uber.autoaccept') | Out-Null } catch {}
try { Invoke-Adb @('shell','svc','power','stayon','true') | Out-Null } catch {}
try { Invoke-Adb @('shell','wm','dismiss-keyguard') | Out-Null } catch {}
try { Invoke-Adb @('logcat','-c') | Out-Null } catch {}

$logcatProc = Start-Process -FilePath 'adb' -ArgumentList (Get-AdbArgs @('logcat','-v','time','UAA_OFFER:I','UAA:I','UberAccessibilityService:I','UberOfferParser:I','*:S')) -RedirectStandardOutput $rawLogPath -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 1
Invoke-Adb @('shell','am','start','-n','com.uber.autoaccept.debug/com.uber.autoaccept.ui.MainActivity') | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
Start-Sleep -Seconds 1
Invoke-Adb @('shell','am','start','-n','com.ubercab.driver/com.ubercab.carbon.core.CarbonActivity') | Out-Null

$seenFingerprints = [System.Collections.Generic.HashSet[string]]::new()
$realOfferCount = 0
$sampleId = 0
$lastHeartbeat = Get-Date

while ($realOfferCount -lt $TargetOffers) {
    $nowIso = (Get-Date).ToString('s')
    $remoteXml = '/sdcard/uaa-monitor.xml'
    $localXml = Join-Path $OutputDir 'latest.xml'

    $dump = Invoke-AdbCapture @('shell','uiautomator','dump',$remoteXml)
    if ($dump.Code -eq 0) {
        $pull = Invoke-AdbCapture @('pull',$remoteXml,$localXml)
        if ($pull.Code -eq 0 -and (Test-Path $localXml)) {
            $snapshot = Get-UiSnapshot $localXml
            $fingerprint = "$($snapshot.SampleType)|$($snapshot.Fingerprint)"
            if (-not [string]::IsNullOrWhiteSpace($snapshot.Fingerprint) -and $seenFingerprints.Add($fingerprint)) {
                $sampleId += 1
                $sampleDir = Join-Path $eventsDir ('sample-' + $sampleId.ToString('D3'))
                New-Item -ItemType Directory -Force $sampleDir | Out-Null
                $xmlCopy = Join-Path $sampleDir 'ui.xml'
                Copy-Item $localXml $xmlCopy -Force
                $remotePng = '/sdcard/uaa-monitor.png'
                $localPng = Join-Path $sampleDir 'screen.png'
                $screencap = Invoke-AdbCapture @('shell','screencap','-p',$remotePng)
                if ($screencap.Code -eq 0) {
                    $pullPng = Invoke-AdbCapture @('pull',$remotePng,$localPng)
                    if ($pullPng.Code -ne 0) { $localPng = '' }
                } else {
                    $localPng = ''
                }

                if ($snapshot.SampleType -eq 'REAL_OFFER') { $realOfferCount += 1 }

                $rootClass = ($snapshot.TopClasses | Select-Object -First 1)
                $csvLine = @(
                    $sampleId,
                    $snapshot.SampleType,
                    $nowIso,
                    ('"' + ($rootClass -replace '"','""') + '"'),
                    ('"' + (($snapshot.StrongMarkers -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.OfferishIds -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.BlacklistIds -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.AcceptIds -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.AcceptTexts -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.PickupIds -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.DropoffIds -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.TimeLikeTexts -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.AddrLikeTexts -join ';') -replace '"','""') + '"'),
                    ('"' + ($snapshot.TextCluster.TitleText -replace '"','""') + '"'),
                    ('"' + ($snapshot.TextCluster.TripDurationText -replace '"','""') + '"'),
                    ('"' + ($snapshot.TextCluster.PickupEtaText -replace '"','""') + '"'),
                    ('"' + ($snapshot.TextCluster.PickupAddress -replace '"','""') + '"'),
                    ('"' + ($snapshot.TextCluster.DropoffAddress -replace '"','""') + '"'),
                    ('"' + ($snapshot.TextCluster.DirectionText -replace '"','""') + '"'),
                    ('"' + (($snapshot.TopIds -join ';') -replace '"','""') + '"'),
                    ('"' + (($snapshot.TopClasses -join ';') -replace '"','""') + '"'),
                    ('"' + ($snapshot.Reason -replace '"','""') + '"'),
                    ($(if ($snapshot.SampleType -eq 'REAL_OFFER') { 'yes' } else { 'no' })),
                    ('"' + ($xmlCopy -replace '"','""') + '"'),
                    ('"' + ($localPng -replace '"','""') + '"')
                ) -join ','
                Add-Content -Encoding utf8 $samplesCsvPath $csvLine
            }
        }
    }

    $status = [ordered]@{
        started_at_utc = $timestamp
        device = $deviceLabel
        target_offers = $TargetOffers
        real_offer_count = $realOfferCount
        samples_recorded = $sampleId
        last_poll_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'
        raw_log_path = (Resolve-Path $rawLogPath).Path
        samples_csv_path = (Resolve-Path $samplesCsvPath).Path
        logcat_pid = $logcatProc.Id
        monitoring = ($realOfferCount -lt $TargetOffers)
    }
    Write-Json $summaryPath $status

    if (((Get-Date) - $lastHeartbeat).TotalMinutes -ge 5) {
        try { Invoke-Adb @('shell','am','start','-n','com.ubercab.driver/com.ubercab.carbon.core.CarbonActivity') | Out-Null } catch {}
        $lastHeartbeat = Get-Date
    }

    Start-Sleep -Seconds $PollSeconds
}

$status = Get-Content -Raw $summaryPath | ConvertFrom-Json
$status.monitoring = $false
$status.completed_at_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'
Write-Json $summaryPath $status
if (-not $logcatProc.HasExited) { Stop-Process -Id $logcatProc.Id -Force }
