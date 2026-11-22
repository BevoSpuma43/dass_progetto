#requires -Version 5.1
<#
Con DASS avviato, da una seconda PowerShell preparata nella radice:
  .\scripts\Verifica-Servizio.ps1
Esegue contro il server lo stesso protocollo del browser: scambio ECDH, buste cifrate, salvataggio e
lettura di un documento cifrato con una chiave che Payara non riceve, isolamento tra utente1 e utente2,
rifiuto delle buste ripetute e dell'analisi senza consenso. Il documento di prova viene eliminato.
#>
[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Porta = 8080)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not $env:DASS_PAYARA_JAR) { throw 'Eseguire prima Prepara-DASS.ps1.' }
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'target'
if (-not (Test-Path (Join-Path $target 'test-classes\it\unisi\dass\sicurezza\CollaudoServizio.class'))) {
    throw 'Compilare il progetto con mvn clean verify.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dir = Join-Path $target ('collaudo-servizio-' + [guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($dir)
try {
    $payara = [IO.Compression.ZipFile]::OpenRead($env:DASS_PAYARA_JAR)
    try {
        $entry = $payara.GetEntry('MICRO-INF/runtime/jakarta.json.jar')
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $dir 'jakarta.json.jar'))
    } finally { $payara.Dispose() }
    $cp = ((Join-Path $target 'classes'), (Join-Path $target 'test-classes'), (Join-Path $dir '*')) -join ';'
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $cp it.unisi.dass.sicurezza.CollaudoServizio "http://127.0.0.1:$Porta/dass/"
    if ($LASTEXITCODE -ne 0) { throw 'Collaudo del servizio fallito.' }
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force
}
