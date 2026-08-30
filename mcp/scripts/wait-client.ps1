param(
    [string]$ControlUrl = "http://127.0.0.1:17343",
    [string]$ScriptId = "",
    [int]$RunId = 0,
    [switch]$StartScript,
    [string]$InputsJson = "{}",
    [int]$PollMilliseconds = 2000,
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$lastLine = ""
$observedRun = $false
$minimumRunId = 0

function Invoke-GenericClientRpc {
    param(
        [string]$Method,
        [object]$Params
    )
    $json = @{
        method = $Method
        params = $Params
    } | ConvertTo-Json -Compress
    $body = [System.Text.Encoding]::UTF8.GetBytes($json)
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$ControlUrl/rpc" `
        -ContentType "application/json; charset=utf-8" `
        -Body $body `
        -TimeoutSec 10
    if (-not $response.ok) {
        throw $response.error
    }
    return $response.result
}

function Invoke-GenericClientStatus {
    return Invoke-GenericClientRpc "status" ([pscustomobject]@{})
}

if ($StartScript) {
    if ($ScriptId -eq "") {
        throw "ScriptId is required when StartScript is set."
    }
    $beforeStart = Invoke-GenericClientStatus
    if ($RunId -eq 0) {
        $minimumRunId = [int]$beforeStart.lua.run_id + 1
    }
    $inputs = $InputsJson | ConvertFrom-Json
    $started = Invoke-GenericClientRpc "scripts.run" ([pscustomobject]@{
        id = $ScriptId
        inputs = $inputs
    })
    Write-Output ([ordered]@{
        at = [DateTimeOffset]::Now.ToString("o")
        started = [string]$started
        script = $ScriptId
    } | ConvertTo-Json -Compress)
}

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    try {
        $status = Invoke-GenericClientStatus
        $active = $status.lua.active
        $activeScript = [string]$status.lua.active_script
        $activeRunId = [int]$status.lua.run_id
        $scriptStatus = [string]$status.lua.script_status
        $randomEvent = $status.random_event
        $randomAttention = $null -ne $randomEvent -and [bool]$randomEvent.attention_required
        $breakRemainingMillis = [long]$status.behavior.break_remaining_millis
        $breakRemainingMinutes = if ($breakRemainingMillis -le 0) {
            0
        } else {
            [long][Math]::Ceiling($breakRemainingMillis / 60000.0)
        }

        $runMatches = if ($RunId -ne 0) {
            $activeRunId -eq $RunId
        } elseif ($minimumRunId -ne 0) {
            $activeRunId -ge $minimumRunId
        } else {
            $true
        }

        if (($ScriptId -eq "" -or $activeScript -eq $ScriptId) -and $runMatches) {
            $observedRun = $true
        }

        $phase = ""
        foreach ($row in @($active.overlay)) {
            if ($row.label -eq "Phase") {
                $phase = [string]$row.value
                break
            }
        }

        $state = [ordered]@{
            game = [string]$status.game_state
            script = $activeScript
            run_id = $activeRunId
            status = $scriptStatus
            phase = $phase
            x = $status.player.world.x
            y = $status.player.world.y
            hp = if ($null -eq $status.player.current_hitpoints) {
                ""
            } else {
                "$($status.player.current_hitpoints)/$($status.player.max_hitpoints)"
            }
            break = [string]$status.behavior.state
            break_minutes = $breakRemainingMinutes
            safety = [string]$status.safety.last_event
            random_event = [string]$randomEvent.state
            random_attention = $randomAttention
            random_npc = [string]$randomEvent.npc_name
            random_npc_id = $randomEvent.npc_id
            random_solver = [string]$randomEvent.solver_script
            last = [string]$status.last_status
            result_status = [string]$active.result.status
            result_phase = [string]$active.result.phase
        }
        $signature = $state | ConvertTo-Json -Compress

        if ($signature -ne $lastLine) {
            $event = [ordered]@{ at = [DateTimeOffset]::Now.ToString("o") }
            foreach ($key in $state.Keys) {
                $event[$key] = $state[$key]
            }
            Write-Output ($event | ConvertTo-Json -Compress)
            $lastLine = $signature
        }

        if ($randomAttention) {
            exit 3
        }

        $requestedRunStillVisible =
            ($ScriptId -eq "" -or $activeScript -eq $ScriptId) -and
            $runMatches
        $requestedRunDisappeared = $observedRun -and
            $scriptStatus -eq "IDLE" -and $activeScript -eq "none"
        if (($observedRun -and $requestedRunStillVisible -and
            $scriptStatus -in @("COMPLETED", "FAULTED", "IDLE")) -or
            $requestedRunDisappeared) {
            exit 0
        }
    }
    catch {
        $signature = "rpc_error:" + $_.Exception.Message
        if ($signature -ne $lastLine) {
            Write-Output ([ordered]@{
                at = [DateTimeOffset]::Now.ToString("o")
                rpc_error = $_.Exception.Message
            } | ConvertTo-Json -Compress)
            $lastLine = $signature
        }
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}

Write-Error "Timed out waiting for GenericClient script state."
exit 2
