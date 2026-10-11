#requires -Version 5
<#
  WaterBuild - build the standalone patcher jar.
  Requires a JDK 21+ (javac/jar) on PATH.
  Output: build\WaterBuild.jar
  Resources embedded into the jar: base.properties (+ CW.png if present).
  Patches are NOT embedded - the patcher downloads them from GitHub at runtime.
#>
param(
    [string]$Root = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$src  = Join-Path $here 'src\CraftWaterPatcher.java'
$cls  = Join-Path $here 'build\classes'
$res  = Join-Path $here 'build\res'

if (Test-Path -LiteralPath $cls) { Remove-Item -Recurse -Force -LiteralPath $cls }
if (Test-Path -LiteralPath $res) { Remove-Item -Recurse -Force -LiteralPath $res }
New-Item -ItemType Directory -Force -Path $cls | Out-Null
New-Item -ItemType Directory -Force -Path $res | Out-Null

Write-Host "compiling..."
& javac --release 21 -encoding UTF-8 -d $cls $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Copy-Item (Join-Path $Root 'base.properties') $res -Force
$icon = Join-Path $Root 'CW.png'
if (Test-Path $icon) { Copy-Item $icon $res -Force }

$dist = Join-Path $here 'build'
$jar  = Join-Path $dist 'WaterBuild.jar'
if (Test-Path $jar) { Remove-Item $jar -Force }

Write-Host "packaging..."
& jar --create --file $jar --main-class CraftWaterPatcher -C $cls . -C $res .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "built $jar"