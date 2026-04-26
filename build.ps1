#!/usr/bin/env pwsh
# build.ps1 — builds the java-lint Zed extension on Windows
# Run: .\build.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "=== Java Lint — Zed Extension Builder ===" -ForegroundColor Cyan

# ── 1. Check prerequisites ──────────────────────────────────────────────────
Write-Host "`n[1/5] Checking prerequisites..."

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java not found. Add java to PATH."
}
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    Write-Error "Rust/Cargo not found. Install from https://rustup.rs"
}
Write-Host "  ✅ java and cargo found"

# ── 2. Add WASM target ─────────────────────────────────────────────────────
Write-Host "`n[2/5] Adding wasm32-wasip1 target..."
rustup target add wasm32-wasip1
Write-Host "  ✅ wasm target ready"

# ── 3. Download Checkstyle jar ─────────────────────────────────────────────
Write-Host "`n[3/5] Downloading Checkstyle..."
$CheckstyleVersion = "10.21.4"
$CheckstyleUrl = "https://github.com/checkstyle/checkstyle/releases/download/checkstyle-$CheckstyleVersion/checkstyle-$CheckstyleVersion-all.jar"
$CheckstyleJar = "shim\checkstyle.jar"

if (-not (Test-Path $CheckstyleJar)) {
    Invoke-WebRequest -Uri $CheckstyleUrl -OutFile $CheckstyleJar
    Write-Host "  ✅ Downloaded checkstyle-$CheckstyleVersion-all.jar"
} else {
    Write-Host "  ✅ Checkstyle already downloaded"
}

# ── 4. Compile the LSP shim jar ────────────────────────────────────────────
Write-Host "`n[4/5] Compiling LSP shim..."
Push-Location shim
javac -cp checkstyle.jar CheckstyleLspShim.java
jar cfm checkstyle-lsp-shim.jar MANIFEST.MF CheckstyleLspShim*.class
Pop-Location
Write-Host "  ✅ checkstyle-lsp-shim.jar built"

# ── 5. Build the Rust WASM extension ──────────────────────────────────────
Write-Host "`n[5/5] Building Rust WASM extension..."
cargo build --target wasm32-wasip1 --release
Write-Host "  ✅ WASM extension built"

# ── Done ───────────────────────────────────────────────────────────────────
Write-Host "`n=== Build complete! ===" -ForegroundColor Green
Write-Host @"

Next steps:
  1. Open Zed
  2. Press Ctrl+Shift+P → type: 'zed: install dev extension'
  3. Select this folder: $PWD
  4. Open any Java project — lint errors will appear as red underlines!

To customize lint rules, edit: checkstyle.xml
"@ -ForegroundColor Yellow
