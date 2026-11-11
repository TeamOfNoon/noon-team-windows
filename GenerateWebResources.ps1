param([Parameter(Mandatory=$true)][string]$JavaExe)
$ErrorActionPreference='Stop'
$root=Join-Path $PSScriptRoot 'gene/bin'
$generated=Join-Path $PSScriptRoot 'GeneratedWebData'
if(!(Test-Path -LiteralPath (Join-Path $root 'NT/app.dat/dir.html'))){throw 'Built bin is missing. BUILD_BIN.bat must finish first.'}
if(Test-Path -LiteralPath $generated){Remove-Item -LiteralPath $generated -Recurse -Force}
New-Item -ItemType Directory -Path $generated | Out-Null
$signingClasspath=(Join-Path $PSScriptRoot 'NoonJava/build/signing')+';'+(Join-Path $PSScriptRoot 'NoonJava/resources')+';'+(Join-Path $PSScriptRoot 'NoonJava/lib/*')
& $JavaExe -cp $signingClasspath VerifySignedBin $root
if($LASTEXITCODE -ne 0){throw 'Refusing to embed bin: signature/hash verification failed.'}
$entries=@();$table=@('// Generated from built bin.', 'struct EmbeddedWebFile { const wchar_t* path; int id; };','static const EmbeddedWebFile embeddedWebFiles[] = {')
$paths=@{};$id=20000
foreach($file in @(Get-ChildItem -LiteralPath $root -Recurse -File | Sort-Object FullName)){
 if($file.Name -ieq 'noon_private.pem'){throw 'Refusing to embed private signing key.'}
 $relative=$file.FullName.Substring($root.Length).TrimStart('\').Replace('\','/')
 # Keep web URLs unchanged. Remaining bin data is private to the native runtime.
 $url=if($relative.StartsWith('NT/app.dat/')){$relative.Substring(11)}else{'__bin/'+$relative}
 if($url.Contains("`t") -or $url.Contains("`r") -or $url.Contains("`n")){throw 'Unsupported control character in bin filename.'}
 if($paths.ContainsKey($url)){throw "Duplicate embedded path: $url"};$paths[$url]=$true
 $entries += [pscustomobject]@{path=('/'+$url);source=('gene/bin/'+$relative)}
 $cpp=$url.Replace('"','\"');$table += "    { L`"/$cpp`", $id },";$id++
}
if(!$entries.Count){throw 'Built bin contains no files.'}
$table+='};'
$utf8=New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'EmbeddedWeb.rc'),"// Built bin is appended to the EXE; no web RCDATA.`r`n",$utf8)
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'EmbeddedWebFiles.h'),$table,$utf8)
ConvertTo-Json -InputObject @($entries) -Depth 4 | Set-Content -LiteralPath (Join-Path $generated 'payload-files.json') -Encoding UTF8
Write-Host "Prepared $($entries.Count) built-bin files for noon.exe."
