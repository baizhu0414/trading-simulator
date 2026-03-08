<#
force-convert-utf8bom.ps1

Recursively convert non-binary files to UTF-8 with BOM across the repository.
Creates a .bak backup for each changed file.

Usage:
  # Dry-run (list candidates, no changes)
  .\force-convert-utf8bom.ps1 -Root . -WhatIf

  # Execute conversion (destructive: creates .bak backups)
  .\force-convert-utf8bom.ps1 -Root . -Run

Notes:
- Skips directories in $ExcludeDirs (default: build, vcpkg_installed, .git, .vs)
- Determines binary files by checking for NUL bytes in first 16KB
- Attempts to read file as ANSI/Default, UTF8 (no BOM), UTF8 with BOM, UTF16 LE/BE
- Writes all converted files with UTF-8 BOM
#>

param(
    [string]$Root = ".",
    [string[]]$ExcludeDirs = @('build','vcpkg_installed','.git','.vs','bin','obj'),
    [int]$ScanBytes = 16384,
    [switch]$WhatIf,
    [switch]$Run
)

function Is-BinaryFile([string]$path, [int]$bytesToScan){
    try{
        $fs = [System.IO.File]::OpenRead($path)
        $buffer = New-Object byte[] $bytesToScan
        $read = $fs.Read($buffer, 0, $bytesToScan)
        $fs.Close()
    } catch {
        return $true
    }
    for($i=0; $i -lt $read; $i++){
        if($buffer[$i] -eq 0){ return $true }
    }
    return $false
}

function Detect-Encoding([string]$path){
    # Returns System.Text.Encoding instance or $null
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF){
        return (New-Object System.Text.UTF8Encoding($true))
    }
    if($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE){
        return [System.Text.UnicodeEncoding]::new($false,$true) # UTF-16 LE with BOM
    }
    if($bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF){
        return [System.Text.UnicodeEncoding]::new($true,$true) # UTF-16 BE with BOM
    }
    # Try UTF8 without BOM by attempting to decode
    try{
        $encUtf8 = New-Object System.Text.UTF8Encoding($false)
        $s = $encUtf8.GetString($bytes)
        $reencoded = $encUtf8.GetBytes($s)
        if($reencoded.Length -eq $bytes.Length){ return $encUtf8 }
    } catch { }
    # Fallback: Default ANSI (system)
    return [System.Text.Encoding]::Default
}

$Root = Resolve-Path -Path $Root
Write-Host "Root: $Root" -ForegroundColor Cyan

$all = Get-ChildItem -Path $Root -Recurse -File -ErrorAction SilentlyContinue
$candidates = @()
foreach($f in $all){
    $skip = $false
    foreach($d in $ExcludeDirs){ if($f.FullName -like "*\$d\*") { $skip = $true; break } }
    if($skip){ continue }
    if(Is-BinaryFile $f.FullName $ScanBytes){ continue }
    $candidates += $f
}

Write-Host "Found $($candidates.Count) non-binary candidate files." -ForegroundColor Green

$converted = 0
$alreadyBom = 0

foreach($fi in $candidates){
    $p = $fi.FullName
    $bytes = [System.IO.File]::ReadAllBytes($p)
    $isBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    if($isBom){ $alreadyBom++; continue }

    if($WhatIf){ Write-Host "Would convert: $p" -ForegroundColor Yellow; continue }
    if(-not $Run){ Write-Host "Skipping (no -Run): $p" -ForegroundColor Yellow; continue }

    # Backup
    $bak = "$p.bak"
    try{ Copy-Item -LiteralPath $p -Destination $bak -Force } catch { Write-Warning "Backup failed for $p" }

    try{
        $enc = Detect-Encoding $p
        $text = $enc.GetString($bytes)
        $utf8bom = New-Object System.Text.UTF8Encoding($true)
        [System.IO.File]::WriteAllText($p, $text, $utf8bom)
        Write-Host "Converted: $p" -ForegroundColor Green
        $converted++
    } catch {
        Write-Warning "Failed to convert $p : $_"
        if(Test-Path $bak){ Copy-Item -LiteralPath $bak -Destination $p -Force }
    }
}

Write-Host "\nSummary:" -ForegroundColor Cyan
Write-Host "  Converted: $converted"
Write-Host "  Already UTF8-BOM: $alreadyBom"

# End
