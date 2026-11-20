#requires -Version 5.1
<#
.SYNOPSIS
    Prepara le dipendenze di DASS su Windows e compila il progetto.
.EXAMPLE
    .\Installa-DASS.ps1
.EXAMPLE
    .\Installa-DASS.ps1 -Avvia -Porta 8081
#>
[CmdletBinding()]
param(
    [switch]$Avvia,
    [ValidateRange(1, 65535)][int]$Porta = 8080
)

$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'Prepara-DASS.ps1')

# Payara Micro distribuisce H2 insieme al suo runtime.
$payara = [IO.Compression.ZipFile]::OpenRead($env:DASS_PAYARA_JAR)
try {
    if (-not $payara.GetEntry('MICRO-INF/runtime/h2.jar')) {
        throw 'Il pacchetto Payara Micro non contiene il driver H2 atteso.'
    }
} finally {
    $payara.Dispose()
}
Write-Host 'H2 incluso in Payara Micro.'

Push-Location -LiteralPath $PSScriptRoot
try {
    & mvn.cmd --batch-mode --no-transfer-progress verify
    if ($LASTEXITCODE -ne 0) {
        throw "Compilazione Maven fallita (codice $LASTEXITCODE). Controllare il messaggio precedente."
    }
    if (-not (Test-Path -LiteralPath 'target\dass.war' -PathType Leaf)) {
        throw 'Maven non ha prodotto target\dass.war.'
    }
    Write-Host 'DASS pronto. File applicativo: target\dass.war'

    if ($Avvia) {
        Write-Host "Diario locale: http://127.0.0.1:$Porta/dass/"
        & (Join-Path $PSScriptRoot 'scripts\Avvia-DASS.ps1') -Porta $Porta
    } else {
        $launcher = (Join-Path $PSScriptRoot 'scripts\Avvia-DASS.ps1').Replace("'", "''")
        Write-Host "Per avviare DASS in questa finestra: & '$launcher' -Porta $Porta"
    }
} finally {
    Pop-Location
}
