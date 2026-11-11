#include "../pch.h"
#include <shlobj.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <commctrl.h>
#include <filesystem>
#include <memory>
#include "InstallTransaction.h"
#include "ExistingInstallation.h"
#include <fstream>
#include <vector>
#include <algorithm>
#include <string>
#include <cwchar>
#include <thread>
#include <stdexcept>
#include <cstdint>
#pragma comment(lib,"shell32.lib")
#pragma comment(lib,"ole32.lib")
#pragma comment(lib,"comctl32.lib")
#pragma comment(lib,"advapi32.lib")
namespace fs=std::filesystem;
namespace {
HWND window;HFONT normalFont=nullptr,titleFont=nullptr;bool busy=false,finished=false,updateMode=false,hasInstalledApp=false,launchAfterClose=false;std::thread worker;
fs::path registeredPath,freshDestination,finishedDestination;
const UINT Complete=WM_APP+81;
struct Result{bool ok=false,updated=false;fs::path destination;std::wstring text;};
std::wstring Self(){wchar_t b[32768]{};if(!GetModuleFileNameW(nullptr,b,32768))throw std::runtime_error("Cannot locate installer");return b;}
fs::path Known(REFKNOWNFOLDERID id){PWSTR p=nullptr;HRESULT hr=SHGetKnownFolderPath(id,0,nullptr,&p);if(FAILED(hr))throw std::runtime_error("Cannot locate Windows folder");fs::path result(p);CoTaskMemFree(p);return result;}
std::wstring Value(int id){HWND c=GetDlgItem(window,id);int n=GetWindowTextLengthW(c);std::vector<wchar_t>b(n+1);GetWindowTextW(c,b.data(),n+1);return b.data();}
uint64_t Read64(std::ifstream& in){unsigned char b[8];if(!in.read((char*)b,8))throw std::runtime_error("Incomplete installer footer");uint64_t v=0;for(int i=7;i>=0;--i)v=(v<<8)|b[i];return v;}
void Copy(std::ifstream& in,std::ofstream& out,uint64_t count){std::vector<char>b(1024*1024);while(count){auto n=(std::streamsize)(std::min<uint64_t>)(count,b.size());if(!in.read(b.data(),n))throw std::runtime_error("Incomplete archive");out.write(b.data(),n);if(!out)throw std::runtime_error("Cannot write extraction module");count-=n;}}
void Shortcut(const fs::path& location,const fs::path& exe){
 IShellLinkW* link=nullptr;HRESULT hr=CoCreateInstance(CLSID_ShellLink,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&link));if(FAILED(hr))throw std::runtime_error("Cannot create shortcut");
 hr=link->SetPath(exe.c_str());if(SUCCEEDED(hr))hr=link->SetWorkingDirectory(exe.parent_path().c_str());if(SUCCEEDED(hr))hr=link->SetIconLocation((exe.extension()==L".bat"?exe.parent_path()/L"Noon.exe":exe).c_str(),0);
 IPersistFile* file=nullptr;if(SUCCEEDED(hr))hr=link->QueryInterface(IID_PPV_ARGS(&file));if(SUCCEEDED(hr))hr=file->Save(location.c_str(),TRUE);if(file)file->Release();link->Release();if(FAILED(hr))throw std::runtime_error("Cannot save shortcut");
}

