# Noon Desktop

Noon archive viewer for Windows. The Visual Studio solution contains the MFC launcher, the local Java server and the installer projects.

| Location | Contents |
| --- | --- |
| `MfcEdgeDemo.sln` | Visual Studio entry point |
| `NoonJava/` | Local server, search and bin signing tools |
| `Installer/`, `Uninstaller/`, `InnoSetup/` | Installation and removal projects |
| `NT/`, `templets/`, `res/` | Configuration, templates and resources |

## Build on Windows

1. Install Visual Studio 2019 with Desktop development with C++, MFC, MSVC v142 and Windows SDK 10.0.19041.0. Visual Studio 2022 can also open the solution with these components installed.
2. Set `GRAALVM_HOME` to a Windows x64 GraalVM JDK 17 installation with Native Image.
3. Restore the two NuGet packages from the solution directory with `nuget restore MfcEdgeDemo.sln -PackagesDirectory packages`, or use **Restore NuGet Packages** in Visual Studio.
4. Copy the original `MfcEdgeDemo/NWRuntime/` folder from `Noon_Desktop_Default_Bin_Folder.zip` next to the solution. The build embeds its NW.js 0.8.0 ia32 files.
5. Open `MfcEdgeDemo.sln` and build **Release | x64**. For a development build, signing creates or reuses a key in the sibling `NoonKeys/` directory. For an existing release, use its original key by setting `NOON_SIGNING_PRIVATE_KEY` to the key's absolute path. See `SigningSettings.example.properties` for file-based overrides.

The archive payload is supplied separately. Place the runtime `bin/` beside the built EXE. See `README_SIGNED_BIN.txt` for packaging and `README_NWJS.txt` for browser configuration.

See `THIRD_PARTY_NOTICES.txt` for attribution and the original project's rights notice.

## Initial GitHub upload

Create an empty GitHub repository, then run these commands from the solution directory. Replace `OWNER` with your GitHub username or organization.

```sh
git init -b main
git add .
git commit -m "Import Noon Desktop"
git remote add origin https://github.com/OWNER/noon-desktop.git
git push -u origin main
```
