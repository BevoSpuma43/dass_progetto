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

$archivio = Invoke-WebRequest -UseBasicParsing -Uri ($base + 'api/demo/archivio') -TimeoutSec 10
$dati = $archivio.Content | ConvertFrom-Json
$attesi = 'pasta-01','pasta-02','pasta-03','pasta-04','controllo-01'
if ($dati.pazienti -lt 1 -or $dati.piatti -lt 2 -or $dati.feedback -lt 3 -or
    $dati.episodi -ne $dati.episodiId.Count -or @($attesi | Where-Object { $_ -notin $dati.episodiId }).Count -gt 0) {
    throw 'Archivio dimostrativo incompleto.'
}
Write-Output "HTTP 200  ${base}api/demo/archivio  $($archivio.Content)"
