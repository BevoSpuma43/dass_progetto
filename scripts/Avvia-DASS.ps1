#requires -Version 5.1
[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Porta = 8080)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$war = Join-Path $root 'target\dass.war'
if (-not $env:JAVA_HOME -or -not $env:DASS_PAYARA_JAR) {
    throw 'Eseguire Prepara-DASS.ps1 in questa finestra PowerShell.'
}
if (-not (Test-Path -LiteralPath $war -PathType Leaf)) {
    throw 'Manca target\dass.war. Dalla radice del progetto eseguire mvn clean verify.'
}

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$preboot = Join-Path $PSScriptRoot 'preboot.asadmin'
$chiaveSalvata = Join-Path $env:LOCALAPPDATA 'DASS\anthropic-key.dpapi'
$precedente = $env:ANTHROPIC_API_KEY
$dataDir = $env:DASS_DATA_DIR
if (-not $dataDir) {
    $dataDir = 'var\dati'
    # Un archivio della versione precedente viene spostato qui: all'avvio Payara rimuove paz-demo.
    $vecchio = Join-Path $root 'var\demo'
    $nuovo = Join-Path $root $dataDir
    if ((Test-Path -LiteralPath (Join-Path $vecchio 'dass.mv.db')) -and -not (Test-Path -LiteralPath (Join-Path $nuovo 'dass.mv.db'))) {
        $null = [IO.Directory]::CreateDirectory($nuovo)
        Get-ChildItem -LiteralPath $vecchio -Filter 'dass.*.db' | Move-Item -Destination $nuovo
        if (-not (Get-ChildItem -LiteralPath $vecchio -Force)) { Remove-Item -LiteralPath $vecchio }
        Write-Host "Archivio spostato da var\demo a $dataDir."
    }
}
if (-not [IO.Path]::IsPathRooted($dataDir)) { $dataDir = Join-Path $root $dataDir }
$dataDir = [IO.Directory]::CreateDirectory($dataDir).FullName
try {
    if ([IO.File]::Exists($chiaveSalvata)) {
        try {
            $protetta = ConvertTo-SecureString -String ([IO.File]::ReadAllText($chiaveSalvata))
            try {
                $env:ANTHROPIC_API_KEY = [System.Net.NetworkCredential]::new('', $protetta).Password
            } finally { $protetta.Dispose() }
        } catch {
            throw 'Chiave salvata illeggibile. Eseguire scripts\Salva-ChiaveClaude.ps1 e riprovare.'
        }
    }
    Write-Host "Archivio DASS: $dataDir\dass.mv.db"
    Push-Location -LiteralPath $dataDir
    try {
        # I certificati di Windows valgono anche dove un antivirus ispeziona le connessioni HTTPS.
        & $java '-Djavax.net.ssl.trustStoreType=Windows-ROOT' -jar $env:DASS_PAYARA_JAR --noCluster --disablePhoneHome --prebootcommandfile $preboot --port $Porta --contextroot /dass --deploy $war
        if ($LASTEXITCODE -ne 0) { throw "Payara terminato con codice $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    $env:ANTHROPIC_API_KEY = $precedente
}
