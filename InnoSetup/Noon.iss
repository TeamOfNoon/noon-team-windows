#ifndef AppExe
  #error AppExe must point to the finished application EXE
#endif
#ifndef AppDir
  #define AppDir ExtractFileDir(AppExe)
#endif
#ifndef SetupOutput
  #define SetupOutput "output"
#endif
#ifndef AppVersion
  #define AppVersion "1.0.0.1"
#endif
#ifndef AppType
  #define AppType "MN"
#endif
#ifndef AppTitle
  #if AppType == "SN"
    #define AppTitle "Shamela NooN شاملة نون"
  #else
    #define AppTitle "Mawso3a NooN موسوعة نون"
  #endif
#endif
#ifndef EditionFile
  #error EditionFile must point to the generated edition marker
#endif
[Setup]
AppId=NoonTeam.Noon.Desktop.{#AppType}
AppName={#AppTitle}
AppVersion={#AppVersion}
AppPublisher=noon-team
AppPublisherURL=https://nasser-alyamani.org/
DefaultDirName={localappdata}\Programs\{#AppType}_NT
DefaultGroupName={#AppType}_NT
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=6.1
DisableWelcomePage=yes
DisableDirPage=no
DisableProgramGroupPage=yes
UsePreviousTasks=no
OutputDir={#SetupOutput}
OutputBaseFilename={#AppType}_NT_Setup
SetupIconFile=..\res\MfcEdgeDemo.ico
UninstallDisplayIcon={app}\Noon.exe
UninstallDisplayName={#AppTitle}
Compression=lzma2/ultra64
LZMAUseSeparateProcess=yes
LZMADictionarySize=1048576
LZMANumFastBytes=64
CompressionThreads=2
LZMANumBlockThreads=1
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "Desktop shortcut / اختصار سطح المكتب"; Flags: checkedonce
Name: "startmenuicon"; Description: "Start Menu shortcut / اختصار قائمة ابدأ"; Flags: checkedonce
[Files]
Source: "{#EditionFile}"; DestDir: "{app}"; DestName: ".noon-edition"; Flags: ignoreversion
Source: "{#AppExe}"; DestDir: "{app}"; DestName: "Noon.exe"; Flags: ignoreversion
Source: "{#AppDir}\bin\config.js"; DestDir: "{app}\bin"; Flags: skipifsourcedoesntexist onlyifdoesntexist uninsneveruninstall
[Icons]
Name: "{autodesktop}\{#AppType}_NT"; Filename: "{app}\Noon.exe"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{autoprograms}\{#AppType}_NT\Noon"; Filename: "{app}\Noon.exe"; WorkingDir: "{app}"; Tasks: startmenuicon
Name: "{autoprograms}\{#AppType}_NT\Uninstall Noon"; Filename: "{uninstallexe}"; Tasks: startmenuicon
[Code]
var
  ChoicePage: TInputOptionWizardPage;
procedure InitializeWizard();
begin
  ChoicePage := CreateInputOptionPage(wpWelcome,
    '{#AppTitle}',
    'Install with defaults or customize / تثبيت افتراضي أو تخصيص',
    'Recommended folder / المجلد الموصى به: ' + ExpandConstant('{localappdata}\Programs\{#AppType}_NT') + #13#10 +
    'Default installation creates Desktop and Start Menu shortcuts.', True, False);
  ChoicePage.Add('Install with defaults / تثبيت افتراضي');
  ChoicePage.Add('Customize folder and shortcuts / تخصيص المجلد والاختصارات');
  ChoicePage.SelectedValueIndex := 0;
end;
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if ChoicePage.SelectedValueIndex = 0 then
    Result := (PageID = wpSelectDir) or (PageID = wpSelectTasks) or (PageID = wpReady);
end;
function NextButtonClick(CurPageID: Integer): Boolean;
var
  Edition: AnsiString;
  Marker: String;
begin
  Result := True;
  if (CurPageID = wpSelectDir) or (CurPageID = wpReady) or (CurPageID = ChoicePage.ID) then begin
    Marker := AddBackslash(WizardDirValue) + '.noon-edition';
    if FileExists(Marker) then begin
      if not LoadStringFromFile(Marker, Edition) then begin
        MsgBox('Cannot read installed app edition.', mbError, MB_OK);
        Result := False;
        Exit;
      end;
      if Edition <> 'NTAPP_{#AppType}' then begin
        MsgBox('This folder belongs to a different app edition. Choose a separate folder.', mbError, MB_OK);
        Result := False;
        Exit;
      end;
    end;
  end;
  if (CurPageID = ChoicePage.ID) and (ChoicePage.SelectedValueIndex = 0) then
    WizardSelectTasks('desktopicon,startmenuicon');
end;
function InitializeSetup(): Boolean;
begin
  Result := True;
  if RegKeyExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\NoonDesktop_{#AppType}') then begin
    MsgBox('Uninstall the old custom-installer edition of Noon from Windows Settings first, then run this setup again.', mbInformation, MB_OK);
    Result := False;
  end;
end;
