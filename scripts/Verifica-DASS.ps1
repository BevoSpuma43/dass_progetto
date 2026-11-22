#requires -Version 5.1
[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Porta = 8080)

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Porta/dass/"
$pagina = Invoke-WebRequest -UseBasicParsing -Uri $base -TimeoutSec 10
if ($pagina.StatusCode -ne 200 -or $pagina.Content -notmatch '<title>DASS') {
    throw 'La pagina iniziale DASS non corrisponde a quella attesa.'
}
Write-Output "HTTP 200  $base"

$api = Invoke-WebRequest -UseBasicParsing -Uri ($base + 'api/stato') -TimeoutSec 10
$json = $api.Content | ConvertFrom-Json
if ($api.StatusCode -ne 200 -or $api.Headers['Content-Type'] -notmatch '^application/json\b' -or $json.stato -cne 'ok') {
    throw 'Risposta di stato inattesa.'
}
Write-Output "HTTP 200  ${base}api/stato  $($api.Content)"

$profili = Invoke-WebRequest -UseBasicParsing -Uri ($base + 'api/profili') -TimeoutSec 10
$elenco = $profili.Content | ConvertFrom-Json
$id = @($elenco.id)
if ($profili.StatusCode -ne 200 -or $id.Count -ne 2 -or 'utente1' -notin $id -or 'utente2' -notin $id -or $profili.Content -match 'paz-demo') {
    throw 'Profili inattesi: servono utente1 e utente2, senza paz-demo.'
}
Write-Output "HTTP 200  ${base}api/profili  $($profili.Content)"

# Senza scambio di chiavi i documenti del diario non sono raggiungibili.
try {
    $null = Invoke-WebRequest -UseBasicParsing -Uri ($base + 'api/profili/utente1/documenti?tipo=pasto') -TimeoutSec 10
    throw 'I documenti del diario rispondono senza scambio di chiavi.'
} catch [System.Net.WebException] {
    if ([int]$_.Exception.Response.StatusCode -ne 401) { throw }
}
Write-Output "HTTP 401  ${base}api/profili/utente1/documenti  (scambio di chiavi richiesto)"
