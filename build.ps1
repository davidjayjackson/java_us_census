<#
    build.ps1 - Assemble the US Census Java Calc add-in into build/Census.oxt

    Pipeline:
      1. unoidl-write  : idl/**.idl                -> build/types/XCensus.rdb (UNO type library)
      2. javamaker     : build/types/XCensus.rdb   -> build/gen/**.class      (Java bindings)
      3. javac         : src/**.java + bindings    -> build/classes/**.class
      4. jar           : classes + bindings        -> build/oxt/census.jar    (+ RegistrationClassName)
      5. zip           : staging tree              -> build/Census.oxt

    Requirements:
      - LibreOffice with the SDK installed (default C:\Program Files\LibreOffice,
        with the SDK under <LibreOffice>\sdk). Override with -LibreOffice.
      - A JDK (any 8+) to compile with. Resolved from -Jdk, else JAVA_HOME, else
        PATH. Output targets Java 8 bytecode (--release 8) so it runs on the
        Oracle JRE 8 that LibreOffice accepts; see docs/INSTALL.md.

    Usage:
      pwsh -File build.ps1
      pwsh -File build.ps1 -LibreOffice 'C:\Program Files\LibreOffice' -Jdk 'C:\Program Files\Android\Android Studio\jbr'
#>
param(
    [string]$LibreOffice = 'C:\Program Files\LibreOffice',
    [string]$Jdk = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# --- Tool locations -------------------------------------------------------
$sdkBin  = Join-Path $LibreOffice 'sdk\bin'
$program = Join-Path $LibreOffice 'program'
$types   = Join-Path $program 'types.rdb'
$uw      = Join-Path $sdkBin 'unoidl-write.exe'
$jmaker  = Join-Path $sdkBin 'javamaker.exe'

if (-not $Jdk -and $env:JAVA_HOME) { $Jdk = $env:JAVA_HOME }
if ($Jdk) {
    $javac = Join-Path $Jdk 'bin\javac.exe'
    $jar   = Join-Path $Jdk 'bin\jar.exe'
    if (-not (Test-Path $javac)) { throw "javac not found under -Jdk/JAVA_HOME: $javac" }
} else {
    $javac = 'javac'
    $jar   = 'jar'
}

foreach ($t in @($uw, $jmaker)) {
    if (-not (Test-Path $t)) { throw "SDK tool not found: $t  (check -LibreOffice)" }
}
if (-not (Test-Path $types)) { throw "types.rdb not found: $types  (check -LibreOffice)" }

# unoidl-write and javamaker need the LibreOffice program dir on PATH for DLLs.
$env:PATH = $program + ';' + $env:PATH

# --- Clean staging --------------------------------------------------------
$build   = Join-Path $root 'build'
$genDir  = Join-Path $build 'gen'
$clsDir  = Join-Path $build 'classes'
$typeDir = Join-Path $build 'types'
$stage   = Join-Path $build 'oxt'
foreach ($d in @($genDir, $clsDir, $typeDir, $stage)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
}
foreach ($d in @($genDir, $clsDir, $typeDir,
                 (Join-Path $stage 'types'),
                 (Join-Path $stage 'config'),
                 (Join-Path $stage 'META-INF'))) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# --- 1. Compile IDL -> type library ---------------------------------------
Write-Host '[1/5] unoidl-write : idl -> build/types/XCensus.rdb'
$rdb = Join-Path $typeDir 'XCensus.rdb'
& $uw $types (Join-Path $root 'idl') $rdb
if ($LASTEXITCODE -ne 0) { throw "unoidl-write failed (exit $LASTEXITCODE)" }

# --- 2. Generate Java bindings from the type library ----------------------
Write-Host '[2/5] javamaker    : build/types/XCensus.rdb -> build/gen'
& $jmaker '-nD' '-Gc' '-O' $genDir '-X' $types $rdb
if ($LASTEXITCODE -ne 0) { throw "javamaker failed (exit $LASTEXITCODE)" }

# --- 3. Compile Java sources -----------------------------------------------
Write-Host '[3/5] javac        : src -> build/classes'
$sdkClasses = Join-Path $program 'classes'
$cp = @(
    $genDir,
    (Join-Path $sdkClasses '*')   # URE jars: libreoffice.jar / juh / jurt / ridl / unoil
) -join ';'
$sources = Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Filter *.java |
           ForEach-Object { $_.FullName }
& $javac '--release' '8' '-cp' $cp '-d' $clsDir @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

# --- 4. Package the jar (with RegistrationClassName manifest) -------------
Write-Host '[4/5] jar          : classes + bindings -> build/oxt/census.jar'
$censusJar = Join-Path $stage 'census.jar'
$mf = Join-Path $root 'registration\MANIFEST.MF'
& $jar 'cfm' $censusJar $mf '-C' $clsDir '.' '-C' $genDir '.'
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }

# --- 5. Stage remaining package files and zip the .oxt --------------------
Write-Host '[5/5] zip           : staging -> build/Census.oxt'
Copy-Item $rdb                                              (Join-Path $stage 'types\XCensus.rdb')
Copy-Item (Join-Path $root 'registration\CalcAddIns.xcu')  (Join-Path $stage 'config\CalcAddIns.xcu')
Copy-Item (Join-Path $root 'registration\description.xml') (Join-Path $stage 'description.xml')
Copy-Item (Join-Path $root 'registration\manifest.xml')    (Join-Path $stage 'META-INF\manifest.xml')

$oxt = Join-Path $build 'Census.oxt'
if (Test-Path $oxt) { Remove-Item $oxt -Force }
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
$zip = [System.IO.Compression.ZipFile]::Open($oxt, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $stage -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($stage.Length + 1) -replace '\\', '/'
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel)
    }
} finally {
    $zip.Dispose()
}
Write-Host ''
Write-Host "Built $oxt"
Write-Host 'Install with:  unopkg add --force build\Census.oxt   (see docs/INSTALL.md)'
