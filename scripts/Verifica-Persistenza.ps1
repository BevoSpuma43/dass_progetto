#requires -Version 5.1
<#
Eseguire dalla radice in una PowerShell preparata, dopo mvn clean verify:
  .\scripts\Verifica-Persistenza.ps1
Collauda l'archivio cifrato su un database H2 temporaneo: rimozione di paz-demo da un archivio della
versione precedente, profili utente1 e utente2, documenti cifrati, revisioni, isolamento e riavvio.
Usa il driver H2 e il provider EclipseLink inclusi in Payara Micro.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not $env:DASS_PAYARA_JAR) { throw 'Eseguire prima Prepara-DASS.ps1.' }
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'target'
if (-not (Test-Path (Join-Path $target 'test-classes\it\unisi\dass\persistenza\CollaudoDocumenti.class'))) {
    throw 'Compilare il progetto con mvn clean verify.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dir = Join-Path ([IO.Path]::GetTempPath()) ('collaudo-documenti-' + [guid]::NewGuid().ToString('N'))
$lib = [IO.Directory]::CreateDirectory((Join-Path $dir 'lib')).FullName
$dati = [IO.Directory]::CreateDirectory((Join-Path $dir 'dati')).FullName
[IO.File]::WriteAllText((Join-Path $dir 'solo-collaudo'), 'DASS')
$payara = [IO.Compression.ZipFile]::OpenRead($env:DASS_PAYARA_JAR)
try {
    foreach ($jar in 'h2','jakarta.persistence-api','jakarta.transaction-api','jakarta.json','org.eclipse.persistence.asm',
                     'org.eclipse.persistence.core','org.eclipse.persistence.jpa','org.eclipse.persistence.jpa.jpql') {
        $entry = $payara.GetEntry("MICRO-INF/runtime/$jar.jar")
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $lib "$jar.jar"))
    }
} finally { $payara.Dispose() }
$cp = ((Join-Path $target 'test-classes'), (Join-Path $target 'classes'), (Join-Path $lib '*')) -join ';'
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $cp it.unisi.dass.persistenza.CollaudoDocumenti $dati
if ($LASTEXITCODE -ne 0) { throw "Collaudo dell'archivio cifrato fallito. Dati di prova in $dir" }
# Rimuove solo la cartella temporanea creata da questo script.
$verificata = (Resolve-Path -LiteralPath $dir).ProviderPath
if ([IO.Path]::GetDirectoryName($verificata) -eq [IO.Path]::GetTempPath().TrimEnd('\') -and
    [IO.Path]::GetFileName($verificata) -match '^collaudo-documenti-[0-9a-f]{32}$' -and
    [IO.File]::ReadAllText((Join-Path $verificata 'solo-collaudo')) -eq 'DASS') {
    Remove-Item -LiteralPath $verificata -Recurse -Force
}
