; Inno Setup script for the AudioForge Windows installer.
;
; Normally run through scripts/build_installer.sh, which first builds the
; self-contained app folder (scripts/deploy_windows.sh) and then calls:
;
;   ISCC /DAppVersion=1.4.0 /DSourceDir=<dist folder> installer\audioforge.iss
;
; Settings live in %APPDATA%\AudioForge and are deliberately left alone on
; uninstall, so reinstalling/upgrading keeps the library, playlists and EQ.

#ifndef AppVersion
  #define AppVersion "1.4.1"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist"
#endif

#define AppName "AudioForge"
#define AppExe  "audioforge.exe"

[Setup]
; Never change AppId -- it's how upgrades find the existing install.
AppId={{736233D9-7BFA-41B4-AA7F-0F6CBDB17A31}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Biasnil
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; Lets the user pick "install just for me" (no admin prompt) or all users.
PrivilegesRequiredOverridesAllowed=dialog
; Qt 6 needs 64-bit Windows 10 1809 or newer (Windows 11 included; ARM PCs run it
; through x64 emulation). Older systems get a clear message instead of an app that won't start.
MinVersion=10.0.17763
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\resources\audioforge.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
OutputDir=Output
OutputBaseFilename=AudioForge-Setup-{#AppVersion}
; Closes a running AudioForge before overwriting its files.
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
