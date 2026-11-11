#include "pch.h"
#include "AppRuntimePaths.h"
#include "NWRuntimePaths.h"
#include "BrowserLaunch.h"
#include "AppBuilder.h"
#include "NoonServer.h"
#include "WebView2.h"
#include <shellapi.h>
#include <TlHelp32.h>
#include <cstdint>
#include <fstream>
#include <regex>
#include <filesystem>
#include <vector>
#include <cctype>
#include <stdexcept>
#pragma comment(lib,"shell32.lib")
namespace {
std::wstring mode=L"auto", error;
bool showNavigation=false,showMenu=false;
struct File { unsigned id; const wchar_t* name; };
const File files[]={{401,L"credits.html"},
{402,L"ffmpegsumo.dll"},
{403,L"icudt.dll"},
{404,L"libEGL.dll"},
{405,L"libGLESv2.dll"},
{406,L"nw.exe"},
{407,L"nw.pak"},
{408,L"app.ico"},
{409,L"app.png"}};
std::wstring ExePath(){wchar_t b[32768]{};GetModuleFileNameW(nullptr,b,32768);return b;}
}
std::wstring BrowserLaunchError(){return error;}
bool BrowserIsAuto(){return mode==L"auto";}
bool ShowNavigationBar(){return showNavigation;}
bool ShowMenuBar(){return showMenu;}
bool ReadBrowserConfig(){
 error.clear();showNavigation=false;showMenu=false;
 mode=BuiltOption("webview",L"auto");
 std::filesystem::path base=std::filesystem::path(ExePath()).parent_path();
 std::filesystem::path config=std::filesystem::path(BuiltOption("config-path",L"bin/config.js"));
 int argc=0; wchar_t** argv=CommandLineToArgvW(GetCommandLineW(),&argc);
 bool explicitPath=false;
 for(int i=1;argv && i<argc;++i){
  std::wstring arg=argv[i];
  if(arg==L"--config" || arg==L"--config-path"){
   if(i+1==argc){error=L"The --config option requires a file path.";LocalFree(argv);return false;}
   config=argv[++i];explicitPath=true;
  }else if(arg.rfind(L"--config=",0)==0){config=arg.substr(9);explicitPath=true;}
 }
 if(argv)LocalFree(argv);
 if(config.is_relative())config=base/config;
 std::ifstream input(config,std::ios::binary);
 if(!input){if(explicitPath){error=L"Cannot read configuration: "+config.wstring();return false;}return true;}
 std::string text((std::istreambuf_iterator<char>(input)),{});
 auto flag=[&](const std::string& key){std::regex re("(?:\""+key+"\"|'"+key+"'|\\b"+key+")"+R"rx(\s*:\s*(true|false))rx",std::regex::icase);std::smatch found;if(!std::regex_search(text,found,re))return false;std::string v=found[1];for(char& c:v)c=static_cast<char>(tolower(static_cast<unsigned char>(c)));return v=="true";};
 showNavigation=flag("show_navigation_bar") || flag("show_toolbar");showMenu=flag("show_menu_bar");
 // Accept JSON or NT_CONFIG JavaScript object properties; never execute config code.
 std::regex property(R"rx((?:"webview_type"|'webview_type'|\bwebview_type)\s*:\s*["']([^"']+)["'])rx",std::regex::icase);
 std::smatch match;
 if(!HasRuntimeOption("webview") && std::regex_search(text,match,property)){
  std::string value=match[1];for(char& c:value)c=static_cast<char>(tolower(static_cast<unsigned char>(c)));
  mode.assign(value.begin(),value.end());
  if(mode==L"nw" || mode==L"node-webkit")mode=L"nwjs";
  if(mode!=L"auto" && mode!=L"webview2" && mode!=L"nwjs"){error=L"webview_type must be auto, webview2 or nwjs.";return false;}
 }
 return true;
}
bool UseNWBrowser(){
 if(mode==L"nwjs")return true;
 if(mode==L"webview2")return false;
 LPWSTR version=nullptr;HRESULT hr=GetAvailableCoreWebView2BrowserVersionString(nullptr,&version);
 bool available=SUCCEEDED(hr) && version && *version;if(version)CoTaskMemFree(version);
 return !available;
}
bool LaunchNWBrowser(){
 error.clear();
 std::filesystem::path tempRoot;try{tempRoot=AppRuntimeFolder(L"temp");}catch(const std::exception&){error=L"Cannot create app temp folder.";return false;}
 std::filesystem::path dir;
 for(unsigned n=0;n<100;++n){
  dir=tempRoot/(L"nw-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64())+L"-"+std::to_wstring(n));
  if(CreateDirectoryW(dir.c_str(),nullptr))break;
  if(n==99 || GetLastError()!=ERROR_ALREADY_EXISTS){error=L"Cannot create NW.js temporary folder.";return false;}
 }
 auto cleanup=[&](){std::error_code ec;std::filesystem::remove_all(dir,ec);};
 for(const auto& f:files){
  HRSRC res=FindResourceW(nullptr,MAKEINTRESOURCEW(f.id),RT_RCDATA);
  DWORD size=res?SizeofResource(nullptr,res):0;
  HGLOBAL mem=res?LoadResource(nullptr,res):nullptr;void* data=mem?LockResource(mem):nullptr;
  HANDLE out=CreateFileW((dir/f.name).c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);
  DWORD written=0;bool ok=data && size && out!=INVALID_HANDLE_VALUE && WriteFile(out,data,size,&written,nullptr) && written==size;
  if(out!=INVALID_HANDLE_VALUE)CloseHandle(out);
  if(!ok){error=L"Cannot extract NW.js file: "+std::wstring(f.name);cleanup();return false;}
 }
 // A local shell handles NW.js lifecycle; actual web assets still come from HTTP.
 std::wstring url=NoonServerUrl()+L"__startup/";
 std::ofstream manifest(dir/L"package.json",std::ios::binary);
 manifest<<"{\"name\":"<<JsonString(NWPackageName(AppRuntimeRoot().filename().wstring()))<<",\"main\":\"launcher.html\",\"nodejs\":true,\"single-instance\":false,\"user-agent\":\"Mozilla/5.0 Chrome/30.0.1599.101 Safari/537.36 NoonNW/0.8\",\"window\":{\"icon\":\"app.png\",\"title\":"<<JsonString(BuiltOption("name",L"Noon"))<<",\"toolbar\":"<<(ShowNavigationBar()?"true":"false")<<",\"width\":1100,\"height\":800}}";
 manifest.close();if(!manifest){error=L"Cannot write NW.js manifest.";cleanup();return false;}
 std::ofstream shell(dir/L"launcher.html",std::ios::binary);
 shell << R"html(<!doctype html><html><head><meta charset="utf-8"><title>Noon</title>
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#fff}#app{border:0;width:100%;height:100%;display:none}#status{padding:24px;font:16px sans-serif}</style></head>
<body><div id="status">Waiting for local server...</div><iframe id="app" name="noonAppHost" nwdisable></iframe><script>
(function(){
 var gui=require('nw.gui'),fs=require('fs'),path=require('path'),http=require('http');
 var win=gui.Window.get(),closing=false,deadline=Date.now()+15000,timer=null,request=null;
 var marker=path.join(process.cwd(),'normal-close');
 win.on('close',function(){
  if(closing)return;closing=true;
  if(timer)clearTimeout(timer);if(request)request.abort();
  try{fs.writeFileSync(marker,'user-close');}catch(e){}
  // Stop the displayed page while its HTTP server is still alive.
  document.getElementById('app').src='about:blank';
  win.close(true);
 });
 var url=)html" << JsonString(url) << ";var parentExe=" << JsonString(ExePath()) << ";var showMenu=" << (ShowMenuBar()?"true":"false") << R"html(;
 var origin=url.match(/^https?:\/\/[^\/]+/)[0];
 function about(){
  try{var child=require('child_process').spawn(parentExe,['--aboutgui'],{stdio:'ignore'});
   child.on('error',function(err){alert('Cannot open About App / تعذر فتح حول التطبيق: '+err.message);});
   child.on('exit',function(code){if(code)alert('About App exited with code '+code);});
  }catch(err){alert('Cannot open About App: '+err.message);}
 }
 function clearData(all){
  if(!confirm(all?'Clear all app data, including settings and bookmarks? / مسح جميع البيانات بما فيها الإعدادات والعلامات؟':'Clear cache and browsing history? / مسح التخزين المؤقت وسجل التصفح؟'))return;
  try{fs.writeFileSync(path.join(process.cwd(),'clear-data'),all?'all':'cache');
   if(win.isDevToolsOpen && win.isDevToolsOpen())win.closeDevTools();
   closing=true;if(timer)clearTimeout(timer);if(request)request.abort();
   document.getElementById('app').src='about:blank';
   gui.App.quit();}
  catch(err){alert('Cleanup failed / فشل المسح: '+err.message);}
 }
 window.addEventListener('message',function(e){
  if(e.origin!==origin || !e.data)return;
  if(e.data.type==='nt-language-choice' && (e.data.lang==='ar' || e.data.lang==='en')){
   var app=document.getElementById('app');if(e.source===app.contentWindow){app.src=app.src;}return;
  }
  if(e.data.noonContext!==true)return;
  var a=e.data.action,value=String(e.data.value||'');
  if(a==='inspect'){try{win.showDevTools('app');}catch(err){alert('Cannot open Developer Tools / تعذر فتح أدوات المطور: '+err.message);}}
  else if(a==='clear-cache')clearData(false);
  else if(a==='clear-all')clearData(true);
  else if(a==='server-port' && /^[0-9]+$/.test(value) && Number(value)>0 && Number(value)<=65535){
   var returnPath=String(e.data.path||'/www/index.html?page=settings');
   if(returnPath.charAt(0)!=='/' || returnPath.charAt(1)==='/' || returnPath.indexOf(String.fromCharCode(92))>=0)return;
   origin='http://127.0.0.1:'+Number(value);url=origin+returnPath;document.getElementById('app').src=url;
  }
  else if(a==='about')about();
  else if(a==='external' && /^(https?:|mailto:)/i.test(value))gui.Shell.openExternal(value);
  else if(a==='copy')gui.Clipboard.get().set(value,'text');
 },false);
 if(showMenu){var menu=new gui.Menu({type:'menubar'});menu.append(new gui.MenuItem({label:'About App / حول التطبيق',click:about}));win.menu=menu;}

 function retry(){
  if(closing)return;
  if(Date.now()>=deadline){document.getElementById('status').innerHTML='Local server did not respond. Close this window and try again.';return;}
  timer=setTimeout(check,200);
 }
 function check(){
  if(closing)return;
  var finished=false;
  function finish(ok){if(finished)return;finished=true;request=null;if(closing)return;
   if(ok){document.getElementById('status').style.display='none';var frame=document.getElementById('app');frame.src=url;frame.style.display='block';}
   else retry();
  }
  var probe=origin+'/__session/boot';
  var req=http.get(probe,function(res){res.resume();finish(res.statusCode===200);});request=req;
  req.on('error',function(){finish(false);});req.setTimeout(1000,function(){req.abort();finish(false);});
 }
 check();
})();
</script></body></html>)html";
 shell.close();if(!shell){error=L"Cannot write NW.js launcher.";cleanup();return false;}
 std::filesystem::path nwProfile;std::vector<wchar_t> childEnvironment;
 try{
  nwProfile=AppRuntimeFolder(L"cache")/L"NWJS";std::filesystem::create_directories(nwProfile);
  auto temporary=dir/L"temp";std::filesystem::create_directories(temporary);
  auto inherited=GetEnvironmentStringsW();if(!inherited)throw std::runtime_error("Cannot read process environment");
  try{childEnvironment=NWEnvironment(inherited,temporary.wstring());}catch(...){FreeEnvironmentStringsW(inherited);throw;}
  FreeEnvironmentStringsW(inherited);
 }catch(const std::exception&){error=L"Cannot prepare NW.js app temp/cache folders.";cleanup();return false;}
 restartNW:
 std::wstring exe=(dir/L"nw.exe").wstring();std::wstring cmd=L"\""+exe+L"\""+NWProfileArguments(nwProfile)+L" \""+dir.wstring()+L"\"";
 STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION process{};
 if(!CreateProcessW(exe.c_str(),&cmd[0],nullptr,nullptr,FALSE,CREATE_UNICODE_ENVIRONMENT,childEnvironment.data(),dir.c_str(),&startup,&process)){
  error=L"Cannot launch NW.js (Windows error "+std::to_wstring(GetLastError())+L").";cleanup();return false;
 }
 CloseHandle(process.hThread);
 // Keep the in-process HTTP server alive until the NW.js browser exits.
 while(true){
  DWORD wait=MsgWaitForMultipleObjects(1,&process.hProcess,FALSE,INFINITE,QS_ALLINPUT);
  if(wait==WAIT_OBJECT_0)break;
  if(wait==WAIT_FAILED){WaitForSingleObject(process.hProcess,INFINITE);break;}
  MSG msg;while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){if(msg.message!=WM_QUIT){TranslateMessage(&msg);DispatchMessageW(&msg);}}
 }
 DWORD code=0;GetExitCodeProcess(process.hProcess,&code);CloseHandle(process.hProcess);
 bool userClosed=GetFileAttributesW((dir/L"normal-close").c_str())!=INVALID_FILE_ATTRIBUTES;
 // Preserve abnormal exit information even when it happened after an explicit close.
 if(code!=0){
  std::ofstream log(tempRoot/L"nw-exit.log",std::ios::app);
  log<<"pid="<<GetCurrentProcessId()<<" exit="<<code<<" explicit_close="<<(userClosed?1:0)<<"\n";
 }
 if(GetFileAttributesW((dir/L"clear-data").c_str())!=INVALID_FILE_ATTRIBUTES){
  std::ifstream requestFile(dir/L"clear-data");std::string kind;std::getline(requestFile,kind);requestFile.close();
  std::error_code ec;
  // Chromium child processes can briefly retain database locks after the main process exits.
  for(int attempt=0;attempt<50;++attempt){
   ec.clear();
   if(kind=="all")std::filesystem::remove_all(nwProfile,ec);
   else if(kind=="cache"){
    for(auto base:{nwProfile,nwProfile/L"Default"}){
     for(auto name:{L"Cache",L"GPUCache",L"Application Cache",L"History",L"History-journal",L"History-wal",L"History-shm",L"Visited Links",L"Current Session",L"Current Tabs",L"Last Session",L"Last Tabs"}){
      std::filesystem::remove_all(base/name,ec);if(ec)break;
     }if(ec)break;
    }
   }else {ec=std::make_error_code(std::errc::invalid_argument);break;}
   if(!ec)break;
   MsgWaitForMultipleObjects(0,nullptr,FALSE,100,QS_ALLINPUT);
   MSG pending;while(PeekMessageW(&pending,nullptr,0,0,PM_REMOVE)){if(pending.message!=WM_QUIT){TranslateMessage(&pending);DispatchMessageW(&pending);}}
  }
  bool failed=bool(ec);
  if(failed)::MessageBoxW(nullptr,L"Browser data could not be fully cleared. Close other Noon windows and retry. The browser will reopen.\nتعذر مسح جميع البيانات. أغلق النوافذ الأخرى وأعد المحاولة. سيعاد فتح المتصفح.",L"Noon - Cleanup failed",MB_OK|MB_ICONERROR);
  else ::MessageBoxW(nullptr,L"Browser data cleared. The browser will reopen.\nتم مسح البيانات. سيعاد فتح المتصفح.",L"Noon",MB_OK|MB_ICONINFORMATION);
  std::error_code markerError;std::filesystem::remove(dir/L"clear-data",markerError);std::filesystem::remove(dir/L"normal-close",markerError);
  if(markerError){error=L"Cannot remove cleanup marker.";cleanup();return false;}
  goto restartNW;
 }
 cleanup();
 if(code!=0 && !userClosed){error=L"NW.js exited with code "+std::to_wstring(code)+L".";return false;}return true;
}

