#requires -Version 5.1
[CmdletBinding()]
param([switch]$SoloPastiInMemoria)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not $env:DASS_PAYARA_JAR) { throw 'Eseguire prima Prepara-DASS.ps1.' }
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'target'
$classe = if ($SoloPastiInMemoria) { 'CollaudoPasti' } else { 'CollaudoPersistenza' }
if (-not (Test-Path (Join-Path $target "test-classes\it\unisi\dass\persistenza\$classe.class"))) {
    throw 'Compilare il progetto con mvn verify.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dir = Join-Path ([IO.Path]::GetTempPath()) ('collaudo-persistenza-' + [guid]::NewGuid().ToString('N'))
$lib = [IO.Directory]::CreateDirectory((Join-Path $dir 'lib')).FullName
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
if (-not $SoloPastiInMemoria) {
    foreach ($avvio in 1..3) {
        & (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $cp it.unisi.dass.persistenza.CollaudoPersistenza $dir $avvio
        if ($LASTEXITCODE -ne 0) { throw "Collaudo fallito all'avvio $avvio. Dati di prova in $dir" }
    }
    Write-Host 'Persistenza, vincoli e rollback verificati su un database temporaneo separato.'
}
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $cp it.unisi.dass.persistenza.CollaudoPasti
if ($LASTEXITCODE -ne 0) { throw "Collaudo dello step 06 fallito. Dati di prova in $dir" }
$verificata = (Resolve-Path -LiteralPath $dir).ProviderPath
if ([IO.Path]::GetDirectoryName($verificata) -eq [IO.Path]::GetTempPath().TrimEnd('\') -and
    [IO.Path]::GetFileName($verificata) -match '^collaudo-persistenza-[0-9a-f]{32}$' -and
    [IO.File]::ReadAllText((Join-Path $verificata 'solo-collaudo')) -eq 'DASS') {
    Remove-Item -LiteralPath $verificata -Recurse -Force
}

