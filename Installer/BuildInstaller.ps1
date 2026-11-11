param([Parameter(Mandatory=$true)][string]$AppExe,[Parameter(Mandatory=$true)][string]$StubExe,[Parameter(Mandatory=$true)][string]$OutputExe)
$ErrorActionPreference='Stop'
$AppExe=[IO.Path]::GetFullPath($AppExe);$StubExe=[IO.Path]::GetFullPath($StubExe);$OutputExe=[IO.Path]::GetFullPath($OutputExe)
. (Join-Path $PSScriptRoot '../GetAppType.ps1')
$appType=Get-NoonAppType $AppExe
$OutputExe=Join-Path (Split-Path $OutputExe) ($appType+'_NT_Setup.exe')
$tools=Join-Path $PSScriptRoot 'Tools'
$session=Join-Path ([IO.Path]::GetTempPath()) ('Noon-Package-'+[Guid]::NewGuid().ToString('N'))
$stage=Join-Path $session 'stage';$archive=Join-Path $session 'Noon.7z';$tmp=$OutputExe+'.new'
try {
 New-Item -ItemType Directory -Path $stage -Force | Out-Null
 Copy-Item -LiteralPath $AppExe -Destination (Join-Path $stage 'Noon.exe')
 # Archive contains only Noon.exe. Setup-only files are embedded RC resources.
 Push-Location $stage
 try {
  Write-Host 'Compressing final app: 7z / Ultra / LZMA2 / 1024 MB / word 64 / solid 16 GB / 2 threads / 80% RAM'
  & (Join-Path $tools '7z.exe') a '-t7z' '-mx=9' '-m0=LZMA2' '-md=1024m' '-mfb=64' '-ms=16g' '-mmt=2' '-mmemuse=p80' $archive '.\*'
  if($LASTEXITCODE -ne 0){throw "7-Zip compression failed: $LASTEXITCODE"}
  & (Join-Path $tools '7z.exe') t $archive
  if($LASTEXITCODE -ne 0){throw "7-Zip archive verification failed: $LASTEXITCODE"}
 } finally {Pop-Location}
 $stubBytes=[IO.File]::ReadAllBytes($StubExe)
 $editionTag=New-Object byte[] 24;[Array]::Copy([Text.Encoding]::ASCII.GetBytes('NEDITIONMN'),$editionTag,10)
 $editionMatches=New-Object 'System.Collections.Generic.List[int]'
 for($i=0;$i -le $stubBytes.Length-24;$i++){$equal=$true;for($j=0;$j -lt 24;$j++){if($stubBytes[$i+$j] -ne $editionTag[$j]){$equal=$false;break}};if($equal){$editionMatches.Add($i)}}
 if($editionMatches.Count -ne 1){throw 'Expected one setup edition resource. Rebuild the installer stub.'}
 [Array]::Copy([Text.Encoding]::ASCII.GetBytes($appType),0,$stubBytes,$editionMatches[0]+8,2)
 $tag=[Text.Encoding]::ASCII.GetBytes('NSETUP01');$matches=New-Object 'System.Collections.Generic.List[int]'
 for($i=0;$i -le $stubBytes.Length-24;$i++){
  if($stubBytes[$i] -ne $tag[0]){continue};$equal=$true
  for($j=1;$j -lt 8;$j++){if($stubBytes[$i+$j] -ne $tag[$j]){$equal=$false;break}}
  if($equal){$zero=$true;for($j=8;$j -lt 24;$j++){if($stubBytes[$i+$j] -ne 0){$zero=$false;break}};if($zero){$matches.Add($i)}}
 }
 if($matches.Count -ne 1){throw 'Expected one empty setup metadata resource. Clean and rebuild the installer stub.'}
 [UInt64]$offset=$stubBytes.Length;[UInt64]$size=(Get-Item -LiteralPath $archive).Length
 [Array]::Copy([BitConverter]::GetBytes($offset),0,$stubBytes,$matches[0]+8,8)
 [Array]::Copy([BitConverter]::GetBytes($size),0,$stubBytes,$matches[0]+16,8)
 $output=[IO.File]::Open($tmp,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try {$output.Write($stubBytes,0,$stubBytes.Length);$data=[IO.File]::OpenRead($archive);try{$data.CopyTo($output)}finally{$data.Dispose()}}finally{$output.Dispose()}
 if((Get-Item -LiteralPath $tmp).Length -ne $offset+$size){throw 'Invalid setup archive range'}
 # Archive is last: no custom footer/trailing bytes to trigger extraction warnings.
 Move-Item -LiteralPath $tmp -Destination $OutputExe -Force
 Write-Host "Installer created: $OutputExe"
} finally {if(Test-Path -LiteralPath $tmp){Remove-Item -LiteralPath $tmp -Force};if(Test-Path -LiteralPath $session){Remove-Item -LiteralPath $session -Recurse -Force}}
