#requires -Version 5.1
<#
Eseguire da PowerShell: .\Prepara-DASS.ps1
Scarica gli strumenti nel workspace, verifica i file e attiva Java e Maven
nella sessione corrente. Nelle sessioni successive basta rilanciare lo script.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$toolsRoot = Join-Path $PSScriptRoot '.dass-tools'
$jdkVersion = '21.0.13.1'
$mavenVersion = '3.9.11'
$payaraVersion = '6.2025.11'
$jdkDir = Join-Path $toolsRoot "microsoft-jdk-$jdkVersion"
$mavenDir = Join-Path $toolsRoot "apache-maven-$mavenVersion"
$payaraName = "payara-micro-$payaraVersion.jar"
$payaraJar = Join-Path $toolsRoot $payaraName

function Get-VerifiedPackage {
    param($Uri, $ChecksumUri, $Algorithm, $Destination)
    Write-Host "Download di $([IO.Path]::GetFileName($Destination))"
    $checksumFile = "$Destination.checksum"
    Invoke-WebRequest -UseBasicParsing -Uri $ChecksumUri -OutFile $checksumFile -ErrorAction Stop
    $published = [IO.File]::ReadAllText($checksumFile)
    $length = @{SHA256 = 64; SHA512 = 128; SHA1 = 40}[$Algorithm]
    $digest = [regex]::Match($published, "(?i)\b[0-9a-f]{$length}\b")
    if (-not $digest.Success) { throw "Checksum $Algorithm illeggibile su $ChecksumUri" }
    Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Destination -ErrorAction Stop
    if ((Get-FileHash -Algorithm $Algorithm -LiteralPath $Destination).Hash -ne $digest.Value) {
        throw "Checksum errato per $Destination. Pacchetto scartato."
    }
}

function Install-ZipTool {
    param($Uri, $ChecksumUri, $Algorithm, $Directory, $Executable, $Label)
    if (Test-Path -LiteralPath (Join-Path $Directory $Executable) -PathType Leaf) { return }
    if (Test-Path -LiteralPath $Directory) { throw "Directory incompleta: $Directory" }
    $zip = Join-Path $stage "$Label.zip"
    Get-VerifiedPackage $Uri $ChecksumUri $Algorithm $zip
    $expanded = Join-Path $stage $Label
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $expanded)
    $roots = @(Get-ChildItem -LiteralPath $expanded -Directory | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName $Executable) -PathType Leaf
    })
    if ($roots.Count -ne 1) { throw "Struttura inattesa nell'archivio $Label." }
    # Directory.Move pubblica la cartella completa e fallisce se la destinazione esiste.
    $sourceDir = $roots[0].FullName
    if (-not $sourceDir.StartsWith(($expanded + '\'), [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetDirectoryName($Directory) -ne $toolsRoot) { throw 'Percorso inatteso.' }
    [IO.Directory]::Move($sourceDir, $Directory)
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$null = [IO.Directory]::CreateDirectory($toolsRoot)
$stage = Join-Path $toolsRoot ('tmp-' + [guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($stage)
$previousJava = $env:JAVA_HOME
$previousPath = $env:Path
$previousTls = [Net.ServicePointManager]::SecurityProtocol
try {
    [Net.ServicePointManager]::SecurityProtocol = $previousTls -bor [Net.SecurityProtocolType]::Tls12
    $jdkUrl = "https://aka.ms/download-jdk/microsoft-jdk-$jdkVersion-windows-x64.zip"
    Install-ZipTool $jdkUrl "$jdkUrl.sha256sum.txt" SHA256 $jdkDir 'bin\javac.exe' 'jdk'

    $mavenUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
    Install-ZipTool $mavenUrl "$mavenUrl.sha512" SHA512 $mavenDir 'bin\mvn.cmd' 'maven'

    if (-not (Test-Path -LiteralPath $payaraJar -PathType Leaf)) {
        $payaraUrl = "https://repo.maven.apache.org/maven2/fish/payara/extras/payara-micro/$payaraVersion/$payaraName"
        $candidate = Join-Path $stage $payaraName
        Get-VerifiedPackage $payaraUrl "$payaraUrl.sha1" SHA1 $candidate
        [IO.File]::Move($candidate, $payaraJar)
    }

    $javaBin = Join-Path $jdkDir 'bin'
    $mavenBin = Join-Path $mavenDir 'bin'
    $env:JAVA_HOME = $jdkDir
    $remaining = @($env:Path -split ';' | Where-Object { $_ -and $_ -notin @($javaBin, $mavenBin) })
    $env:Path = (@($javaBin, $mavenBin) + $remaining) -join ';'

    & (Join-Path $javaBin 'java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'Avvio Java fallito.' }
    & (Join-Path $javaBin 'javac.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'Avvio compilatore fallito.' }
    & (Join-Path $mavenBin 'mvn.cmd') --version
    if ($LASTEXITCODE -ne 0) { throw 'Avvio Maven fallito.' }
    # Payara 6.2025.11 scrive la versione su stderr e termina con codice 1.
    & {
        # PowerShell 5.1 tratta stderr reindirizzato come un flusso di errori.
        $ErrorActionPreference = 'Continue'
        $outputLines = & (Join-Path $javaBin 'java.exe') -jar $payaraJar --version 2>&1
        $exitCode = $LASTEXITCODE
        $versionText = ($outputLines -join [Environment]::NewLine).Trim()
        $expected = 'Payara Micro ' + $payaraVersion + '. Build Number '
        if ($exitCode -notin 0, 1 -or $versionText -notmatch ('(?m)^' + [regex]::Escape($expected) + '[0-9]+\s*$')) {
            throw "Verifica di Payara Micro $payaraVersion fallita (codice $exitCode). $versionText"
        }
    }
    $env:DASS_PAYARA_JAR = $payaraJar
    # Maven usa i certificati di Windows: funziona anche dove un antivirus ispeziona HTTPS.
    if ($env:MAVEN_OPTS -notmatch 'trustStoreType') {
        $env:MAVEN_OPTS = ("$env:MAVEN_OPTS -Djavax.net.ssl.trustStoreType=Windows-ROOT").Trim()
    }
    Write-Host "Ambiente pronto per lo step 1. Payara Micro $payaraVersion verificato."
} catch {
    $env:JAVA_HOME = $previousJava
    $env:Path = $previousPath
    throw
} finally {
    [Net.ServicePointManager]::SecurityProtocol = $previousTls
    # Rimuove solo la directory temporanea creata da questa esecuzione.
    $resolvedStage = (Resolve-Path -LiteralPath $stage).ProviderPath
    if ([IO.Path]::GetDirectoryName($resolvedStage) -eq $toolsRoot -and
        [IO.Path]::GetFileName($resolvedStage) -match '^tmp-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
