#requires -Version 5.1
<#
Eseguire dalla radice in una PowerShell preparata, dopo mvn clean verify:
  .\scripts\Verifica-Sicurezza.ps1
Collauda senza server lo scambio di chiavi ECDH P-256, la derivazione HKDF-SHA-256 (vettore della
RFC 5869) e il canale cifrato: buste valide, ripetute, alterate o scadute.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not $env:DASS_PAYARA_JAR) { throw 'Eseguire prima Prepara-DASS.ps1.' }
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'target'
if (-not (Test-Path (Join-Path $target 'test-classes\it\unisi\dass\sicurezza\CollaudoSicurezza.class'))) {
    throw 'Compilare il progetto con mvn clean verify.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dir = Join-Path $target ('collaudo-sicurezza-' + [guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($dir)
try {
    $payara = [IO.Compression.ZipFile]::OpenRead($env:DASS_PAYARA_JAR)
    try {
        foreach ($jar in 'jakarta.json','jakarta.servlet-api') {
            $entry = $payara.GetEntry("MICRO-INF/runtime/$jar.jar")
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $dir "$jar.jar"))
        }
    } finally { $payara.Dispose() }
    $cp = ((Join-Path $target 'classes'), (Join-Path $target 'test-classes'), (Join-Path $dir '*')) -join ';'
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $cp it.unisi.dass.sicurezza.CollaudoSicurezza
    if ($LASTEXITCODE -ne 0) { throw 'Collaudo di sicurezza fallito.' }
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force
}
