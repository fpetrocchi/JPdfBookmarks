# Compila il reactor con le proprieta ai.cloud.* lette da file locale, cosi Maven filtra
# jpdfbookmarks_core/.../ai-cloud-defaults.properties con URL e anon key (come il package release).
#
# File cercati (primo che esiste vince):
#   1) JPdfBookmarks/local-ai-cloud-maven.properties  (tipico, in .gitignore)
#   2) JPdfBookmarks/../JPdfBookmarks-private/maven/ai-cloud-release.properties
#
# Se nessuno esiste: esce 0 e non modifica nulla (build IDE senza cloud bundled).
#
# Uso: .\scripts\compile-with-local-ai-cloud.ps1
#      oppure come preLaunchTask da VS Code / Cursor.

param(
    [string] $PropertiesFile = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

if ([string]::IsNullOrWhiteSpace($PropertiesFile)) {
    $PropertiesFile = Join-Path $root "local-ai-cloud-maven.properties"
    if (-not (Test-Path -LiteralPath $PropertiesFile)) {
        $alt = Join-Path $root "..\JPdfBookmarks-private\maven\ai-cloud-release.properties"
        if (Test-Path -LiteralPath $alt) {
            $PropertiesFile = $alt
        }
    }
}

if (-not (Test-Path -LiteralPath $PropertiesFile)) {
    Write-Host "[compile-with-local-ai-cloud] Nessun file ai.cloud in locale: mvn compile senza -Dai.cloud.*. Per filtrare gli URL: copia in local-ai-cloud-maven.properties o apri il repo JPdfBookmarks-private (maven/ai-cloud-release.properties)." -ForegroundColor DarkYellow
    Set-Location $root
    mvn -q compile -DskipTests
    exit $LASTEXITCODE
}

$invoke = Join-Path $root "scripts\package-with-local-cloud.ps1"
& $invoke -PropertiesFile $PropertiesFile -MavenGoals @("compile") -SkipTests
exit $LASTEXITCODE
