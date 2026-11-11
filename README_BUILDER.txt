PASSWORD-GATED GUI
Run Noon.exe --gui, or OPEN_BUILDER_GUI.bat.
The password dialog appears BEFORE the builder window. Password: noon.
Cancel or a wrong password leaves the builder closed. The same gate applies
to the seven-logo-click shortcut from About. --gui is intentionally absent
from --help. --build and --buildgui are no longer supported.

Building and extraction are available only inside the unlocked GUI. Both run
on background workers with an animated loader. There is no console build
process or build command; the GUI calls the packaging function directly.
Extraction has no second password prompt. Its destination picker starts beside
the EXE and creates Noon_Extracted_Web without overwriting existing folders.

Choose embedded/external mode, web folder, output EXE, browser, port, config
path, name, version, copyright, ICO and PNG. Empty embedded folder reuses
existing embedded files. External output contains no web assets. Output
defaults beside the EXE; web folder defaults to adjacent www.

Runtime options for normal app launching remain supported. --help lists only
those runtime options; it has no builder examples or GUI command.
Rebuild Release x64 / v142, then regenerate older EXEs to get these changes.
