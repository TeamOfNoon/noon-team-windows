#include "SourceBase/pch.h"
#include <shlobj.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <commctrl.h>
#include <filesystem>
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
HWND window;HFONT normalFont=nullptr,titleFont=nullptr;bool busy=false;std::thread worker;
const UINT Complete=WM_APP+81;
struct Result{bool ok=false;std::wstring text;};
std::wstring Self(){wchar_t b[32768]{};if(!GetModuleFileNameW(nullptr,b,32768))throw std::runtime_error("Cannot locate installer");return b;}
fs::path Known(REFKNOWNFOLDERID id){PWSTR p=nullptr;HRESULT hr=SHGetKnownFolderPath(id,0,nullptr,&p);if(FAILED(hr))throw std::runtime_error("Cannot locate Windows folder");fs::path result(p);CoTaskMemFree(p);return result;}
const wchar_t* UninstallKey=L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\NoonDesktop";
std::wstring Wide(const std::string& text){int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),(int)text.size(),nullptr,0);if(!n&&!text.empty())throw std::runtime_error("Invalid file list");std::wstring out(n,L'\0');if(n)MultiByteToWideChar(CP_UTF8,0,text.data(),(int)text.size(),&out[0],n);return out;}
std::string Utf8(const std::wstring& text){int n=WideCharToMultiByte(CP_UTF8,0,text.data(),(int)text.size(),nullptr,0,nullptr,nullptr);std::string out(n,'\0');if(n)WideCharToMultiByte(CP_UTF8,0,text.data(),(int)text.size(),&out[0],n,nullptr,nullptr);return out;}
std::wstring Registered(){HKEY k=nullptr;if(RegOpenKeyExW(HKEY_CURRENT_USER,UninstallKey,0,KEY_READ,&k)!=ERROR_SUCCESS)return L"";DWORD type=0,size=0;LONG e=RegQueryValueExW(k,L"InstallLocation",nullptr,&type,nullptr,&size);std::vector<wchar_t>b(size/2+1);if(e==ERROR_SUCCESS&&type==REG_SZ)e=RegQueryValueExW(k,L"InstallLocation",nullptr,&type,(BYTE*)b.data(),&size);RegCloseKey(k);return e==ERROR_SUCCESS&&type==REG_SZ?std::wstring(b.data()):L"";}
bool ValidInstall(const fs::path& root){if(root.empty()||root==root.root_path()||fs::is_symlink(root))return false;std::wstring reg=Registered();if(reg.empty()||_wcsicmp(fs::absolute(reg).lexically_normal().c_str(),fs::absolute(root).lexically_normal().c_str()))return false;std::ifstream mark(root/L".noon-install");std::string line;std::getline(mark,line);return line=="NOON_INSTALL_V1";}
void RemoveShortcut(const fs::path& file,const fs::path& exe){if(!fs::exists(file))return;IShellLinkW* link=nullptr;if(FAILED(CoCreateInstance(CLSID_ShellLink,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&link))))return;IPersistFile* persist=nullptr;wchar_t target[32768]{};bool ours=false;if(SUCCEEDED(link->QueryInterface(IID_PPV_ARGS(&persist)))&&SUCCEEDED(persist->Load(file.c_str(),STGM_READ))&&SUCCEEDED(link->GetPath(target,32768,nullptr,SLGP_RAWPATH)))ours=_wcsicmp(target,exe.c_str())==0;if(persist)persist->Release();link->Release();if(ours)fs::remove(file);}
void RemoveInstalled(const fs::path& root,DWORD parentPid){
 auto tempDirectory=fs::temp_directory_path();if(!SetCurrentDirectoryW(tempDirectory.c_str()))throw std::runtime_error("Cannot switch uninstall working directory");
 HANDLE parent=OpenProcess(SYNCHRONIZE,FALSE,parentPid);if(parent){WaitForSingleObject(parent,10000);CloseHandle(parent);}
 if(!ValidInstall(root))throw std::runtime_error("Invalid Noon installation");
 std::ifstream list(root/L".noon-files",std::ios::binary);if(!list)throw std::runtime_error("Uninstall file list missing");std::vector<fs::path> files;std::string line;
 while(std::getline(list,line)){fs::path relative=Wide(line);if(relative.empty()||relative.is_absolute()||relative.has_root_name())throw std::runtime_error("Unsafe uninstall file path");for(auto& part:relative)if(part==L".."||part==L".")throw std::runtime_error("Unsafe uninstall file path");auto full=root/relative;auto current=root;for(auto& part:relative){current/=part;if(fs::is_symlink(current))throw std::runtime_error("Linked uninstall path refused");}files.push_back(full);}list.close();
 for(auto& file:files){std::error_code ec;for(int i=0;i<30;++i){ec.clear();fs::remove(file,ec);if(!ec)break;Sleep(100);}if(ec)throw std::runtime_error("Close Noon and run uninstall again. Some app files are still in use");}
 RemoveShortcut(Known(FOLDERID_Desktop)/L"Noon.lnk",root/L"Noon.exe");auto menu=Known(FOLDERID_Programs)/L"Noon";RemoveShortcut(menu/L"Noon.lnk",root/L"Noon.exe");if(fs::exists(menu)&&fs::is_empty(menu))fs::remove(menu);
 fs::remove(root/L"Uninstall.exe");fs::remove(root/L".noon-files");fs::remove(root/L".noon-install");
 for(auto& file:files){auto dir=file.parent_path();while(dir!=root&&dir!=dir.root_path()){if(fs::exists(dir)&&fs::is_empty(dir))fs::remove(dir);else break;dir=dir.parent_path();}}
 if(fs::is_empty(root)){std::error_code directoryError;for(int i=0;i<30;++i){directoryError.clear();fs::remove(root,directoryError);if(!directoryError)break;Sleep(100);}}LONG e=RegDeleteTreeW(HKEY_CURRENT_USER,UninstallKey);if(e!=ERROR_SUCCESS&&e!=ERROR_FILE_NOT_FOUND)throw std::runtime_error("Files removed, but installed-app registration could not be removed");
}