// Called before starting either browser or the local server.
bool ClearBothBrowserData(std::wstring& report){
 try{
  wchar_t local[32768]{};DWORD length=GetEnvironmentVariableW(L"LOCALAPPDATA",local,32768);
  if(!length || length>=32768)throw std::runtime_error("Cannot locate browser data");
  std::vector<std::filesystem::path> roots={AppRuntimeRoot()/L"cache"/L"WebView2",AppRuntimeRoot()/L"cache"/L"NWJS"};
  // Check both profiles before deleting either. Running browsers lock their databases.
  for(const auto& root:roots){
   if(!std::filesystem::exists(root))continue;
   if(std::filesystem::is_symlink(root))throw std::runtime_error("Browser profile is a symbolic link; cleanup refused");
   for(const auto& entry:std::filesystem::recursive_directory_iterator(root)){
    if(entry.is_symlink())continue;
    if(!entry.is_regular_file())continue;
    HANDLE file=CreateFileW(entry.path().c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE)throw std::runtime_error("Browser data is locked or inaccessible. Close all Noon browser windows and retry");
    CloseHandle(file);
   }
  }
  for(const auto& root:roots)std::filesystem::remove_all(root);
  report=L"All WebView2 and NW.js browser data cleared. / تم مسح جميع بيانات المتصفحين";return true;
 }catch(const std::exception& e){std::string text=e.what();int n=MultiByteToWideChar(CP_UTF8,0,text.data(),(int)text.size(),nullptr,0);report.resize(n);if(n)MultiByteToWideChar(CP_UTF8,0,text.data(),(int)text.size(),&report[0],n);return false;}
}

