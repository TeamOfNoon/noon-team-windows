# Reads build inputs only; never executes the packaged EXE or config.js.
function Get-NoonConfigType([string]$Text) {
 $text=$Text.TrimStart([char]0xFEFF)
 $start=$text.IndexOf('{');$end=$text.LastIndexOf('}')
 if($start -lt 0 -or $end -lt $start){return $null}
 try {$value=($text.Substring($start,$end-$start+1) | ConvertFrom-Json).app_type}
 catch {$match=[regex]::Match($text,'(?i)(?:["'']app_type["'']|\bapp_type)\s*:\s*["''](MN|SN)["'']');$value=if($match.Success){$match.Groups[1].Value}else{$null}}
 if($value -is [string] -and @('MN','SN') -contains $value.ToUpperInvariant()){return $value.ToUpperInvariant()}
 return $null
}
function Get-NoonAppType([string]$AppExe) {
 $AppExe=[IO.Path]::GetFullPath($AppExe);$type='MN';$configPath='NT/config.js'
 if(-not ('NoonBuildResourceReader' -as [type])) {
  Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;using System.Text;
public static class NoonBuildResourceReader {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr LoadLibraryEx(string path,IntPtr file,uint flags);
 [DllImport("kernel32.dll")] static extern IntPtr FindResource(IntPtr module,IntPtr id,IntPtr type);
 [DllImport("kernel32.dll")] static extern IntPtr LoadResource(IntPtr module,IntPtr resource);
 [DllImport("kernel32.dll")] static extern IntPtr LockResource(IntPtr resource);
 [DllImport("kernel32.dll")] static extern uint SizeofResource(IntPtr module,IntPtr resource);
 [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
 public static string Options(string path){IntPtr h=LoadLibraryEx(path,IntPtr.Zero,0x22);if(h==IntPtr.Zero)return "";try{IntPtr r=FindResource(h,(IntPtr)501,(IntPtr)10);if(r==IntPtr.Zero)return "";uint size=SizeofResource(h,r);if(size>4194304)return "";byte[] b=new byte[size];Marshal.Copy(LockResource(LoadResource(h,r)),b,0,b.Length);return Encoding.UTF8.GetString(b);}finally{FreeLibrary(h);}}
}
'@
 }
 $options=[NoonBuildResourceReader]::Options($AppExe)
 foreach($line in ($options -split "`n")) {
  if($line -match '^app-type=(MN|SN)\s*$'){$type=$Matches[1].ToUpperInvariant()}
  if($line.StartsWith('config-path=')){$configPath=$line.Substring(12).TrimEnd("`r")}
 }
 $stream=[IO.File]::OpenRead($AppExe);$reader=New-Object IO.BinaryReader($stream)
 try {
  if($stream.Length -ge 24){
   $stream.Position=$stream.Length-24;$magic=[Text.Encoding]::ASCII.GetString($reader.ReadBytes(8));$offset=$reader.ReadUInt64();$size=$reader.ReadUInt64()
   if($magic -eq 'NHTMLPK1'){
    if($size -gt 67108864 -or $offset -gt ($stream.Length-24) -or $size -ne ($stream.Length-24-$offset)){throw 'Invalid embedded payload manifest'}
    $stream.Position=$offset;$manifest=[Text.Encoding]::UTF8.GetString($reader.ReadBytes([int]$size))
    foreach($line in ($manifest -split "`n")){
     $parts=$line -split "`t";if($parts.Count -ne 3 -or $parts[0] -ne '/__bin/config.js'){continue}
     $at=[UInt64]::Parse($parts[1]);$length=[UInt64]::Parse($parts[2]);if($length -gt 4194304 -or $at -gt $offset -or $length -gt ($offset-$at)){throw 'Invalid embedded config range'}
     $stream.Position=$at;$found=Get-NoonConfigType ([Text.Encoding]::UTF8.GetString($reader.ReadBytes([int]$length)));if($found){$type=$found};break
    }
   }
  }
 } finally {$reader.Dispose()}
 $base=Split-Path $AppExe
 $config=if([IO.Path]::IsPathRooted($configPath)){$configPath}else{Join-Path $base $configPath}
 if(Test-Path -LiteralPath $config -PathType Container){$config=Join-Path $config 'config.js'}
 if(!(Test-Path -LiteralPath $config -PathType Leaf)){$config=Join-Path $base 'NT/config.js'}
 if(Test-Path -LiteralPath $config -PathType Leaf){$found=Get-NoonConfigType (Get-Content -LiteralPath $config -Raw -Encoding UTF8);if($found){$type=$found}}
 return $type
}