void DeleteHelperLater(){
 std::wstring file=Self(),escaped;for(wchar_t c:file){escaped+=c;if(c==L'\'')escaped+=c;}
 std::wstring script=L"Wait-Process -Id "+std::to_wstring(GetCurrentProcessId())+L" -ErrorAction SilentlyContinue; Remove-Item -LiteralPath '"+escaped+L"' -Force -ErrorAction SilentlyContinue";
 const char* alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";auto data=(const unsigned char*)script.data();size_t length=script.size()*sizeof(wchar_t);std::wstring encoded;
 for(size_t i=0;i<length;i+=3){unsigned value=(unsigned)data[i]<<16;if(i+1<length)value|=(unsigned)data[i+1]<<8;if(i+2<length)value|=data[i+2];encoded+=alphabet[(value>>18)&63];encoded+=alphabet[(value>>12)&63];encoded+=i+1<length?alphabet[(value>>6)&63]:'=';encoded+=i+2<length?alphabet[value&63]:'=';}
 wchar_t system[32768]{};if(!GetSystemDirectoryW(system,32768))return;auto exe=fs::path(system)/L"WindowsPowerShell"/L"v1.0"/L"powershell.exe";
 std::wstring command=L"\""+exe.wstring()+L"\" -NoProfile -NonInteractive -EncodedCommand "+encoded;STARTUPINFOW si{};si.cb=sizeof(si);PROCESS_INFORMATION pi{};if(CreateProcessW(exe.c_str(),&command[0],nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&si,&pi)){CloseHandle(pi.hThread);CloseHandle(pi.hProcess);}
}
void ShowUninstallProgress(const fs::path& root,DWORD parentPid);
bool HandleUninstall(){
 int argc=0;LPWSTR* argv=CommandLineToArgvW(GetCommandLineW(),&argc);std::vector<std::wstring> args;for(int i=1;argv&&i<argc;++i)args.push_back(argv[i]);if(argv)LocalFree(argv);if(args.empty()){if(_wcsicmp(fs::path(Self()).filename().c_str(),L"Uninstall.exe")==0)args.push_back(L"--uninstall");else return false;}
 if(args[0]!=L"--uninstall"&&args[0]!=L"--remove")return false;
 try{
  if(args[0]==L"--remove"){if(args.size()!=3)throw std::runtime_error("Invalid uninstall command");ShowUninstallProgress(fs::absolute(args[1]).lexically_normal(),std::stoul(args[2]));DeleteHelperLater();return true;}
  auto root=fs::path(Self()).parent_path();if(!ValidInstall(root))throw std::runtime_error("This is not a registered Noon installation");
  if(MessageBoxW(nullptr,L"Uninstall Noon? Close the app first. Personal browser data will be kept.\nإلغاء تثبيت نون؟ أغلق التطبيق أولا. سيتم الاحتفاظ بالبيانات الشخصية.",L"Noon",MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2)!=IDYES)return true;
  GUID guid{};CoCreateGuid(&guid);wchar_t token[40]{};StringFromGUID2(guid,token,40);auto helper=fs::temp_directory_path()/(std::wstring(L"Noon-Uninstall-")+token+L".exe");fs::copy_file(Self(),helper);
  std::wstring cmd=L"\""+helper.wstring()+L"\" --remove \""+root.wstring()+L"\" "+std::to_wstring(GetCurrentProcessId());STARTUPINFOW si{};si.cb=sizeof(si);PROCESS_INFORMATION pi{};if(!CreateProcessW(helper.c_str(),&cmd[0],nullptr,nullptr,FALSE,0,nullptr,helper.parent_path().c_str(),&si,&pi)){fs::remove(helper);throw std::runtime_error("Cannot start uninstall helper");}CloseHandle(pi.hThread);CloseHandle(pi.hProcess);
 }catch(const std::exception& e){MessageBoxW(nullptr,Wide(e.what()).c_str(),L"Noon - Uninstall",MB_ICONERROR);}return true;
}
struct UninstallState{fs::path root;DWORD parentPid=0;std::thread thread;bool running=true;HFONT font=nullptr;};
LRESULT CALLBACK UninstallProc(HWND w,UINT message,WPARAM wp,LPARAM lp){
 auto state=(UninstallState*)GetWindowLongPtrW(w,GWLP_USERDATA);
 if(message==WM_NCCREATE){state=(UninstallState*)((CREATESTRUCTW*)lp)->lpCreateParams;SetWindowLongPtrW(w,GWLP_USERDATA,(LONG_PTR)state);}
 if(!state)return DefWindowProcW(w,message,wp,lp);
 if(message==WM_CREATE){
  state->font=CreateFontW(-16,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
  HWND label=CreateWindowExW(0,L"STATIC",L"Removing Noon... / جار إزالة نون...",WS_CHILD|WS_VISIBLE,24,24,480,35,w,nullptr,AfxGetInstanceHandle(),nullptr);SendMessageW(label,WM_SETFONT,(WPARAM)state->font,TRUE);
  HWND progress=CreateWindowExW(0,PROGRESS_CLASSW,L"",WS_CHILD|WS_VISIBLE|PBS_MARQUEE,24,80,480,20,w,(HMENU)101,AfxGetInstanceHandle(),nullptr);SendMessageW(progress,PBM_SETMARQUEE,TRUE,35);
  HWND detail=CreateWindowExW(0,L"STATIC",L"Please wait. Personal browser data will be kept.\nيرجى الانتظار. سيتم الاحتفاظ بالبيانات الشخصية.",WS_CHILD|WS_VISIBLE,24,120,480,60,w,nullptr,AfxGetInstanceHandle(),nullptr);SendMessageW(detail,WM_SETFONT,(WPARAM)state->font,TRUE);
  try{state->thread=std::thread([state,w](){auto result=new Result;HRESULT com=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);try{RemoveInstalled(state->root,state->parentPid);result->ok=true;result->text=L"Noon uninstalled. Personal browser data and extra files were kept.\nتم إلغاء تثبيت نون مع الاحتفاظ بالبيانات الشخصية.";}catch(const std::exception& e){result->text=Wide(e.what());}if(SUCCEEDED(com))CoUninitialize();if(!PostMessageW(w,WM_APP+82,0,(LPARAM)result))delete result;});}catch(...){state->running=false;PostMessageW(w,WM_CLOSE,0,0);}return 0;
 }
 if(message==WM_APP+82){if(state->thread.joinable())state->thread.join();state->running=false;SendMessageW(GetDlgItem(w,101),PBM_SETMARQUEE,FALSE,0);auto result=(Result*)lp;MessageBoxW(w,result->text.c_str(),L"Noon - Uninstall",MB_OK|(result->ok?MB_ICONINFORMATION:MB_ICONERROR));delete result;DestroyWindow(w);return 0;}
 if(message==WM_CLOSE){if(!state->running)DestroyWindow(w);return 0;}
 if(message==WM_DESTROY){if(state->font)DeleteObject(state->font);return 0;}return DefWindowProcW(w,message,wp,lp);
}
void ShowUninstallProgress(const fs::path& root,DWORD parentPid){
 INITCOMMONCONTROLSEX controls{sizeof(controls),ICC_PROGRESS_CLASS};InitCommonControlsEx(&controls);UninstallState state;state.root=root;state.parentPid=parentPid;
 WNDCLASSW c{};c.lpfnWndProc=UninstallProc;c.hInstance=AfxGetInstanceHandle();c.lpszClassName=L"NoonUninstallProgress";c.hCursor=::LoadCursorW(nullptr,IDC_ARROW);c.hIcon=::LoadIconW(c.hInstance,MAKEINTRESOURCEW(128));c.hbrBackground=(HBRUSH)(COLOR_BTNFACE+1);RegisterClassW(&c);
 HWND w=CreateWindowExW(WS_EX_DLGMODALFRAME,c.lpszClassName,L"Uninstall Noon / إزالة نون",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX,CW_USEDEFAULT,CW_USEDEFAULT,550,250,nullptr,nullptr,c.hInstance,&state);
 if(w){ShowWindow(w,SW_SHOW);MSG message;while(IsWindow(w)&&GetMessageW(&message,nullptr,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}}else throw std::runtime_error("Cannot show uninstall progress");if(state.thread.joinable())state.thread.join();
}
}
bool RunNoonUninstall(){HRESULT com=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);bool handled=false;try{handled=HandleUninstall();if(!handled)MessageBoxW(nullptr,L"Use Uninstall.exe from the installed Noon folder.",L"Noon",MB_ICONINFORMATION);}catch(const std::exception& e){MessageBoxW(nullptr,Wide(e.what()).c_str(),L"Noon",MB_ICONERROR);}if(SUCCEEDED(com))CoUninitialize();return handled;}
