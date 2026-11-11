param([Parameter(Mandatory=$true)][string]$JavaExe)
$ErrorActionPreference='Stop'
$gene=Join-Path $PSScriptRoot 'gene'
New-Item -ItemType Directory -Path $gene -Force | Out-Null
$stage=Join-Path $gene ('bin.new-'+[Guid]::NewGuid().ToString('N'))
$output=Join-Path $gene 'bin'
$sevenZip=Join-Path $PSScriptRoot 'Installer/Tools/7z.exe'
$archive=Join-Path $gene 'bin.7z'
$archiveTemp=Join-Path $gene ('bin.new-'+[Guid]::NewGuid().ToString('N')+'.7z')
# Build inputs live inside the top-level NT source folder.
if(!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'NT/app/dir.html') -PathType Leaf)){throw 'Missing project input: NT/app/dir.html. Place NT beside MfcEdgeDemo.vcxproj.'}
if(!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'NT/db') -PathType Container)){throw 'Missing project input: NT/db. Place NT beside MfcEdgeDemo.vcxproj.'}

if(!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'NT/config.js') -PathType Leaf)){throw 'Missing project input: NT/config.js. Place config.js beside NT/app, NT/db and NT/lucene.'}

if(!(Test-Path -LiteralPath $sevenZip -PathType Leaf)){throw "7-Zip not found: $sevenZip"}
try {
 New-Item -ItemType Directory -Path $stage | Out-Null
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'NT/config.js') -Destination (Join-Path $stage 'config.js')
 $databaseBuildClasspath=(Join-Path $PSScriptRoot 'NoonJava/build/nttool')+';'+(Join-Path $PSScriptRoot 'NoonJava/lib/*')
 & $JavaExe -cp $databaseBuildClasspath BuildNt (Join-Path $PSScriptRoot 'NT/app') (Join-Path $PSScriptRoot 'NT/db') (Join-Path $stage 'NT')
 if($LASTEXITCODE -ne 0){throw "dtbuild failed: $LASTEXITCODE"}
 # Optional ready-made indexes live beside app/db and follow the existing bin packaging.
 & $JavaExe -cp $databaseBuildClasspath StageLucene (Join-Path $PSScriptRoot 'NT/lucene') (Join-Path $stage 'lucene')
 if($LASTEXITCODE -ne 0){throw "Ready-made Lucene staging failed: $LASTEXITCODE"}
 $signingClasspath=(Join-Path $PSScriptRoot 'NoonJava/build/signing')+';'+(Join-Path $PSScriptRoot 'NoonJava/resources')+';'+(Join-Path $PSScriptRoot 'NoonJava/lib/*')
 & $JavaExe -cp $signingClasspath SignBin sign $PSScriptRoot $stage
 if($LASTEXITCODE -ne 0){throw "Signed-bin generation failed: $LASTEXITCODE"}
 & $JavaExe -cp $signingClasspath VerifySignedBin $stage
 if($LASTEXITCODE -ne 0){throw "Signed-bin verification failed: $LASTEXITCODE"}
 if(Test-Path -LiteralPath $output){Remove-Item -LiteralPath $output -Recurse -Force}
 Move-Item -LiteralPath $stage -Destination $output
 # Keep the signed folder for the existing embedding pipeline; export a reusable archive.
 Push-Location $gene
 try {
  Write-Host 'Compressing signed bin: 7z / Ultra / LZMA2 / 1024 MB / word 64 / solid 16 GB / 2 threads / 80% RAM / password protection'
  & $sevenZip a '-t7z' '-mx=9' '-m0=LZMA2' '-md=1024m' '-mfb=64' '-ms=16g' '-mmt=2' '-mmemuse=p80' '-pnoon' '-mhe=on' $archiveTemp '.\bin'
  if($LASTEXITCODE -ne 0){throw "Signed-bin compression failed: $LASTEXITCODE"}
  & $sevenZip t '-pnoon' $archiveTemp
  if($LASTEXITCODE -ne 0){throw "Signed-bin archive verification failed: $LASTEXITCODE"}
 } finally {Pop-Location}
 Move-Item -LiteralPath $archiveTemp -Destination $archive -Force
 Write-Host "Signed bin: $output. Next: embed this signed bin into noon.exe."
 Write-Host "Reusable signed-bin archive: $archive (password: noon)."
} finally {
 if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
 if(Test-Path -LiteralPath $archiveTemp){Remove-Item -LiteralPath $archiveTemp -Force}
}
