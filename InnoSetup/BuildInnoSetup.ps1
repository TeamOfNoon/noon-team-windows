param([Parameter(Mandatory=$true)][string]$AppExe,[Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$CompilerPath,[string]$AppVersion='1.0.0.1',[string]$AppTitle='')
$ErrorActionPreference='Stop'
$AppExe=[IO.Path]::GetFullPath($AppExe)
if(!(Test-Path -LiteralPath $AppExe)){throw "Built application EXE not found: $AppExe"}
. (Join-Path $PSScriptRoot '../GetAppType.ps1')
$appType=Get-NoonAppType $AppExe
if(!$AppTitle -or $AppTitle -eq 'Noon'){$AppTitle=if($appType -eq 'SN'){'Shamela NooN شاملة نون'}else{'Mawso3a NooN موسوعة نون'}}
$candidates=@($CompilerPath,$env:INNO_SETUP_ISCC)
foreach($root in @(${env:ProgramFiles(x86)},$env:ProgramFiles,$env:LOCALAPPDATA)){
 if($root){foreach($version in @('7','6')){$candidates+=Join-Path $root "Inno Setup $version\ISCC.exe";$candidates+=Join-Path $root "Programs\Inno Setup $version\ISCC.exe"}}
}
$command=Get-Command ISCC.exe -ErrorAction SilentlyContinue;if($command){$candidates+=$command.Source}
$compiler=$candidates | Where-Object {$_ -and (Test-Path -LiteralPath $_ -PathType Leaf)} | Select-Object -First 1
if(!$compiler){throw 'Install Inno Setup 6.3+ or 7 from https://jrsoftware.org/isdl.php . Or set InnoSetupCompilerPath in InnoSetupSettings.props / INNO_SETUP_ISCC.'}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
# Separate AppDir avoids file-path / .. ambiguity in the optional config entry.
$appDir=Split-Path $AppExe
$editionSession=Join-Path ([IO.Path]::GetTempPath()) ('Noon-Edition-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $editionSession | Out-Null
$editionFile=Join-Path $editionSession 'edition.bin'
try {
 [IO.File]::WriteAllBytes($editionFile,[Text.Encoding]::ASCII.GetBytes('NTAPP_'+$appType))
& $compiler "/DEditionFile=$editionFile" "/DAppType=$appType" "/DAppExe=$AppExe" "/DAppDir=$appDir" "/DSetupOutput=$([IO.Path]::GetFullPath($OutputDirectory))" "/DAppVersion=$AppVersion" "/DAppTitle=$AppTitle" (Join-Path $PSScriptRoot 'Noon.iss')
if($LASTEXITCODE -ne 0){throw "Inno Setup compiler failed: $LASTEXITCODE"}
} finally {Remove-Item -LiteralPath $editionSession -Recurse -Force}

Write-Host "Installer created: $(Join-Path $OutputDirectory ($appType+'_NT_Setup.exe'))"
