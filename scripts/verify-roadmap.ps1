# Verifica playbook roadmap (cloud / abusi / UI). Non modifica il piano in .cursor/plans.
# Uso:
#   .\scripts\verify-roadmap.ps1
#   $env:PROCESS_INDEX_URL = "https://<ref>.supabase.co/functions/v1/process-index"; $env:SUPABASE_ANON_KEY = "eyJ..."; .\scripts\verify-roadmap.ps1 -SmokeProcessIndex
# Richiede: Maven; opzionale curl (Windows10+) per smoke HTTP.

param(
    [switch] $SmokeProcessIndex)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $root "pom.xml"))) {
    throw "Esegui dalla cartella del repo JPdfBookmarks (manca pom.xml in $root)"
}
Set-Location $root

Write-Host "== Maven compile (jpdfbookmarks_core + moduli) ==" -ForegroundColor Cyan
mvn -pl jpdfbookmarks_core -am clean test -q
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "== File backend attesi ==" -ForegroundColor Cyan
$be = Join-Path $root "..\pdfbookmarks-backend"
if (-not (Test-Path $be)) { $be = "C:\Users\flavi\Projects\pdfbookmarks-backend" }
$mig = Join-Path $be "supabase\migrations\20260413120000_process_index_usage.sql"
if (-not (Test-Path $mig)) {
    Write-Warning "Migrazione non trovata: $mig (imposta percorso repo backend)"
} else {
    $t = Get-Content $mig -Raw
    if ($t -notmatch "try_increment_process_index_usage") { throw "RPC mancante nel file migrazione" }
    Write-Host "OK migrazione process_index_usage" -ForegroundColor Green
}

$pi = Join-Path $be "supabase\functions\process-index\index.ts"
if (-not (Test-Path $pi)) {
    Write-Warning "process-index non trovato: $pi"
} else {
    $t2 = Get-Content $pi -Raw
    if ($t2 -notmatch "try_increment_process_index_usage") { throw "process-index non invoca RPC rate limit" }
    if ($t2 -notmatch "FREE_PREVIEW_MAX_INDEX_PAGES") { throw "process-index senza soglia pagine preview" }
    Write-Host "OK Edge function process-index (guardrail presenti)" -ForegroundColor Green
}

if ($SmokeProcessIndex) {
    $url = $env:PROCESS_INDEX_URL
    $key = $env:SUPABASE_ANON_KEY
    if (-not $url -or -not $key) {
        throw "Imposta PROCESS_INDEX_URL e SUPABASE_ANON_KEY per lo smoke HTTP"
    }
    Write-Host "== POST process-index (body minimo: atteso 400 Missing imageBase64) ==" -ForegroundColor Cyan
    $body = '{}'
    curl.exe -sS -D $env:TEMP\pi-smoke.hdr -o $env:TEMP\pi-smoke.json -X POST $url `
        -H "apikey: $key" -H "Authorization: Bearer $key" -H "Content-Type: application/json" `
        --data-binary $body
    $j = Get-Content $env:TEMP\pi-smoke.json -Raw
    if ($j -notmatch "Missing imageBase64" -and $j -notmatch "imageBase64") {
        Write-Warning "Risposta inattesa: $j"
    } else {
        Write-Host "OK smoke process-index (errore atteso su payload vuoto)" -ForegroundColor Green
    }

    Write-Host "== POST process-index (start>end pagine: atteso 400) ==" -ForegroundColor Cyan
    $body2 = '{"imageBase64":"e30=","start_page":5,"end_page":1,"textLayerContent":""}'
    curl.exe -sS -o $env:TEMP\pi-smoke2.json -X POST $url `
        -H "apikey: $key" -H "Authorization: Bearer $key" -H "Content-Type: application/json" `
        --data-binary $body2
    $j2 = Get-Content $env:TEMP\pi-smoke2.json -Raw
    if ($j2 -match "end_page must be >= start_page" -or $j2 -match "error") {
        Write-Host "OK smoke validazione intervallo pagine" -ForegroundColor Green
    } else {
        Write-Warning "Risposta intervallo pagine inattesa: $j2"
    }

    Write-Host "== E2E manuale: PDF reale in GUI, oppure loop curl con payload valido fino a rate_limited (429) ==" -ForegroundColor DarkGray
}

Write-Host "Verifica completata." -ForegroundColor Green