std::wstring SetupEdition(){auto r=FindResourceW(nullptr,MAKEINTRESOURCEW(6007),RT_RCDATA);if(r&&SizeofResource(nullptr,r)==24){auto data=(const char*)LockResource(LoadResource(nullptr,r));if(data&&data[8]=='S'&&data[9]=='N')return L"SN";}return L"MN";}
std::wstring SetupAppTitle(){return SetupEdition()==L"SN"?L"Shamela NooN شاملة نون":L"Mawso3a NooN موسوعة نون";}
std::wstring InstallName(){return SetupEdition()+L"_NT";}
const std::wstring UninstallKeyText=L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\NoonDesktop_"+SetupEdition();
const wchar_t* UninstallKey=UninstallKeyText.c_str();
std::wstring Wide(const std::string& text){int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),(int)text.size(),nullptr,0);if(!n&&!text.empty())throw std::runtime_error("Invalid file list");std::wstring out(n,L'\0');if(n)MultiByteToWideChar(CP_UTF8,0,text.data(),(int)text.size(),&out[0],n);return out;}
std::string Utf8(const std::wstring& text){int n=WideCharToMultiByte(CP_UTF8,0,text.data(),(int)text.size(),nullptr,0,nullptr,nullptr);std::string out(n,'\0');if(n)WideCharToMultiByte(CP_UTF8,0,text.data(),(int)text.size(),&out[0],n,nullptr,nullptr);return out;}
std::wstring RegistryInstallLocation(const wchar_t* name,REGSAM view){
 HKEY key=nullptr;if(RegOpenKeyExW(HKEY_CURRENT_USER,name,0,KEY_READ|view,&key)!=ERROR_SUCCESS)return L"";
 DWORD type=0,size=0;auto status=RegQueryValueExW(key,L"InstallLocation",nullptr,&type,nullptr,&size);std::vector<wchar_t> value;
 if(status==ERROR_SUCCESS&&(type==REG_SZ||type==REG_EXPAND_SZ)&&size<=65536){value.resize(size/sizeof(wchar_t)+1);status=RegQueryValueExW(key,L"InstallLocation",nullptr,&type,(BYTE*)value.data(),&size);}else status=ERROR_INVALID_DATA;
 RegCloseKey(key);if(status!=ERROR_SUCCESS||value.empty())return L"";
 if(type==REG_EXPAND_SZ){DWORD count=ExpandEnvironmentStringsW(value.data(),nullptr,0);if(!count||count>32768)return L"";std::vector<wchar_t> expanded(count);if(!ExpandEnvironmentStringsW(value.data(),expanded.data(),count))return L"";return expanded.data();}
 return value.data();
}
const wchar_t* LegacyUninstallKey=L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\NoonDesktop";
std::wstring Registered(){
 for(auto view:{REGSAM(KEY_WOW64_64KEY),REGSAM(KEY_WOW64_32KEY)}){auto path=RegistryInstallLocation(UninstallKey,view);if(IsExistingInstall(path,SetupEdition(),true))return path;}
 // Legacy shared registration is used only when its files identify this edition.
 for(auto view:{REGSAM(KEY_WOW64_64KEY),REGSAM(KEY_WOW64_32KEY)}){auto path=RegistryInstallLocation(LegacyUninstallKey,view);if(IsExistingInstall(path,SetupEdition()))return path;}
 auto programs=Known(FOLDERID_LocalAppData)/L"Programs";
 for(const auto& path:{programs/InstallName(),programs/L"Noon"})if(IsExistingInstall(path,SetupEdition()))return path.wstring();
 return L"";
}
void RegisterInstall(const fs::path& root){
 HKEY k=nullptr;if(RegCreateKeyExW(HKEY_CURRENT_USER,UninstallKey,0,nullptr,0,KEY_WRITE,nullptr,&k,nullptr)!=ERROR_SUCCESS)throw std::runtime_error("Cannot register installed app");
 auto text=[&](const wchar_t* name,const std::wstring& value){return RegSetValueExW(k,name,0,REG_SZ,(const BYTE*)value.c_str(),(DWORD)((value.size()+1)*sizeof(wchar_t)))==ERROR_SUCCESS;};
 bool ok=text(L"DisplayName",SetupAppTitle())&&text(L"Publisher",L"noon-team")&&text(L"DisplayVersion",L"1.0.0.1")&&text(L"InstallLocation",root.wstring())&&text(L"DisplayIcon",(root/L"Noon.exe").wstring()+L",0")&&text(L"UninstallString",L"cmd.exe /d /c \"\""+(root/L"Uninstall.bat").wstring()+L"\"\"")&&text(L"URLInfoAbout",L"https://nasser-alyamani.org/");
 DWORD one=1;ok=ok&&RegSetValueExW(k,L"NoModify",0,REG_DWORD,(BYTE*)&one,sizeof(one))==ERROR_SUCCESS&&RegSetValueExW(k,L"NoRepair",0,REG_DWORD,(BYTE*)&one,sizeof(one))==ERROR_SUCCESS;RegCloseKey(k);if(!ok)throw std::runtime_error("Cannot finish installed-app registration");
}
void WriteSetupFile(const fs::path& target,unsigned id){
 HRSRC resource=FindResourceW(nullptr,MAKEINTRESOURCEW(id),RT_RCDATA);if(!resource)throw std::runtime_error("Setup file resource missing");auto memory=LoadResource(nullptr,resource);auto data=LockResource(memory);DWORD size=SizeofResource(nullptr,resource);if(!data||!size)throw std::runtime_error("Invalid setup file resource");
 std::ofstream out(target,std::ios::binary);out.write((const char*)data,size);out.close();if(!out)throw std::runtime_error("Cannot write setup file");
}
void WriteInstallManifest(const fs::path& stage){
 if(!fs::is_regular_file(stage/L"Uninstall.bat"))throw std::runtime_error("Uninstall BAT missing");
 std::ofstream list(stage/L".noon-files",std::ios::binary);if(!list)throw std::runtime_error("Cannot write uninstall file list");
 for(const auto& e:fs::recursive_directory_iterator(stage))if(e.is_regular_file()&&e.path().filename()!=L".noon-files"&&e.path().filename()!=L"Uninstall.exe")list<<Utf8(fs::relative(e.path(),stage).generic_wstring())<<'\n';
 list.close();if(!list)throw std::runtime_error("Cannot finish uninstall file list");std::ofstream mark(stage/L".noon-install",std::ios::binary);mark<<"NOON_INSTALL_V1\n";mark.close();if(!mark)throw std::runtime_error("Cannot write installation marker");
}

