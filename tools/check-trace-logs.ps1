<#
.SYNOPSIS
    Sends one transfer through the Gateway with a chosen traceId and checks that every service it
    passes through has log lines with that traceId in Loki (docs/phase-13-log-aggregation-loki.md).

.DESCRIPTION
    Needs the dev stack running (docker compose up). Uses the realm's demo users ada and bob,
    creating a 1000.00 EUR account for either one if they have none. Exits 0 when every expected
    service is found in Loki, 1 otherwise. Windows PowerShell 5.1 compatible.
#>
param(
    [string]$Gateway = 'http://localhost:8080',
    [string]$Keycloak = 'http://localhost:8180',
    [string]$Loki = 'http://localhost:3100',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'

function Get-Token([string]$user) {
    $body = @{ grant_type = 'password'; client_id = 'showcase-ui'; username = $user; password = 'password' }
    (Invoke-RestMethod -Method Post -Uri "$Keycloak/realms/showcase/protocol/openid-connect/token" -Body $body).access_token
}

function Get-OrCreateAccount([string]$user, [string]$token) {
    $headers = @{ Authorization = "Bearer $token" }
    $accounts = @(Invoke-RestMethod -Uri "$Gateway/accounts/mine" -Headers $headers)
    if ($accounts.Count -gt 0) {
        return $accounts[0]
    }
    $body = @{ ownerName = $user; initialBalance = 1000.00; currency = 'EUR' } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$Gateway/accounts" -Headers $headers -ContentType 'application/json' -Body $body
}

function New-Hex([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    -join ($buffer | ForEach-Object { $_.ToString('x2') })
}

$adaToken = Get-Token 'ada'
$from = Get-OrCreateAccount 'ada' $adaToken
$to = Get-OrCreateAccount 'bob' (Get-Token 'bob')

$traceId = New-Hex 16
$spanId = New-Hex 8
$headers = @{
    Authorization     = "Bearer $adaToken"
    'Idempotency-Key' = [guid]::NewGuid().ToString()
    traceparent       = "00-$traceId-$spanId-01"
}
$body = @{ fromAccountId = $from.id; toAccountId = $to.id; amount = 1.00 } | ConvertTo-Json
$transfer = Invoke-RestMethod -Method Post -Uri "$Gateway/transfers" -Headers $headers -ContentType 'application/json' -Body $body
Write-Host "Transfer $($transfer.id) ($($transfer.status)), traceId $traceId"

$expected = @('gateway-service', 'transfer-service', 'account-service', 'fraud-service', 'notification-service')
if ($from.currency -ne $to.currency) {
    $expected += 'fx-service'
}

$query = [uri]::EscapeDataString("{service=~"".+""} |= ""$traceId""")
$found = @()
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $response = Invoke-RestMethod -Uri "$Loki/loki/api/v1/query_range?query=$query&since=5m&limit=1000"
    $found = @($response.data.result | ForEach-Object { $_.stream.service } | Sort-Object -Unique)
    $missing = @($expected | Where-Object { $found -notcontains $_ })
    if ($missing.Count -eq 0) {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

Write-Host "Found:   $($found -join ', ')"
if ($missing.Count -gt 0) {
    Write-Host "Missing: $($missing -join ', ')"
    exit 1
}
Write-Host 'Missing: none'
exit 0
