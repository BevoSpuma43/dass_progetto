#requires -Version 5.1
<#
Eseguire da PowerShell nella radice del progetto: .\scripts\Avvia-Pages.ps1
Ricostruisce _site con Costruisci-Pages.ps1 e lo serve su http://localhost:8090/,
come farebbe GitHub Pages. Con -SenzaCostruzione serve la cartella _site esistente.
Premere Ctrl+C per fermare il server.
#>
[CmdletBinding()]
param(
    [int]$Porta = 8090,
    [switch]$SenzaCostruzione
)
$ErrorActionPreference = 'Stop'

if (-not $SenzaCostruzione) { & (Join-Path $PSScriptRoot 'Costruisci-Pages.ps1') }
$sito = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSScriptRoot) '_site'))
$tipi = @{
    '.html' = 'text/html; charset=utf-8'
    '.css'  = 'text/css; charset=utf-8'
    '.js'   = 'text/javascript; charset=utf-8'
    '.json' = 'application/json; charset=utf-8'
    '.jpg'  = 'image/jpeg'
    '.png'  = 'image/png'
    '.svg'  = 'image/svg+xml'
    '.txt'  = 'text/plain; charset=utf-8'
}

$indirizzo = "http://localhost:$Porta/"
$server = New-Object System.Net.HttpListener
$server.Prefixes.Add($indirizzo)
$server.Start()
Write-Host "Anteprima di GitHub Pages su $indirizzo (Ctrl+C per fermare)"
try {
    while ($server.IsListening) {
        $attesa = $server.GetContextAsync()
        # L'attesa a intervalli lascia a PowerShell la possibilita' di gestire Ctrl+C.
        while (-not $attesa.AsyncWaitHandle.WaitOne(250)) { }
        $contesto = $attesa.GetAwaiter().GetResult()
        $risposta = $contesto.Response
        try {
            $percorso = [Uri]::UnescapeDataString($contesto.Request.Url.AbsolutePath).TrimStart('/')
            if ($percorso -eq '' -or $percorso.EndsWith('/')) { $percorso += 'index.html' }
            $file = [IO.Path]::GetFullPath((Join-Path $sito $percorso))
            $interno = $file.StartsWith($sito + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
            if ($interno -and (Test-Path -LiteralPath $file -PathType Leaf)) {
                $byte = [IO.File]::ReadAllBytes($file)
                $tipo = $tipi[[IO.Path]::GetExtension($file).ToLowerInvariant()]
                if (-not $tipo) { $tipo = 'application/octet-stream' }
                $risposta.ContentType = $tipo
                $risposta.Headers.Add('Cache-Control', 'no-cache')
                $risposta.ContentLength64 = $byte.Length
                # Una richiesta HEAD riceve solo le intestazioni.
                if ($contesto.Request.HttpMethod -ne 'HEAD') { $risposta.OutputStream.Write($byte, 0, $byte.Length) }
            } else {
                $risposta.StatusCode = 404
            }
        } catch {
            # Un errore su una singola richiesta non deve fermare l'anteprima.
            Write-Warning "Richiesta non servita: $($_.Exception.Message)"
        } finally {
            try { $risposta.Close() } catch { }
        }
    }
} finally {
    $server.Stop()
    $server.Close()
}