struct RegistryValue {std::wstring name;DWORD type=0;std::vector<BYTE> data;};
struct RegistrationSnapshot {
 bool exists=false;std::vector<RegistryValue> values;
 RegistrationSnapshot(){
  HKEY key=nullptr;LONG error=RegOpenKeyExW(HKEY_CURRENT_USER,UninstallKey,0,KEY_READ,&key);
  if(error==ERROR_FILE_NOT_FOUND)return;if(error!=ERROR_SUCCESS)throw std::runtime_error("Cannot read installed-app registration");
  exists=true;DWORD count=0,maxName=0,maxData=0;
  error=RegQueryInfoKeyW(key,nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,&count,&maxName,&maxData,nullptr,nullptr);
  if(error!=ERROR_SUCCESS){RegCloseKey(key);throw std::runtime_error("Cannot back up installed-app registration");}
  for(DWORD i=0;i<count;++i){std::vector<wchar_t> name(maxName+2);std::vector<BYTE> data(maxData+2);DWORD names=(DWORD)name.size(),bytes=(DWORD)data.size(),type=0;
   error=RegEnumValueW(key,i,name.data(),&names,nullptr,&type,data.data(),&bytes);
   if(error!=ERROR_SUCCESS){RegCloseKey(key);throw std::runtime_error("Cannot back up installed-app registration");}
   data.resize(bytes);values.push_back({std::wstring(name.data(),names),type,std::move(data)});
  }RegCloseKey(key);
 }
 void restore()const{
  LONG error=RegDeleteTreeW(HKEY_CURRENT_USER,UninstallKey);
  if(error!=ERROR_SUCCESS&&error!=ERROR_FILE_NOT_FOUND&&error!=ERROR_PATH_NOT_FOUND)throw std::runtime_error("Cannot restore registration");
  if(!exists)return;HKEY key=nullptr;
  if(RegCreateKeyExW(HKEY_CURRENT_USER,UninstallKey,0,nullptr,0,KEY_WRITE,nullptr,&key,nullptr)!=ERROR_SUCCESS)throw std::runtime_error("Cannot restore registration");
  for(const auto& value:values)if(RegSetValueExW(key,value.name.c_str(),0,value.type,value.data.empty()?nullptr:value.data.data(),(DWORD)value.data.size())!=ERROR_SUCCESS){RegCloseKey(key);throw std::runtime_error("Cannot restore registration");}
  RegCloseKey(key);
 }
};
fs::path NormalPath(const fs::path& path){return fs::weakly_canonical(fs::absolute(path)).lexically_normal();}
bool SamePath(const fs::path& a,const fs::path& b){auto x=NormalPath(a).wstring(),y=NormalPath(b).wstring();return CompareStringOrdinal(x.c_str(),-1,y.c_str(),-1,TRUE)==CSTR_EQUAL;}
bool Within(const fs::path& child,const fs::path& parent){auto x=NormalPath(child).wstring(),y=NormalPath(parent).wstring();if(y.back()!=L'\\')y+=L'\\';return x.size()>=y.size()&&CompareStringOrdinal(x.c_str(),(int)y.size(),y.c_str(),(int)y.size(),TRUE)==CSTR_EQUAL;}
bool ManagedNoon(const fs::path& folder){std::ifstream marker(folder/L".noon-install");std::string text;std::getline(marker,text);if(!text.empty()&&text.back()=='\r')text.pop_back();return text=="NOON_INSTALL_V1"&&fs::is_regular_file(folder/L"Noon.exe");}
void CheckFolder(const fs::path& folder){
 if(fs::is_regular_file(folder/L".noon-edition")){std::ifstream marker(folder/L".noon-edition",std::ios::binary);std::string value((std::istreambuf_iterator<char>(marker)),{});std::string expected=SetupEdition()==L"SN"?"NTAPP_SN":"NTAPP_MN";if(value!=expected)throw std::runtime_error("This folder belongs to a different app edition. Choose its separate installation folder");}
 if(folder==folder.root_path())throw std::runtime_error("Choose an application folder, not a drive root");
 for(const auto& id:{FOLDERID_Windows,FOLDERID_System,FOLDERID_Profile,FOLDERID_Desktop,FOLDERID_Documents,FOLDERID_ProgramFiles,FOLDERID_LocalAppData,FOLDERID_Programs})if(SamePath(folder,Known(id)))throw std::runtime_error("Choose a dedicated Noon folder, not a Windows or personal folder");
 if(fs::exists(folder)&&(!fs::is_directory(folder)||fs::is_symlink(folder)))throw std::runtime_error("Choose a regular application folder");
}
void CheckUnlocked(const fs::path& folder){
 if(!fs::exists(folder))return;
 DWORD attributes=GetFileAttributesW(folder.c_str());if(attributes!=INVALID_FILE_ATTRIBUTES&&(attributes&FILE_ATTRIBUTE_REPARSE_POINT))throw std::runtime_error("Linked installation folders are not supported");
 for(const auto& entry:fs::recursive_directory_iterator(folder)){
  DWORD value=GetFileAttributesW(entry.path().c_str());if(entry.is_symlink()||(value!=INVALID_FILE_ATTRIBUTES&&(value&FILE_ATTRIBUTE_REPARSE_POINT)))throw std::runtime_error("Linked folders or files found. Choose another installation folder");
  if(!entry.is_regular_file())continue;
  HANDLE lock=CreateFileW(entry.path().c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
  if(lock==INVALID_HANDLE_VALUE)throw std::runtime_error("Close Noon and other programs using the installation folder, then try again");CloseHandle(lock);
 }
}
void RemoveOldShortcut(const fs::path& shortcut,const fs::path& previous){
 if(previous.empty()||!fs::exists(shortcut))return;
 IShellLinkW* link=nullptr;if(FAILED(CoCreateInstance(CLSID_ShellLink,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&link))))return;
 IPersistFile* file=nullptr;wchar_t target[32768]{};HRESULT hr=link->QueryInterface(IID_PPV_ARGS(&file));if(SUCCEEDED(hr))hr=file->Load(shortcut.c_str(),STGM_READ);if(SUCCEEDED(hr))hr=link->GetPath(target,32768,nullptr,SLGP_RAWPATH);if(file)file->Release();link->Release();
 if(SUCCEEDED(hr)&&target[0]&&(SamePath(target,previous/L"Noon.exe")||SamePath(target,previous/L"Uninstall.bat"))){std::error_code ignored;fs::remove(shortcut,ignored);}
}

