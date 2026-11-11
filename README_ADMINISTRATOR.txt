Administrator execution
=======================
The main Noon EXE uses the highest permissions available to the current account.
BuildSettings.props: Link/UACExecutionLevel = HighestAvailable.
Administrator accounts receive a UAC consent prompt when elevation is needed.
Standard/Guest accounts run normally without an administrator credential request
from the app manifest. Filesystem access remains limited to that account rights.
Builder-generated embedded and external EXEs inherit the parent's PE manifest,
including highestAvailable. Icon/version edits retain the existing manifest.
The setup EXE also requests administrator privileges. The disabled native
uninstaller project is configured consistently if it is later enabled.
NW.js children inherit the elevated parent's token. Vendor runtime EXEs are unchanged.
Rebuild the solution and regenerate existing child EXEs to apply this change.
Previously generated EXEs are not modified automatically.

Administrator access can allow writes to protected folders; it cannot bypass a
file sharing lock held by another process. Close Noon before updating it.
For per-user HKCU registration, approve UAC using the same Windows account.
Supplying another account's credentials changes that account's registry/profile.

Validation: project and manifest XML checked for matching UAC settings.
Windows UAC execution and the VS2019 native build must be verified on Windows.

Uninstall.bat also requests elevation through Uninstall.ps1 before any changes.
The elevated PowerShell process runs the existing uninstall GUI; cancelling UAC
exits without removing anything. Already elevated sessions do not prompt again.
Rebuild setup and install/update once to deploy the revised uninstall scripts.
