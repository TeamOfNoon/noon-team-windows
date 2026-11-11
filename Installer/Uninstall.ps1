param([Parameter(Mandatory=$true)][string]$InstallRoot)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
# Uninstall.bat enters here. Request UAC before any file/registry removal.
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$principal=New-Object Security.Principal.WindowsPrincipal($identity)
if(!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){
 try {
  # Launch PowerShell directly: paths are passed as quoted arguments, never cmd code.
  $arguments='-NoProfile -ExecutionPolicy Bypass -File "'+$PSCommandPath+'" -InstallRoot "'+$InstallRoot+'"'
  $elevated=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -Verb RunAs -ArgumentList $arguments -WorkingDirectory $env:TEMP -Wait -PassThru -ErrorAction Stop
  exit $elevated.ExitCode
 } catch {
  [Windows.Forms.MessageBox]::Show('Administrator approval was cancelled or unavailable. Nothing was removed. / تم إلغاء طلب صلاحيات المسؤول أو تعذر الحصول عليها. لم تُحذف أي ملفات.','Noon uninstall / إزالة نون') | Out-Null
  exit 1223
 }
}
Add-Type -AssemblyName System.Drawing
$root=[IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$edition=Get-Content -LiteralPath (Join-Path $root '.noon-edition') -Raw
if($edition -notin @('NTAPP_MN','NTAPP_SN')){throw 'Invalid installer edition marker'}
$appType=$edition.Substring(6);$installName=$appType+'_NT'
$key='HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\NoonDesktop_'+$appType
try {
 $registered=(Get-ItemProperty -LiteralPath $key).InstallLocation
 if([IO.Path]::GetFullPath($registered).TrimEnd('\') -ine $root){throw 'This is not the registered Noon folder.'}
 if(!(Test-Path -LiteralPath (Join-Path $root '.noon-files'))){throw 'Uninstall file list is missing.'}
} catch {[Windows.Forms.MessageBox]::Show($_.Exception.Message,'Noon uninstall') | Out-Null;exit 1}
if([Windows.Forms.MessageBox]::Show('Remove Noon? Close the app first. Personal browser data will be kept.','Noon uninstall','YesNo','Question') -ne 'Yes'){exit 0}
$form=New-Object Windows.Forms.Form
$form.Text='Uninstall Noon / إزالة نون';$form.ClientSize=New-Object Drawing.Size(510,160);$form.StartPosition='CenterScreen';$form.FormBorderStyle='FixedDialog';$form.MaximizeBox=$false;$form.ControlBox=$false
$label=New-Object Windows.Forms.Label;$label.Text='Removing Noon... / جار إزالة نون...';$label.SetBounds(20,20,470,35);$form.Controls.Add($label)
$bar=New-Object Windows.Forms.ProgressBar;$bar.Style='Marquee';$bar.SetBounds(20,80,470,22);$form.Controls.Add($bar)
$worker={param($root,$key,$installName)
 $ErrorActionPreference='Stop';Set-Location -LiteralPath $env:TEMP
 if((Get-Item -LiteralPath $root).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked installation folder refused.'}
 $list=Get-Content -LiteralPath (Join-Path $root '.noon-files') -Encoding UTF8
 foreach($relative in $list){
  if(!$relative -or [IO.Path]::IsPathRooted($relative) -or $relative.Contains(':')){throw 'Unsafe uninstall path.'}
  $target=[IO.Path]::GetFullPath((Join-Path $root $relative));if(!$target.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe uninstall path.'}
  $walk=$root;foreach($part in ($relative -split '[\\/]')){$walk=Join-Path $walk $part;if((Test-Path -LiteralPath $walk) -and ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Linked uninstall path refused.'}}
  if(@('Uninstall.bat','Uninstall.ps1','.noon-edition') -contains $relative){continue}
  if(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target -Force}
 }
 $shell=New-Object -ComObject WScript.Shell
 $desktop=[Environment]::GetFolderPath('Desktop');$programs=[Environment]::GetFolderPath('Programs')
 foreach($link in @((Join-Path $desktop ($installName+'.lnk')),(Join-Path $programs ($installName+'\Noon.lnk')))){if(Test-Path -LiteralPath $link){$shortcut=$shell.CreateShortcut($link);if($shortcut.TargetPath -ieq (Join-Path $root 'Noon.exe')){Remove-Item -LiteralPath $link -Force}}}
 $uninstallLink=Join-Path $programs ($installName+'\Uninstall Noon - إزالة نون.lnk')
 if(Test-Path -LiteralPath $uninstallLink){$shortcut=$shell.CreateShortcut($uninstallLink);if($shortcut.TargetPath -ieq (Join-Path $root 'Uninstall.bat')){Remove-Item -LiteralPath $uninstallLink -Force}}
 $menuFolder=Join-Path $programs $installName;if(Test-Path -LiteralPath $menuFolder){Remove-Item -LiteralPath $menuFolder -Recurse -Force}
 foreach($name in @('.noon-files','.noon-install','.noon-edition','Uninstall.bat','Uninstall.ps1')){$file=Join-Path $root $name;if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file -Force}}
 Get-ChildItem -LiteralPath $root -Recurse -Force | Where-Object {$_.PSIsContainer} | Sort-Object {$_.FullName.Length} -Descending | ForEach-Object {if(!(Get-ChildItem -LiteralPath $_.FullName -Force | Select-Object -First 1)){Remove-Item -LiteralPath $_.FullName -Force}}
 Remove-Item -LiteralPath $key -Recurse -Force
 if(!(Get-ChildItem -LiteralPath $root -Force | Select-Object -First 1)){Remove-Item -LiteralPath $root -Force -ErrorAction SilentlyContinue}
}
$runspace=[System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace();$runspace.Open();$ps=[System.Management.Automation.PowerShell]::Create();$ps.Runspace=$runspace;[void]$ps.AddScript($worker.ToString());[void]$ps.AddArgument($root);[void]$ps.AddArgument($key);[void]$ps.AddArgument($installName);$operation=$ps.BeginInvoke()
$timer=New-Object Windows.Forms.Timer;$timer.Interval=150
$timer.Add_Tick({if($operation.IsCompleted){$timer.Stop();$message='Noon removed. Personal browser data and extra files were kept.';try{$ps.EndInvoke($operation) | Out-Null;if($ps.HadErrors){throw ($ps.Streams.Error | Out-String)}}catch{$message='Removal failed. Close Noon and try again. '+$_.Exception.Message};[Windows.Forms.MessageBox]::Show($form,$message,'Noon uninstall','OK') | Out-Null;$form.Close()}})
$timer.Start();[void]$form.ShowDialog();$timer.Dispose();$ps.Dispose();$runspace.Dispose()