Result Install(fs::path destination,bool desktop,bool start,bool updating){
 Result result;result.updated=updating;fs::path temp,stage;std::unique_ptr<NoonInstallTransaction> transaction;std::unique_ptr<RegistrationSnapshot> registration;bool registryTouched=false;
 HRESULT com=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
 try{
  registration=std::make_unique<RegistrationSnapshot>();
  std::wstring previousText=Registered();fs::path previous=previousText.empty()?fs::path():NormalPath(previousText);
  destination=NormalPath(destination);CheckFolder(destination);
  if(updating&&(previous.empty()||!fs::is_regular_file(previous/L"Noon.exe")||!SamePath(destination,previous)))throw std::runtime_error("The detected installation changed. Close setup and open it again");
  fs::path removePrevious;
  if(!updating&&!previous.empty()&&fs::exists(previous)&&!SamePath(destination,previous)){
   CheckFolder(previous);
   if(Within(destination,previous)||Within(previous,destination))throw std::runtime_error("New and previous installation folders must not contain each other");
   if(ManagedNoon(previous))removePrevious=previous;
   else if(fs::is_regular_file(previous/L"Noon.exe"))throw std::runtime_error("Previous installation has no Noon installation marker. Update in place instead");
  }
  if(Within(Self(),destination)||(!removePrevious.empty()&&Within(Self(),removePrevious)))throw std::runtime_error("Run setup from outside the Noon installation folder");
  fs::create_directories(destination.parent_path());
  wchar_t token[40]{};GUID guid{};if(FAILED(CoCreateGuid(&guid)))throw std::runtime_error("Cannot create setup session");StringFromGUID2(guid,token,40);
  temp=fs::temp_directory_path()/(std::wstring(L"Noon-Setup-")+token);fs::create_directory(temp);
  stage=destination.parent_path()/(std::wstring(L"Noon-Installing-")+token);fs::create_directory(stage);
  std::ifstream in(Self(),std::ios::binary);if(!in)throw std::runtime_error("Cannot read installer");in.seekg(0,std::ios::end);uint64_t total=(uint64_t)in.tellg();if(total<24)throw std::runtime_error("Installer has no archive");
  HRSRC metadata=FindResourceW(nullptr,MAKEINTRESOURCEW(6002),RT_RCDATA);if(!metadata||SizeofResource(nullptr,metadata)!=24)throw std::runtime_error("Installer metadata missing");auto metadataHandle=LoadResource(nullptr,metadata);auto info=(const unsigned char*)LockResource(metadataHandle);if(!info)throw std::runtime_error("Cannot read installer metadata");uint64_t offset=0,size=0;memcpy(&offset,info+8,8);memcpy(&size,info+16,8);if(memcmp(info,"NSETUP01",8)||offset>total||size!=total-offset||!size)throw std::runtime_error("Invalid installer archive");
  HRSRC r=FindResourceW(nullptr,MAKEINTRESOURCEW(6001),RT_RCDATA);if(!r)throw std::runtime_error("Extraction module missing");auto mem=LoadResource(nullptr,r);auto bytes=LockResource(mem);DWORD length=SizeofResource(nullptr,r);if(!bytes || !length)throw std::runtime_error("Invalid extraction module");
  fs::path extractor=temp/L"extract.exe";std::ofstream out(extractor,std::ios::binary);out.write((const char*)bytes,length);in.seekg(offset);Copy(in,out,size);out.close();if(!out)throw std::runtime_error("Cannot finish extraction module");
  std::wstring command=L"\""+extractor.wstring()+L"\" x -y -o\""+stage.wstring()+L"\"";
  STARTUPINFOW si{};si.cb=sizeof(si);PROCESS_INFORMATION pi{};if(!CreateProcessW(extractor.c_str(),&command[0],nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,temp.c_str(),&si,&pi))throw std::runtime_error("Cannot start 7-Zip extraction");
  CloseHandle(pi.hThread);DWORD wait=WaitForSingleObject(pi.hProcess,INFINITE),exit=1;GetExitCodeProcess(pi.hProcess,&exit);CloseHandle(pi.hProcess);if(wait!=WAIT_OBJECT_0 || exit)throw std::runtime_error("7-Zip extraction failed or was cancelled");
  fs::path app=stage/L"Noon.exe";if(!fs::is_regular_file(app))throw std::runtime_error("Installed EXE missing");
  {std::ofstream edition(stage/L".noon-edition",std::ios::binary);edition<<"NTAPP_"<<(SetupEdition()==L"SN"?"SN":"MN");edition.close();if(!edition)throw std::runtime_error("Cannot save installed edition marker");}
  WriteSetupFile(stage/L"Uninstall.bat",6003);WriteSetupFile(stage/L"Uninstall.ps1",6004);
  WriteSetupFile(stage/L"7-Zip-License.txt",6005);WriteSetupFile(stage/L"7-Zip-Notice.txt",6006);
  WriteInstallManifest(stage);

  CheckUnlocked(destination);if(!removePrevious.empty())CheckUnlocked(removePrevious);
  if(updating&&fs::is_directory(destination/L"NT"/L"lucene")){fs::create_directories(stage/L"NT");fs::copy(destination/L"NT"/L"lucene",stage/L"NT"/L"lucene",fs::copy_options::recursive);}
  transaction=std::make_unique<NoonInstallTransaction>(destination,stage,removePrevious);transaction->install(token);
  registryTouched=true;LONG removed=RegDeleteTreeW(HKEY_CURRENT_USER,UninstallKey);
  if(removed!=ERROR_SUCCESS&&removed!=ERROR_FILE_NOT_FOUND&&removed!=ERROR_PATH_NOT_FOUND)throw std::runtime_error("Cannot replace previous app registration");
  RegisterInstall(destination);std::wstring warnings;
  for(const auto& remaining:transaction->commit())warnings+=L"\nOld installation backup could not be removed / تعذر حذف النسخة الاحتياطية القديمة:\n"+remaining.wstring();
  // Retire only the legacy registration that points to this successfully updated app.
  if(!previous.empty())for(auto view:{REGSAM(KEY_WOW64_64KEY),REGSAM(KEY_WOW64_32KEY)}){auto legacy=RegistryInstallLocation(LegacyUninstallKey,view);if(!legacy.empty()&&SamePath(legacy,previous)){auto removedLegacy=RegDeleteKeyExW(HKEY_CURRENT_USER,LegacyUninstallKey,view,0);if(removedLegacy!=ERROR_SUCCESS&&removedLegacy!=ERROR_FILE_NOT_FOUND)warnings+=L"\nThe old app registration could not be removed.";}}
  try{
   if(!previous.empty()){
    RemoveOldShortcut(Known(FOLDERID_Desktop)/(InstallName()+L".lnk"),previous);auto menu=Known(FOLDERID_Programs)/InstallName();
    RemoveOldShortcut(menu/L"Noon.lnk",previous);RemoveOldShortcut(menu/L"Uninstall Noon - إزالة نون.lnk",previous);
    if(fs::is_directory(menu)&&fs::is_empty(menu))fs::remove(menu);
   }
   if(desktop)Shortcut(Known(FOLDERID_Desktop)/(InstallName()+L".lnk"),destination/L"Noon.exe");
   if(start){auto menu=Known(FOLDERID_Programs)/InstallName();fs::create_directories(menu);Shortcut(menu/L"Noon.lnk",destination/L"Noon.exe");Shortcut(menu/L"Uninstall Noon - إزالة نون.lnk",destination/L"Uninstall.bat");}
  }catch(...){warnings+=L"\nSome shortcuts could not be updated / تعذر تحديث بعض الاختصارات.";}
  result.ok=true;result.destination=destination;result.text=(updating?L"Updated / تم التحديث:\n":L"Installed / تم التثبيت:\n")+destination.wstring()+warnings;
 }catch(const std::exception& error){
  if(transaction&&transaction->committed()){
   result.ok=true;result.destination=destination;result.text=L"Installation completed, but a final setup step failed. / اكتمل التثبيت لكن تعذرت خطوة نهائية.";
  }else{
  result.text=Wide(error.what());
  bool restored=!transaction||transaction->rollback();
  if(!restored)result.text+=L"\nPrevious files could not all be restored / تعذر استعادة بعض الملفات السابقة.";
  if(transaction&&!transaction->destinationBackup().empty())result.text+=L"\nBackup / نسخة احتياطية: "+transaction->destinationBackup().wstring();
  if(transaction&&!transaction->previousBackup().empty())result.text+=L"\nPrevious app backup / نسخة التطبيق السابق: "+transaction->previousBackup().wstring();
  if(registryTouched&&registration)try{registration->restore();}catch(...){result.text+=L"\nCould not restore previous registry entry / تعذر استعادة سجل التثبيت السابق.";}
  }
 }
 std::error_code ignored;if(!stage.empty())fs::remove_all(stage,ignored);if(!temp.empty())fs::remove_all(temp,ignored);if(SUCCEEDED(com))CoUninitialize();return result;
}

