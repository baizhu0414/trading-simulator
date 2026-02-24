<#
Convert-to-UTF8BOM.ps1

Recursively convert common text files to UTF-8 with BOM.
Creates a .bak backup for each changed file.

Usage:
  # Preview (no changes):
  .\convert-to-utf8bom.ps1 -Root . -WhatIf

  # Execute:
  .\convert-to-utf8bom.ps1 -Root . -IncludeGitIgnored:$false

Notes:
- Skips binary files (detects NUL bytes in first 8K).
- Skips paths that contain any of the $ExcludeDirs names.
- Adds a .bak backup before overwriting.
#>

param(
    [string]$Root = ".",
    [string[]]$Extensions = @(
        '*.cpp','*.c','*.cc','*.cxx','*.h','*.hpp','*.inl',
        '*.md','*.txt','*.rst','*.cfg','*.ini','*.json','*.xml','*.yml','*.yaml',
        '*.cmake','CMakeLists.txt','*.html','*.htm','*.py','*.ps1','*.sh','*.bat'
    ),
    [string[]]$ExcludeDirs = @('build','vcpkg_installed','.git','.vs','bin','obj'),
    [switch]$WhatIf,
    [switch]$IncludeGitIgnored
)

$Root = Resolve-Path -Path $Root
Write-Host "Root: $Root" -ForegroundColor Cyan

# Find files
$files = @()
foreach($ext in $Extensions){
    if($ext -eq 'CMakeLists.txt'){
        $files += Get-ChildItem -Path $Root -Recurse -File -Filter 'CMakeLists.txt' -ErrorAction SilentlyContinue
    } else {
        $files += Get-ChildItem -Path $Root -Recurse -File -Include $ext -ErrorAction SilentlyContinue
    }
}

# Filter excludes
$files = $files | Where-Object {
    $path = $_.FullName.ToLower()
    foreach($d in $ExcludeDirs){ if($path -like "*\$d\*") { return $false } }
    return $true
}

Write-Host "Found $($files.Count) candidate files." -ForegroundColor Green

$converted = 0
$skippedBinary = 0
$alreadyUtf8Bom = 0

foreach($f in $files){
    $p = $f.FullName

    # Read first chunk to detect NUL (binary) and BOM
    try{
        $fs = [System.IO.File]::OpenRead($p)
        $buffer = New-Object byte[] 8192
        $read = $fs.Read($buffer,0,$buffer.Length)
        $fs.Close()
    } catch {
        Write-Warning "Cannot read $p : $_"
        continue
    }

    # Detect binary: NUL byte
    $hasNul = $false
    for($i=0;$i -lt $read; $i++){ if($buffer[$i] -eq 0){ $hasNul = $true; break } }
    if($hasNul){ $skippedBinary++ ; continue }

    # Detect BOM (EF BB BF)
    $isUtf8Bom = ($read -ge 3 -and $buffer[0] -eq 0xEF -and $buffer[1] -eq 0xBB -and $buffer[2] -eq 0xBF)
    if($isUtf8Bom){ $alreadyUtf8Bom++; continue }

    # Read file content as system ANSI (to preserve local encoding like GBK), convert to UTF8 with BOM
    try{
        $text = Get-Content -LiteralPath $p -Raw -Encoding Default
    } catch {
        Write-Warning "Failed reading text for $p : $_"
        continue
    }

    # Backup
    $bak = "$p.bak"
    if(-not $WhatIf){
        Copy-Item -LiteralPath $p -Destination $bak -Force
    }

    if($WhatIf){
        Write-Host "Would convert: $p" -ForegroundColor Yellow
    } else {
        try{
            $utf8bom = New-Object System.Text.UTF8Encoding($true)
            [System.IO.File]::WriteAllText($p, $text, $utf8bom)
            Write-Host "Converted: $p" -ForegroundColor Green
            $converted++
        } catch {
            Write-Warning "Failed writing $p : $_"
            # restore from bak
            if(Test-Path $bak){ Copy-Item -LiteralPath $bak -Destination $p -Force }
        }
    }
}

Write-Host "\nSummary:" -ForegroundColor Cyan
Write-Host "  Converted: $converted"
Write-Host "  Already UTF8-BOM: $alreadyUtf8Bom"
Write-Host "  Skipped (binary): $skippedBinary"
Write-Host "  Backups: .bak created next to converted files"

if($WhatIf){ Write-Host "(WhatIf) No files were modified." -ForegroundColor Yellow }

# End
