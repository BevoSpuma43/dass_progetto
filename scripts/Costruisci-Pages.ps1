#requires -Version 5.1
<#
Eseguire da PowerShell nella radice del progetto: .\scripts\Costruisci-Pages.ps1
Prepara in _site la versione statica di DASS per GitHub Pages: pagine e stili del
prototipo, adattamenti della cartella github-pages, catalogo nutrizionale, schema
dello strumento Haiku e foto della fixture. Non servono Java, Maven o Payara.
Il workflow .github/workflows/pages.yml esegue lo stesso script prima del deploy.
#>
[CmdletBinding()]
param(
    [string]$Destinazione = '_site'
)
$ErrorActionPreference = 'Stop'

$radice = Split-Path -Parent $PSScriptRoot
$sito = if ([IO.Path]::IsPathRooted($Destinazione)) { $Destinazione } else { Join-Path $radice $Destinazione }

function Copy-Contenuto {
    param([string]$Origine, [string]$Arrivo, [string]$Filtro = '*')
    $base = (Resolve-Path -LiteralPath $Origine).Path.TrimEnd('\', '/')
    $file = @(Get-ChildItem -LiteralPath $base -Recurse -File -Filter $Filtro)
    if ($file.Count -eq 0) { throw "Nessun file $Filtro in $Origine" }
    foreach ($elemento in $file) {
        $destinazioneFile = Join-Path $Arrivo $elemento.FullName.Substring($base.Length + 1)
        $cartella = Split-Path -Parent $destinazioneFile
        if (-not (Test-Path -LiteralPath $cartella)) { New-Item -ItemType Directory -Path $cartella -Force | Out-Null }
        Copy-Item -LiteralPath $elemento.FullName -Destination $destinazioneFile -Force
    }
}

if (Test-Path -LiteralPath $sito) { Remove-Item -LiteralPath $sito -Recurse -Force }
New-Item -ItemType Directory -Path $sito | Out-Null

# Pagine, stili e immagini del prototipo, poi i file adattati per il sito statico.
Copy-Contenuto (Join-Path $radice 'src/main/webapp') $sito
Copy-Contenuto (Join-Path $radice 'github-pages') $sito
# stato.js verifica il server Payara, che su GitHub Pages non esiste.
$stato = Join-Path $sito 'js/stato.js'
if (Test-Path -LiteralPath $stato) { Remove-Item -LiteralPath $stato -Force }

# Dati letti dall'API nel browser e foto della fixture.
Copy-Contenuto (Join-Path $radice 'macros_tables') (Join-Path $sito 'data') 'catalogo_nutrizionale_it.json'
Copy-Contenuto (Join-Path $radice 'src/main/resources/analisi') (Join-Path $sito 'data') 'proponi-ingredienti.json'
Copy-Contenuto (Join-Path $radice 'src/main/resources/demo') (Join-Path $sito 'demo') '*.jpg'

$pubblicati = @(Get-ChildItem -LiteralPath $sito -Recurse -File)
$kb = [math]::Round(($pubblicati | Measure-Object -Property Length -Sum).Sum / 1KB)
Write-Host "Sito statico pronto in $sito ($($pubblicati.Count) file, $kb KB)."
