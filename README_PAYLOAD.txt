Build the solution in Visual Studio Release, Win32 or x64 as before.
Place your original web files in app/ with dir.html at its root.
The existing index wrapper conversion is retained.
Web assets are appended using the SAME NHTMLPK1 payload format as the GUI builder.
They are no longer standard PE RCDATA resources or a ZIP archive.
Runtime reads them from the EXE without extracting an app folder.
This is packaging, not encryption: a custom extractor can recover the files.
Existing compiler/linker settings are unchanged. The uploaded template uses
dynamic MFC/CRT in Release x64 and static MFC/CRT in Release Win32.
PowerShell is used at build time only, as in the original project.
Incremental builds replace the previous payload instead of duplicating it.
Sign the final EXE only after the append step.
Source/XML checked here; actual Windows compilation and runtime not tested.

Edit ProductInfo.props for all product branding and version settings.
NoonVersion must contain four numeric parts, e.g. 1.2.0.0.
Copyright year defaults to 2026. Rebuild to regenerate the resource header.
Use plain values without double quotes or backslashes in branding fields.