// The mutex is acquired before starting the server and retained until process exit.
// Builder/About commands run before this check, so their existing behavior is kept.
bool AcquireNoonAppInstance(){
 static HANDLE instance=nullptr;
 if(instance)return true;
 auto identity=ExePath();CharLowerBuffW(&identity[0],static_cast<DWORD>(identity.size()));
 uint64_t hash=14695981039346656037ULL;
 for(wchar_t c:identity){hash^=static_cast<unsigned short>(c);hash*=1099511628211ULL;}
 auto name=L"Local\\NoonApp-"+std::to_wstring(hash);
 HANDLE candidate=CreateMutexW(nullptr,FALSE,name.c_str());
 if(!candidate){::MessageBoxW(nullptr,L"Cannot check whether Noon is already running. / تعذر التحقق من تشغيل نون.",L"Noon",MB_OK|MB_ICONERROR);return false;}
 if(GetLastError()!=ERROR_ALREADY_EXISTS){instance=candidate;return true;}
 CloseHandle(candidate);
 // Locate the parent's MFC window or its direct NW.js child window.
 std::vector<DWORD> owners,windows;
 HANDLE snapshot=CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS,0);
 if(snapshot!=INVALID_HANDLE_VALUE){
  PROCESSENTRY32W entry{};entry.dwSize=sizeof(entry);
  if(Process32FirstW(snapshot,&entry))do{
   if(entry.th32ProcessID==GetCurrentProcessId())continue;
   HANDLE process=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,entry.th32ProcessID);
   if(process){wchar_t path[32768]{};DWORD length=32768;
    if(QueryFullProcessImageNameW(process,0,path,&length)&&_wcsicmp(path,identity.c_str())==0)owners.push_back(entry.th32ProcessID);
    CloseHandle(process);
   }
  }while(Process32NextW(snapshot,&entry));
  windows=owners;
  if(Process32FirstW(snapshot,&entry))do{
   for(DWORD owner:owners)if(entry.th32ParentProcessID==owner&&_wcsicmp(entry.szExeFile,L"nw.exe")==0){windows.push_back(entry.th32ProcessID);break;}
  }while(Process32NextW(snapshot,&entry));
  CloseHandle(snapshot);
 }
 EnumWindows([](HWND window,LPARAM parameter)->BOOL{
  auto targets=reinterpret_cast<std::vector<DWORD>*>(parameter);DWORD pid=0;GetWindowThreadProcessId(window,&pid);
  if(!IsWindowVisible(window)||GetWindow(window,GW_OWNER))return TRUE;
  for(DWORD target:*targets)if(pid==target){
   if(IsIconic(window))ShowWindow(window,SW_RESTORE);
   SetForegroundWindow(window);return FALSE;
  }
  return TRUE;
 },reinterpret_cast<LPARAM>(&windows));
 return false;
}
