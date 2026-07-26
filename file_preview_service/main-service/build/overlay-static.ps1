# overlay-static.ps1 -- in-place update of BOOT-INF/classes/static/* inside the
# executable jar, preserving every other entry (nested jars stay STORED).
# See pom.xml verify-frontend-assets for why this exists.
param(
  [Parameter(Mandatory = $true)][string]$JarPath,
  [Parameter(Mandatory = $true)][string]$StaticDir,
  [string]$Prefix = 'BOOT-INF/classes/static'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$jarFull = (Resolve-Path $JarPath).Path
$staticFull = (Resolve-Path $StaticDir).Path

$zip = [System.IO.Compression.ZipFile]::Open($jarFull, 'Update')
try {
  @($zip.Entries | Where-Object { $_.FullName.StartsWith($Prefix) }) | ForEach-Object { $_.Delete() }
  Get-ChildItem -Recurse -File $staticFull | ForEach-Object {
    $rel = $_.FullName.Substring($staticFull.Length).TrimStart('\', '/') -replace '\\', '/'
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, "$Prefix/$rel", 'Optimal') | Out-Null
  }
}
finally {
  $zip.Dispose()
}
Write-Host "overlay-static: wrote $((Get-ChildItem -Recurse -File $staticFull).Count) static entries into $jarFull"
