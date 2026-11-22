#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$chiave = Read-Host 'Copia ora la chiave Anthropic completa, incollala qui e premi Invio' -AsSecureString
try {
    $testo = [System.Net.NetworkCredential]::new('', $chiave).Password.Trim()
    if ($testo -notmatch '^sk-ant-[A-Za-z0-9_-]+$') {
        throw 'Formato inatteso. Copiare il valore completo dalla Claude Console e riprovare.'
    }
    $pulita = ConvertTo-SecureString -String $testo -AsPlainText -Force
    try {
        $cifrata = ConvertFrom-SecureString -SecureString $pulita
    } finally { $pulita.Dispose() }
} finally {
    $testo = $null
    $chiave.Dispose()
}

$dir = Join-Path $env:LOCALAPPDATA 'DASS'
$null = [IO.Directory]::CreateDirectory($dir)
$file = Join-Path $dir 'anthropic-key.dpapi'
$tmp = Join-Path $dir ([guid]::NewGuid().ToString('N') + '.tmp')
try {
    [IO.File]::WriteAllText($tmp, $cifrata)
    if ([IO.File]::Exists($file)) {
        $backup = "$tmp.bak"
        [IO.File]::Replace($tmp, $file, $backup)
        [IO.File]::Delete($backup)
    } else { [IO.File]::Move($tmp, $file) }
} finally { [IO.File]::Delete($tmp) }
Write-Host 'Chiave salvata nel profilo Windows. Avvia-DASS.ps1 la carica al prossimo avvio.'
