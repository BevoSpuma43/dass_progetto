#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not $env:DASS_PAYARA_JAR) {
    throw 'Eseguire Prepara-DASS.ps1 in questa sessione.'
}
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'target'
$test = Join-Path $target 'test-classes\it\unisi\dass\analisi\CollaudoClaude.class'
if (-not (Test-Path -LiteralPath $test)) { throw 'Prima eseguire mvn clean verify.' }
# Usiamo lo stesso provider JSON-P di Payara anche senza avviare il server.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dir = Join-Path $target ('collaudo-claude-' + [guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($dir)
$jsonJar = Join-Path $dir 'jakarta.json.jar'
try {
    $payara = [IO.Compression.ZipFile]::OpenRead($env:DASS_PAYARA_JAR)
    try {
        $entry = $payara.GetEntry('MICRO-INF/runtime/jakarta.json.jar')
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $jsonJar)
    } finally { $payara.Dispose() }
    $cp = ((Join-Path $target 'classes'), (Join-Path $target 'test-classes'), $jsonJar) -join ';'
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $cp it.unisi.dass.analisi.CollaudoClaude
    if ($LASTEXITCODE -ne 0) { throw 'Collaudo Claude offline fallito.' }
} finally {
    if (Test-Path -LiteralPath $jsonJar) { Remove-Item -LiteralPath $jsonJar }
    [IO.Directory]::Delete($dir)
}
