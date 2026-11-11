#include "pch.h"
#include "AppRuntimePaths.h"
#include "NoonServer.h"
#include "AppBuilder.h"
#include "PortDialog.h"
#include <filesystem>
#include "NoonJava/dist/NoonJava.h"
#include <string>
#include <fstream>
#include <stdexcept>
#include <utility>
namespace {
HMODULE module=nullptr;
graal_isolatethread_t* thread=nullptr;
decltype(&graal_tear_down_isolate) tearDown=nullptr;
decltype(&noon_java_stop) stopHttp=nullptr;
std::wstring folder, url, errorMessage;
void WindowsError(const wchar_t* stage){
 DWORD code=GetLastError();wchar_t* message=nullptr;
 FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER|FORMAT_MESSAGE_FROM_SYSTEM|FORMAT_MESSAGE_IGNORE_INSERTS,nullptr,code,0,reinterpret_cast<wchar_t*>(&message),0,nullptr);
 errorMessage=std::wstring(stage)+L" (Windows error "+std::to_wstring(code)+L"): "+(message?message:L"Unknown error");
 if(message)LocalFree(message);
 if(code==ERROR_MOD_NOT_FOUND)errorMessage+=L"\nNoonJava.dll requires the Microsoft Visual C++ x64 runtime (VCRUNTIME140.dll and VCRUNTIME140_1.dll).";
}
const wchar_t* files[]={L"NoonJava.dll",L"vcruntime140.dll",L"vcruntime140_1.dll"};
bool Extract(unsigned id,const std::wstring& path){
 HMODULE self=GetModuleHandleW(nullptr);HRSRC res=FindResourceW(self,MAKEINTRESOURCEW(id),RT_RCDATA);
 if(!res)return false;DWORD size=SizeofResource(self,res);HGLOBAL mem=LoadResource(self,res);
 const void* data=mem?LockResource(mem):nullptr;if(!data||!size)return false;
 HANDLE file=CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);
 if(file==INVALID_HANDLE_VALUE)return false;DWORD written=0;
 bool ok=WriteFile(file,data,size,&written,nullptr)&&written==size;CloseHandle(file);return ok;
}
std::string Utf8(const wchar_t* value){
 int count=WideCharToMultiByte(CP_UTF8,0,value,-1,nullptr,0,nullptr,nullptr);
 std::string out(count,'\0');WideCharToMultiByte(CP_UTF8,0,value,-1,&out[0],count,nullptr,nullptr);return out;
}
}
static bool LoadNoonCore(){
 errorMessage.clear();
 if(module)return thread!=nullptr;
 wchar_t exe[32768]{},temp[32768]{};
 DWORD length=GetModuleFileNameW(nullptr,exe,32768);
 if(!length||length>=32768){WindowsError(L"Read EXE path");return false;}
 std::filesystem::path tempRoot;try{tempRoot=AppRuntimeFolder(L"temp");}catch(const std::exception&){errorMessage=L"Cannot create app temp folder";return false;}
 bool created=false;
 for(unsigned i=0;i<100;++i){
  folder=(tempRoot/(L"runtime-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64())+L"-"+std::to_wstring(i))).wstring();
  if(CreateDirectoryW(folder.c_str(),nullptr)){created=true;break;}
  if(GetLastError()!=ERROR_ALREADY_EXISTS)break;
 }
 if(!created){WindowsError(L"Create temporary folder");folder.clear();return false;}
 for(unsigned i=0;i<3;++i)if(!Extract(301+i,folder+L"\\"+files[i])){WindowsError(L"Extract embedded DLL");StopNoonServer();return false;}
 // Resolve the core's imports only from its extracted folder and Windows system directories.
 module=LoadLibraryExW((folder+L"\\NoonJava.dll").c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
 if(!module){WindowsError(L"Load NoonJava.dll");StopNoonServer();return false;}
 auto create=reinterpret_cast<decltype(&graal_create_isolate)>(GetProcAddress(module,"graal_create_isolate"));
 tearDown=reinterpret_cast<decltype(&graal_tear_down_isolate)>(GetProcAddress(module,"graal_tear_down_isolate"));
 auto start=reinterpret_cast<decltype(&noon_java_start)>(GetProcAddress(module,"noon_java_start"));
 stopHttp=reinterpret_cast<decltype(&noon_java_stop)>(GetProcAddress(module,"noon_java_stop"));
 if(!create||!tearDown||!start||!stopHttp){WindowsError(L"Resolve Java DLL exports");StopNoonServer();return false;}
 graal_isolate_t* isolate=nullptr;
 int isolateResult=create(nullptr,&isolate,&thread);
 if(isolateResult!=0){errorMessage=L"Create GraalVM isolate failed, code "+std::to_wstring(isolateResult);thread=nullptr;StopNoonServer();return false;}
 return true;
}
bool StartNoonServer(){
 if(!url.empty())return true;
 if(!LoadNoonCore())return false;
 wchar_t exe[32768]{};if(!GetModuleFileNameW(nullptr,exe,32768)){WindowsError(L"Read executable path");StopNoonServer();return false;}
 auto start=reinterpret_cast<decltype(&noon_java_start)>(GetProcAddress(module,"noon_java_start"));
 int port=std::stoi(BuiltOption("port",L"4030"));
 std::wstring original(exe);auto slash=original.find_last_of(L"\\/");
 std::wstring portPath=original.substr(0,slash+1)+L"port";
 std::ifstream portFile(portPath.c_str());
 if(portFile && !HasRuntimeOption("port")){std::string text((std::istreambuf_iterator<char>(portFile)),{});try{size_t used=0;port=std::stoi(text,&used);if(text.find_first_not_of(" \t\r\n",used)!=std::string::npos||port<1||port>65535)throw 1;}catch(...){errorMessage=L"Invalid port file: "+portPath+L". Use a number from 1 to 65535.";StopNoonServer();return false;}}
 using SetBoot=int (*)(graal_isolatethread_t*,const char*);
 auto setBoot=reinterpret_cast<SetBoot>(GetProcAddress(module,"noon_java_set_boot_file"));
 auto bootUtf8=Utf8(BuiltOption("boot-file",L"dir.html").c_str());
 if(!setBoot||setBoot(thread,bootUtf8.c_str())!=0){errorMessage=L"Invalid boot file or missing boot settings export; rebuild NoonJava.dll";StopNoonServer();return false;}
 using ContentMode=int (*)(graal_isolatethread_t*,int);
 auto setMode=reinterpret_cast<ContentMode>(GetProcAddress(module,"noon_java_set_content_mode"));
 if(!setMode || setMode(thread,BuiltOption("web-mode",L"embedded")==L"external"?1:0)!=0){errorMessage=L"Rebuild NoonJava.dll: content mode export missing";StopNoonServer();return false;}
 using Policy=int (*)(graal_isolatethread_t*,int,int,int);
 auto policy=reinterpret_cast<Policy>(GetProcAddress(module,"noon_java_set_external_policy"));
 if(!policy || policy(thread,BuiltOption("external-secure",L"0")==L"1"?1:0,BuiltOption("require-password",L"1")==L"1"?1:0,BuiltOption("external-authorized",L"0")==L"1"?1:0)!=0){errorMessage=L"Rebuild NoonJava.dll: external policy export missing";StopNoonServer();return false;}
 using SetPassword=int (*)(graal_isolatethread_t*,const char*);
 auto setPassword=reinterpret_cast<SetPassword>(GetProcAddress(module,"noon_java_set_external_password"));
 auto passwordUtf8=Utf8(BuiltOption("external-password",L"noon").c_str());
 if(!setPassword || setPassword(thread,passwordUtf8.c_str())!=0){errorMessage=L"Rebuild NoonJava.dll: password settings export missing";StopNoonServer();return false;}
 using ConfigStartup=int (*)(graal_isolatethread_t*,int);
 auto configStartup=reinterpret_cast<ConfigStartup>(GetProcAddress(module,"noon_java_set_config_startup"));
 bool explicitApp=HasRuntimeOption("app") || HasRuntimeOption("web-mode");
 if(!configStartup || configStartup(thread,explicitApp?0:1)!=0){errorMessage=L"Rebuild NoonJava.dll: config startup export missing";StopNoonServer();return false;}
 std::wstring external=BuiltExternalRoot();
 if(!external.empty()){
  using AutoDetect=int (*)(graal_isolatethread_t*,int);
  auto autoDetect=reinterpret_cast<AutoDetect>(GetProcAddress(module,"noon_java_set_auto_detect"));
  if(BuiltOption("auto-detect",L"0")==L"1" && (!autoDetect || autoDetect(thread,1)!=0)){errorMessage=L"Rebuild NoonJava.dll: auto-detection export missing";StopNoonServer();return false;}
  using Security=int (*)(graal_isolatethread_t*,int);
  auto security=reinterpret_cast<Security>(GetProcAddress(module,"noon_java_set_external_security"));
  if(!security || security(thread,BuiltOption("external-secure",L"0")==L"1"?1:0)!=0){errorMessage=L"Rebuild NoonJava.dll: external security export missing";StopNoonServer();return false;}
  using Configure=int (*)(graal_isolatethread_t*,const char*);
  auto configure=reinterpret_cast<Configure>(GetProcAddress(module,"noon_java_set_external_root"));
  std::string rootUtf8=Utf8(external.c_str());
  if(!configure || configure(thread,rootUtf8.c_str())!=0){errorMessage=L"Cannot configure external web folder: "+external;StopNoonServer();return false;}
 }
 for(const auto& option:{std::make_pair("app-lucy","noon_java_set_lucene_path"),std::make_pair("app-sqlite","noon_java_set_sqlite_path")}){
  if(!HasRuntimeOption(option.first))continue;
  using SetPath=int (*)(graal_isolatethread_t*,const char*);
  auto setPath=reinterpret_cast<SetPath>(GetProcAddress(module,option.second));
  auto value=Utf8(BuiltOption(option.first).c_str());
  if(!setPath || setPath(thread,value.c_str())!=0){errorMessage=L"Cannot configure data folder; rebuild NoonJava.dll";StopNoonServer();return false;}
 }
 using SetConfig=int (*)(graal_isolatethread_t*,const char*);
 auto setConfig=reinterpret_cast<SetConfig>(GetProcAddress(module,"noon_java_set_config_path"));
 std::wstring configPath=BuiltOption("config-path",L"bin/config.js");
 if(BuiltOption("auto-detect",L"0")==L"1" && !HasRuntimeOption("config-path"))configPath=L"";
 if(!configPath.empty()){
  if(configPath.size()<2 || (configPath[1]!=L':' && configPath[0]!=L'\\' && configPath[0]!=L'/'))configPath=original.substr(0,slash+1)+configPath;
  if(std::filesystem::is_directory(configPath))configPath=(std::filesystem::path(configPath)/L"config.js").wstring();
  auto configUtf8=Utf8(configPath.c_str());
  if(!setConfig || setConfig(thread,configUtf8.c_str())!=0){errorMessage=L"Cannot configure Java search settings";StopNoonServer();return false;}
 }
 std::string utf8=Utf8(exe);
 using ReadPort=int (*)(graal_isolatethread_t*,const char*,int);
 using SuggestPort=int (*)(graal_isolatethread_t*);
 auto readPort=reinterpret_cast<ReadPort>(GetProcAddress(module,"noon_java_read_saved_port"));
 auto savePort=reinterpret_cast<ReadPort>(GetProcAddress(module,"noon_java_save_port"));
 auto suggestPort=reinterpret_cast<SuggestPort>(GetProcAddress(module,"noon_java_suggest_port"));
 if(!readPort||!savePort||!suggestPort){errorMessage=L"Rebuild NoonJava.dll: port-selection exports are missing.";StopNoonServer();return false;}
 bool remember=false;
 int saved=HasRuntimeOption("port")?port:readPort(thread,utf8.c_str(),port);
 auto savePath=(std::filesystem::path(exe).parent_path()/L"bin"/L"config.js").wstring();
 auto choose=[&](const std::wstring& reason){return ChooseNoonPort(port,reason,savePath,[&](){return suggestPort(thread);},port);};
 if(saved<1){
  if(!choose(L"The saved server_port is invalid. Choose a port. / قيمة المنفذ المحفوظة غير صحيحة. اختر منفذاً.")){StopNoonServer();errorMessage.clear();return false;}
  remember=true;
 }else port=saved;
 int actual=0;
 while(true){
  actual=start(thread,utf8.data(),port);
  if(actual>0)break;
  if(actual==-4){
   auto reason=L"Port "+std::to_wstring(port)+L" is busy. Use the suggested port or enter another.\nالمنفذ مستخدم. استخدم المنفذ المقترح أو أدخل منفذاً آخر.";
   if(!choose(reason)){StopNoonServer();errorMessage.clear();return false;}
   remember=true;continue;
  }
  errorMessage=L"Java HTTP startup failed, code "+std::to_wstring(actual);
  auto getError=reinterpret_cast<decltype(&noon_java_last_error)>(GetProcAddress(module,"noon_java_last_error"));
  if(getError){char buffer[8192]{};getError(thread,buffer,sizeof(buffer));int count=MultiByteToWideChar(CP_UTF8,0,buffer,-1,nullptr,0);if(count>1){std::wstring detail(count,L'\0');MultiByteToWideChar(CP_UTF8,0,buffer,-1,&detail[0],count);detail.resize(count-1);errorMessage+=L"\n"+detail;}}
  StopNoonServer();return false;
 }
 if(remember&&savePort(thread,utf8.c_str(),actual)!=0){
  std::wstring warning=L"The app will use the selected port for this launch, but it could not be saved. Check folder write permissions.\nسيعمل التطبيق بالمنفذ المختار لهذه المرة، لكن تعذر حفظه. تحقق من صلاحيات المجلد.\n"+savePath;
  MessageBoxW(nullptr,warning.c_str(),L"Noon / نون",MB_ICONWARNING);
 }
 url=L"http://127.0.0.1:"+std::to_wstring(actual)+L"/";return true;
}
void StopNoonServer(){
 if(thread){
  if(stopHttp)stopHttp(thread);
  if(!tearDown||tearDown(thread)!=0)return; // Never unload while GraalVM threads remain.
  thread=nullptr;
 }
 if(module){FreeLibrary(module);module=nullptr;}
 tearDown=nullptr;stopHttp=nullptr;url.clear();
 if(!folder.empty()){for(auto name:files)DeleteFileW((folder+L"\\"+name).c_str());RemoveDirectoryW(folder.c_str());folder.clear();}
}
static void RefreshServerAddress(){
 if(!module||!thread||url.empty())return;
 using Current=int (*)(graal_isolatethread_t*);
 auto current=reinterpret_cast<Current>(GetProcAddress(module,"noon_java_current_port"));
 int port=current?current(thread):0;
 if(port>0&&port<=65535)url=L"http://127.0.0.1:"+std::to_wstring(port)+L"/";
}
std::wstring NoonServerUrl(){RefreshServerAddress();return url;}
std::wstring NoonServerError(){return errorMessage;}

// Extraction has its own isolate when the application server is already running.
bool ExportNoonAppData(const std::wstring& destination,std::wstring& report,int indexFormat,int indexKinds,const std::wstring& indexRanges,int indexRanks){
 const bool ownsCore=module==nullptr;
 if(ownsCore&&!LoadNoonCore()){report=NoonServerError();return false;}
 graal_isolatethread_t* exportThread=ownsCore?thread:nullptr;
 bool ok=false;
 try{
  if(!ownsCore){
   auto create=reinterpret_cast<decltype(&graal_create_isolate)>(GetProcAddress(module,"graal_create_isolate"));
   graal_isolate_t* isolate=nullptr;
   if(!create||create(nullptr,&isolate,&exportThread)!=0)throw std::runtime_error("Cannot create independent extraction isolate");
  }
  using Export=int (*)(graal_isolatethread_t*,const char*,const char*);
  auto exportData=reinterpret_cast<Export>(GetProcAddress(module,"noon_java_export_app_data"));
  if(!exportData)throw std::runtime_error("Rebuild NoonJava.dll: app-data export entry point missing");
  wchar_t exe[32768]{};if(!GetModuleFileNameW(nullptr,exe,32768))throw std::runtime_error("Cannot locate executable");
  auto source=Utf8(exe),target=Utf8(destination.c_str());if(indexFormat){
   using ExportIndexes=int (*)(graal_isolatethread_t*,const char*,const char*,int,int,const char*,int);
   auto generate=reinterpret_cast<ExportIndexes>(GetProcAddress(module,"noon_java_export_indexed_app_data_v2"));
   if(!generate)throw std::runtime_error("Rebuild NoonJava.dll: offline index export entry point missing");
   ok=generate(exportThread,source.c_str(),target.c_str(),indexFormat,indexKinds,Utf8(indexRanges.c_str()).c_str(),indexRanks)==0;
  }else ok=exportData(exportThread,source.c_str(),target.c_str())==0;
  auto detail=reinterpret_cast<decltype(&noon_java_last_error)>(GetProcAddress(module,"noon_java_last_error"));
  if(detail){char buffer[16384]{};detail(exportThread,buffer,sizeof(buffer));int n=MultiByteToWideChar(CP_UTF8,0,buffer,-1,nullptr,0);if(n>1){report.resize(n);MultiByteToWideChar(CP_UTF8,0,buffer,-1,&report[0],n);report.resize(n-1);}}
  if(!ok&&report.empty())report=L"App data extraction failed / تعذر استخراج بيانات التطبيق";
 }catch(const std::exception& error){int n=MultiByteToWideChar(CP_UTF8,0,error.what(),-1,nullptr,0);if(n>1){report.resize(n);MultiByteToWideChar(CP_UTF8,0,error.what(),-1,&report[0],n);report.resize(n-1);}}
 if(ownsCore)StopNoonServer();
 else if(exportThread){
  auto destroy=reinterpret_cast<decltype(&graal_tear_down_isolate)>(GetProcAddress(module,"graal_tear_down_isolate"));
  if(!destroy||destroy(exportThread)!=0){ok=false;report+=L"\nCould not close extraction isolate / تعذر إغلاق بيئة الاستخراج";}
 }
 return ok;
}

bool VerifyNoonSignedBin(const std::wstring& destination,std::wstring& report){
 const bool ownsCore=module==nullptr;
 if(ownsCore&&!LoadNoonCore()){report=NoonServerError();return false;}
 graal_isolatethread_t* exportThread=ownsCore?thread:nullptr;
 bool ok=false;
 try{
  if(!ownsCore){
   auto create=reinterpret_cast<decltype(&graal_create_isolate)>(GetProcAddress(module,"graal_create_isolate"));
   graal_isolate_t* isolate=nullptr;
   if(!create||create(nullptr,&isolate,&exportThread)!=0)throw std::runtime_error("Cannot create independent extraction isolate");
  }
  using Verify=int (*)(graal_isolatethread_t*,const char*);
  auto verify=reinterpret_cast<Verify>(GetProcAddress(module,"noon_java_verify_signed_bin"));
  if(!verify)throw std::runtime_error("Rebuild NoonJava.dll: signed-bin verification entry point missing");
  auto target=Utf8(destination.c_str());ok=verify(exportThread,target.c_str())==0;
  auto detail=reinterpret_cast<decltype(&noon_java_last_error)>(GetProcAddress(module,"noon_java_last_error"));
  if(detail){char buffer[16384]{};detail(exportThread,buffer,sizeof(buffer));int n=MultiByteToWideChar(CP_UTF8,0,buffer,-1,nullptr,0);if(n>1){report.resize(n);MultiByteToWideChar(CP_UTF8,0,buffer,-1,&report[0],n);report.resize(n-1);}}
  if(!ok&&report.empty())report=L"Signed bin verification failed";
 }catch(const std::exception& error){int n=MultiByteToWideChar(CP_UTF8,0,error.what(),-1,nullptr,0);if(n>1){report.resize(n);MultiByteToWideChar(CP_UTF8,0,error.what(),-1,&report[0],n);report.resize(n-1);}}
 if(ownsCore)StopNoonServer();
 else if(exportThread){
  auto destroy=reinterpret_cast<decltype(&graal_tear_down_isolate)>(GetProcAddress(module,"graal_tear_down_isolate"));
  if(!destroy||destroy(exportThread)!=0){ok=false;report+=L"\nCould not close extraction isolate / تعذر إغلاق بيئة الاستخراج";}
 }
 return ok;
}

bool SaveNoonBuildSettings(const std::wstring& config,int port,const std::wstring& version,const std::wstring& app,std::wstring& report,const std::wstring& bootFile,int passwordRequired,const std::wstring& password,const std::wstring& appType){
 const bool ownsCore=module==nullptr;if(ownsCore&&!LoadNoonCore()){report=NoonServerError();return false;}
 graal_isolatethread_t* settingsThread=ownsCore?thread:nullptr;bool ok=false;
 try{
  if(!ownsCore){auto create=reinterpret_cast<decltype(&graal_create_isolate)>(GetProcAddress(module,"graal_create_isolate"));graal_isolate_t* isolate=nullptr;if(!create||create(nullptr,&isolate,&settingsThread)!=0)throw std::runtime_error("Cannot create settings isolate");}
  using SetBoot=int (*)(graal_isolatethread_t*,const char*);
  auto setBoot=reinterpret_cast<SetBoot>(GetProcAddress(module,"noon_java_set_boot_file"));auto boot=Utf8(bootFile.c_str());
  if(!setBoot||setBoot(settingsThread,boot.c_str())!=0)throw std::runtime_error("Invalid boot file or missing boot export");
  if(passwordRequired>=0){using SetPassword=int (*)(graal_isolatethread_t*,int,const char*);auto setPassword=reinterpret_cast<SetPassword>(GetProcAddress(module,"noon_java_set_build_password"));auto value=Utf8(password.c_str());if(!setPassword||setPassword(settingsThread,passwordRequired,value.c_str())!=0)throw std::runtime_error("Rebuild NoonJava.dll: build password export missing");}
  if(!appType.empty()){using SetType=int (*)(graal_isolatethread_t*,const char*);auto setType=reinterpret_cast<SetType>(GetProcAddress(module,"noon_java_set_build_type"));auto value=Utf8(appType.c_str());if(!setType||setType(settingsThread,value.c_str())!=0)throw std::runtime_error("Rebuild NoonJava.dll: app type export missing");}
  using Save=int (*)(graal_isolatethread_t*,const char*,int,const char*,const char*);
  auto save=reinterpret_cast<Save>(GetProcAddress(module,"noon_java_save_build_settings"));
  if(!save)throw std::runtime_error("Rebuild NoonJava.dll: build-settings export missing");
  auto configUtf8=Utf8(config.c_str()),versionUtf8=Utf8(version.c_str()),appUtf8=Utf8(app.c_str());
  ok=save(settingsThread,configUtf8.c_str(),port,versionUtf8.c_str(),appUtf8.c_str())==0;
  if(!ok)report=L"Cannot save external config port/version. Check the config file and permissions.";
 }catch(const std::exception& e){int n=MultiByteToWideChar(CP_UTF8,0,e.what(),-1,nullptr,0);if(n>1){report.resize(n);MultiByteToWideChar(CP_UTF8,0,e.what(),-1,&report[0],n);report.resize(n-1);}}
 if(ownsCore)StopNoonServer();else if(settingsThread){auto destroy=reinterpret_cast<decltype(&graal_tear_down_isolate)>(GetProcAddress(module,"graal_tear_down_isolate"));if(!destroy||destroy(settingsThread)!=0){ok=false;report+=L" Could not close settings isolate.";}}
 return ok;
}