void Control(const wchar_t* cls,const wchar_t* label,DWORD style,int id,int x,int y,int width,int height){auto h=CreateWindowExW(0,cls,label,WS_CHILD|WS_VISIBLE|style,x,y,width,height,window,(HMENU)(INT_PTR)id,AfxGetInstanceHandle(),nullptr);SendMessageW(h,WM_SETFONT,(WPARAM)(normalFont?normalFont:GetStockObject(DEFAULT_GUI_FONT)),TRUE);}
void ApplyMode(bool updating){
 if(!updateMode&&!finished&&GetDlgItem(window,101))freshDestination=Value(101);
 updateMode=updating&&hasInstalledApp;
 SendMessageW(GetDlgItem(window,130),BM_SETCHECK,updateMode?BST_CHECKED:BST_UNCHECKED,0);SendMessageW(GetDlgItem(window,131),BM_SETCHECK,updateMode?BST_UNCHECKED:BST_CHECKED,0);
 auto target=updateMode?registeredPath:freshDestination;SetWindowTextW(GetDlgItem(window,101),target.c_str());SendMessageW(GetDlgItem(window,101),EM_SETREADONLY,updateMode,0);EnableWindow(GetDlgItem(window,102),!updateMode&&!busy);
 SetWindowTextW(GetDlgItem(window,114),SetupAppTitle().c_str());
 SetWindowTextW(GetDlgItem(window,105),updateMode?L"Update / تحديث":L"Install / تثبيت");
 SetWindowTextW(GetDlgItem(window,108),updateMode?L"Update uses the detected installation folder. / سيُستخدم مجلد التثبيت المكتشف.":L"Fresh install replaces the selected folder contents after success.\nالتثبيت الجديد يستبدل محتويات المجلد المحدد بعد النجاح.");
}
void Browse(){if(updateMode||busy)return;IFileDialog* dlg=nullptr;if(FAILED(CoCreateInstance(CLSID_FileOpenDialog,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&dlg))))return;DWORD flags=0;dlg->GetOptions(&flags);dlg->SetOptions(flags|FOS_PICKFOLDERS|FOS_FORCEFILESYSTEM);dlg->SetTitle(L"Choose parent folder / اختر المجلد الأب");if(SUCCEEDED(dlg->Show(window))){IShellItem* item=nullptr;if(SUCCEEDED(dlg->GetResult(&item))){PWSTR path=nullptr;if(SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH,&path))){freshDestination=fs::path(path)/InstallName();SetWindowTextW(GetDlgItem(window,101),freshDestination.c_str());CoTaskMemFree(path);}item->Release();}}dlg->Release();}
void LaunchInstalledUninstall(){
 if(busy||finished||!hasInstalledApp)return;
 try{
  auto current=Registered();
  if(current.empty()||!SamePath(current,registeredPath))throw std::runtime_error("The detected installation changed. Close setup and reopen it");
  bool hasRegistration=false;for(auto view:{REGSAM(KEY_WOW64_64KEY),REGSAM(KEY_WOW64_32KEY)})for(auto key:{UninstallKey,LegacyUninstallKey}){auto path=RegistryInstallLocation(key,view);if(!path.empty()&&SamePath(path,registeredPath))hasRegistration=true;}
  if(fs::is_regular_file(registeredPath/L".noon-edition")){auto registered=RegistryInstallLocation(UninstallKey,0);if(registered.empty()||!SamePath(registered,registeredPath))RegisterInstall(registeredPath);}else if(!hasRegistration)throw std::runtime_error("Installation found, but its old registration is missing. Use Update once to repair registration before uninstalling");
  fs::path script=registeredPath/L"Uninstall.bat";
  if(!fs::is_regular_file(script)){MessageBoxW(window,L"The installed Uninstall.bat is missing. / ملف إزالة التثبيت Uninstall.bat غير موجود.",L"Noon Setup / تثبيت نون",MB_ICONERROR);return;}
  auto working=fs::temp_directory_path().wstring();
  SHELLEXECUTEINFOW launch{};launch.cbSize=sizeof(launch);launch.fMask=SEE_MASK_NOCLOSEPROCESS|SEE_MASK_NOASYNC;launch.hwnd=window;launch.lpVerb=L"open";launch.lpFile=script.c_str();launch.lpDirectory=working.c_str();launch.nShow=SW_SHOWNORMAL;
  if(!ShellExecuteExW(&launch)){DWORD error=GetLastError();std::wstring text=L"Cannot start Uninstall.bat / تعذر تشغيل إزالة التثبيت. Windows error: "+std::to_wstring(error);MessageBoxW(window,text.c_str(),L"Noon Setup",MB_ICONERROR);return;}
  if(launch.hProcess)CloseHandle(launch.hProcess);
  // The existing BAT owns confirmation, progress, shortcut cleanup and registry removal.
  // Exit setup so it cannot keep the installed directory open during removal.
  launchAfterClose=false;DestroyWindow(window);
 }catch(const std::exception& error){MessageBoxW(window,Wide(error.what()).c_str(),L"Noon Setup / تثبيت نون",MB_ICONERROR);}
}
void FinishAndClose(){launchAfterClose=finished&&SendMessageW(GetDlgItem(window,125),BM_GETCHECK,0,0)==BST_CHECKED;DestroyWindow(window);}
void EnableSetup(bool enabled){for(int id:{101,102,103,104,105,106,130,131,132})EnableWindow(GetDlgItem(window,id),enabled);if(enabled)EnableWindow(GetDlgItem(window,102),!updateMode);}
LRESULT CALLBACK Proc(HWND w,UINT message,WPARAM wp,LPARAM lp){
 if(message==WM_CREATE){window=w;normalFont=CreateFontW(-14,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");titleFont=CreateFontW(-23,0,0,0,FW_SEMIBOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
  auto existing=Registered();if(!existing.empty())registeredPath=existing;std::error_code error;hasInstalledApp=!registeredPath.empty()&&fs::is_regular_file(registeredPath/L"Noon.exe",error);freshDestination=Known(FOLDERID_LocalAppData)/L"Programs"/InstallName();
  Control(L"STATIC",L"",SS_ICON,116,24,22,56,56);SendMessageW(GetDlgItem(w,116),STM_SETICON,(WPARAM)::LoadImageW(AfxGetInstanceHandle(),MAKEINTRESOURCEW(128),IMAGE_ICON,48,48,LR_SHARED),0);
  Control(L"STATIC",SetupAppTitle().c_str(),0,114,96,22,590,34);SendMessageW(GetDlgItem(w,114),WM_SETFONT,(WPARAM)titleFont,TRUE);
  Control(L"STATIC",hasInstalledApp?L"Installed app detected / تم العثور على تطبيق مثبت":L"Install on this computer / تثبيت على هذا الجهاز",0,115,96,64,590,28);
  Control(L"EDIT",registeredPath.c_str(),WS_BORDER|ES_AUTOHSCROLL|ES_READONLY,121,24,100,666,28);ShowWindow(GetDlgItem(w,121),hasInstalledApp?SW_SHOW:SW_HIDE);
  Control(L"BUTTON",L"Update existing app / تحديث التطبيق المثبت",BS_AUTORADIOBUTTON|WS_GROUP|WS_TABSTOP,130,24,144,490,27);
  Control(L"BUTTON",L"Fresh install (replace folder) / تثبيت جديد واستبدال المجلد",BS_AUTORADIOBUTTON|WS_TABSTOP,131,24,178,490,27);ShowWindow(GetDlgItem(w,130),hasInstalledApp?SW_SHOW:SW_HIDE);
  Control(L"BUTTON",L"Uninstall / إزالة",WS_TABSTOP,132,526,144,164,32);ShowWindow(GetDlgItem(w,132),hasInstalledApp?SW_SHOW:SW_HIDE);
  Control(L"STATIC",L"Installation folder / مجلد التثبيت",WS_GROUP,111,24,216,666,22);
  Control(L"EDIT",freshDestination.c_str(),WS_BORDER|ES_AUTOHSCROLL|WS_TABSTOP,101,24,240,490,30);Control(L"BUTTON",L"Browse / استعراض",WS_TABSTOP,102,526,240,164,30);
  Control(L"BUTTON",L"Desktop shortcut / اختصار سطح المكتب",BS_AUTOCHECKBOX|WS_TABSTOP|WS_GROUP,103,24,287,666,28);Control(L"BUTTON",L"Start Menu shortcut / اختصار قائمة ابدأ",BS_AUTOCHECKBOX|WS_TABSTOP,104,24,324,666,28);for(int id:{103,104})SendMessageW(GetDlgItem(w,id),BM_SETCHECK,BST_CHECKED,0);
  Control(PROGRESS_CLASSW,L"",PBS_MARQUEE,107,24,372,666,16);ShowWindow(GetDlgItem(w,107),SW_HIDE);
  Control(L"STATIC",L"",0,108,24,399,666,45);
  Control(L"EDIT",L"",ES_MULTILINE|ES_READONLY|WS_VSCROLL,124,24,146,666,200);ShowWindow(GetDlgItem(w,124),SW_HIDE);
  Control(L"BUTTON",L"Open Noon after closing setup / فتح نون بعد إغلاق المثبّت",BS_AUTOCHECKBOX|WS_TABSTOP|WS_GROUP,125,24,365,666,32);SendMessageW(GetDlgItem(w,125),BM_SETCHECK,BST_CHECKED,0);ShowWindow(GetDlgItem(w,125),SW_HIDE);
  Control(L"BUTTON",L"Install / تثبيت",BS_DEFPUSHBUTTON|WS_TABSTOP,105,420,465,170,38);Control(L"BUTTON",L"Cancel / إلغاء",WS_TABSTOP,106,602,465,88,38);
  ApplyMode(hasInstalledApp);return 0;
 }
 if(message==WM_COMMAND){int id=LOWORD(wp);if(busy)return 0;if(finished){if(id==105)FinishAndClose();return 0;}
  if(id==132){LaunchInstalledUninstall();return 0;}
  if(id==130){ApplyMode(true);return 0;}if(id==131){ApplyMode(false);return 0;}if(id==102){Browse();return 0;}if(id==106){DestroyWindow(w);return 0;}
  if(id==105){auto path=updateMode?registeredPath.wstring():Value(101);if(path.empty())return 0;bool desktop=SendMessageW(GetDlgItem(w,103),BM_GETCHECK,0,0)==BST_CHECKED,start=SendMessageW(GetDlgItem(w,104),BM_GETCHECK,0,0)==BST_CHECKED,updating=updateMode;
   busy=true;EnableSetup(false);ShowWindow(GetDlgItem(w,107),SW_SHOW);SendMessageW(GetDlgItem(w,107),PBM_SETMARQUEE,TRUE,35);SetWindowTextW(GetDlgItem(w,108),updating?L"Updating Noon... / جارٍ تحديث نون...":L"Installing Noon... / جارٍ تثبيت نون...");
   try{worker=std::thread([path,desktop,start,updating,w](){auto result=new Result(Install(path,desktop,start,updating));if(!PostMessageW(w,Complete,0,(LPARAM)result))delete result;});}
   catch(...){busy=false;EnableSetup(true);ShowWindow(GetDlgItem(w,107),SW_HIDE);MessageBoxW(w,L"Cannot start installation / تعذر بدء التثبيت",L"Noon",MB_ICONERROR);}
  }return 0;
 }
 if(message==Complete){if(worker.joinable())worker.join();std::unique_ptr<Result> result((Result*)lp);busy=false;ShowWindow(GetDlgItem(w,107),SW_HIDE);SendMessageW(GetDlgItem(w,107),PBM_SETMARQUEE,FALSE,0);
  if(!result->ok){EnableSetup(true);SetWindowTextW(GetDlgItem(w,108),L"Setup failed. Previous installation was retained where possible. / تعذر إكمال التثبيت.");MessageBoxW(w,result->text.c_str(),L"Noon Setup / تثبيت نون",MB_ICONERROR);return 0;}
  finished=true;finishedDestination=result->destination;
  for(int id:{101,102,103,104,106,111,130,131,132})ShowWindow(GetDlgItem(w,id),SW_HIDE);
  SetWindowTextW(GetDlgItem(w,114),result->updated?L"Update complete / اكتمل التحديث":L"Installation complete / اكتمل التثبيت");SetWindowTextW(GetDlgItem(w,115),(SetupAppTitle()+L" - Ready / جاهز").c_str());SetWindowTextW(GetDlgItem(w,121),finishedDestination.c_str());ShowWindow(GetDlgItem(w,121),SW_SHOW);
  SetWindowTextW(GetDlgItem(w,124),result->text.c_str());ShowWindow(GetDlgItem(w,124),SW_SHOW);ShowWindow(GetDlgItem(w,125),SW_SHOW);EnableWindow(GetDlgItem(w,105),TRUE);SetWindowTextW(GetDlgItem(w,105),L"Finish / إنهاء");
  MoveWindow(GetDlgItem(w,108),24,414,666,30,TRUE);SetWindowTextW(GetDlgItem(w,108),L"Click Finish to close setup. / اضغط إنهاء لإغلاق المثبّت.");SetFocus(GetDlgItem(w,105));return 0;
 }
 if(message==WM_CLOSE){if(!busy){if(finished)FinishAndClose();else DestroyWindow(w);}return 0;}
 if(message==WM_DESTROY){if(normalFont)DeleteObject(normalFont);if(titleFont)DeleteObject(titleFont);PostQuitMessage(0);return 0;}return DefWindowProcW(w,message,wp,lp);
}
class InstallerApp:public CWinApp{public:BOOL InitInstance()override{
 CWinApp::InitInstance();HRESULT com=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);INITCOMMONCONTROLSEX cc{sizeof(cc),ICC_PROGRESS_CLASS};InitCommonControlsEx(&cc);WNDCLASSW c{};c.lpfnWndProc=Proc;c.hInstance=AfxGetInstanceHandle();c.lpszClassName=L"NoonInstaller";c.hCursor=::LoadCursorW(nullptr,IDC_ARROW);c.hbrBackground=(HBRUSH)(COLOR_BTNFACE+1);c.hIcon=::LoadIconW(c.hInstance,MAKEINTRESOURCEW(128));RegisterClassW(&c);
 auto w=CreateWindowExW(WS_EX_DLGMODALFRAME,c.lpszClassName,(SetupAppTitle()+L" - Setup / تثبيت").c_str(),WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX,CW_USEDEFAULT,CW_USEDEFAULT,730,565,nullptr,nullptr,c.hInstance,nullptr);
 if(w){ShowWindow(w,SW_SHOW);MSG message;while(GetMessageW(&message,nullptr,0,0)>0){if(!IsDialogMessageW(w,&message)){TranslateMessage(&message);DispatchMessageW(&message);}}}
 if(worker.joinable())worker.join();
 // Launch only after the setup window has closed and the user's checkbox was read.
 if(launchAfterClose&&!finishedDestination.empty()){
  std::wstring exe=(finishedDestination/L"Noon.exe").wstring(),command=L"\""+exe+L"\"";STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION process{};
  if(CreateProcessW(exe.c_str(),&command[0],nullptr,nullptr,FALSE,0,nullptr,finishedDestination.c_str(),&startup,&process)){CloseHandle(process.hThread);CloseHandle(process.hProcess);}
  else MessageBoxW(nullptr,L"Noon could not be opened. Use its shortcut to open it. / تعذر فتح نون. يمكنك فتحه من الاختصار.",L"Noon",MB_ICONERROR);
 }
 if(SUCCEEDED(com))CoUninitialize();return FALSE;
}} app;
}
