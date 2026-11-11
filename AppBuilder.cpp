#include "pch.h"
#include "AppBuilder.h"
#include "BuildGui.h"
#include "BrowserLaunch.h"
#include "NoonServer.h"
#include "AppDataReplace.h"
#include "AppTypeConfig.h"
#include "AppFolderName.h"
#include <memory>
#include <shellapi.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <map>
#include <vector>
#include <algorithm>
#include <stdexcept>
#include <cstdio>
#include <cstdint>
#include <atomic>
#include <regex>
#include <set>
#include <cwctype>
namespace fs=std::filesystem;
namespace {
int result=0;
std::atomic<bool> guiAuthorized{false};
std::map<std::string,std::wstring> runtimeOptions;
std::wstring Self(){wchar_t b[32768]{};if(!GetModuleFileNameW(nullptr,b,32768))throw std::runtime_error("Cannot locate executable");return b;}
std::string Utf8(const std::wstring& s){if(s.empty())return {};int n=WideCharToMultiByte(CP_UTF8,0,s.data(),(int)s.size(),nullptr,0,nullptr,nullptr);std::string b(n,'\0');WideCharToMultiByte(CP_UTF8,0,s.data(),(int)s.size(),&b[0],n,nullptr,nullptr);return b;}
std::wstring Wide(const std::string& s){if(s.empty())return {};int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),(int)s.size(),nullptr,0);if(!n)throw std::runtime_error("Invalid UTF-8");std::wstring b(n,L'\0');MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),&b[0],n);return b;}
std::map<std::string,std::wstring> Defaults(){
 std::map<std::string,std::wstring> m;
 HRSRC r=FindResourceW(nullptr,MAKEINTRESOURCEW(501),RT_RCDATA);
 if(r){auto mem=LoadResource(nullptr,r);auto data=(const char*)LockResource(mem);std::istringstream in(std::string(data,SizeofResource(nullptr,r)));std::string line;
 while(std::getline(in,line)){auto at=line.find('=');if(at!=std::string::npos)m[line.substr(0,at)]=Wide(line.substr(at+1));}}
 return m;
}
void Console(){if(GetFileType(GetStdHandle(STD_OUTPUT_HANDLE))==FILE_TYPE_PIPE)return;if(!AttachConsole(ATTACH_PARENT_PROCESS))AllocConsole();FILE* f=nullptr;freopen_s(&f,"CONOUT$","w",stdout);freopen_s(&f,"CONOUT$","w",stderr);}
bool ExternalOverridesAllowed(){auto m=Defaults();auto it=m.find("allow-external-override");return it!=m.end() && it->second==L"1";}
void Help(){puts(
 "Noon - application runtime\n\n"
 "Noon.exe\n"
 "--auto_detect BOOL  detect app/databases/indexes under --app_path (true/false)\n"
 "--app_path PATH     external web app (alias: --path)\n"
 "--app_lucy PATH     Lucene index folder\n"
 "--app_sqlite PATH   SQLite database root\n"
 "Noon.exe --config \"path\\config.js\"\n\n"
 "--config PATH       runtime config override\n"
 "--webview TYPE      auto, webview2 or nwjs\n"
 "--port NUMBER       1..65535\n"
 "--name TEXT         runtime window title\n"
 "--clearalldata      clear all WebView2 and NW.js data, then exit (close browser windows first)\n"
 "--help / -h         these instructions\n");
 if(ExternalOverridesAllowed())puts("--web-mode MODE     embedded or external\n--app PATH          external web folder (relative paths resolve beside EXE)\nExample: Noon.exe --app \"www\"\n");
}
uint64_t Read64(std::ifstream& in){unsigned char b[8];if(!in.read((char*)b,8))throw std::runtime_error("Truncated payload footer");uint64_t v=0;for(int i=7;i>=0;--i)v=(v<<8)|b[i];return v;}
void Write64(std::ofstream& out,uint64_t v){for(int i=0;i<8;++i){out.put(char(v&255));v>>=8;}}
struct Entry{std::string path;uint64_t offset,size;fs::path source;};
struct Payload{uint64_t base,total;std::vector<Entry> entries;};
Payload ReadPayload(const fs::path& exe){
 std::ifstream in(exe,std::ios::binary);if(!in)throw std::runtime_error("Cannot read source EXE");
 in.seekg(0,std::ios::end);uint64_t total=(uint64_t)in.tellg();Payload p{total,total,{}};
 if(total<24)return p;in.seekg(total-24);char magic[8];in.read(magic,8);
 if(std::string(magic,8)=="NEXTAPP1"){
  uint64_t base=Read64(in),reserved=Read64(in);
  if(base!=total-24 || reserved!=0)throw std::runtime_error("Invalid external application trailer");
  p.base=base;return p;
 }
 if(std::string(magic,8)!="NHTMLPK1")return p;
 uint64_t offset=Read64(in),size=Read64(in);
 if(size==0 || size>64*1024*1024 || offset>total-24 || size!=total-24-offset)throw std::runtime_error("Invalid source web payload");
 in.seekg(offset);std::string manifest((size_t)size,'\0');in.read(&manifest[0],size);std::istringstream lines(manifest);std::string line;std::getline(lines,line);
 uint64_t base=offset;
 while(std::getline(lines,line)){
  if(line.empty())continue;auto a=line.find('\t'),b=line.find('\t',a==std::string::npos?0:a+1);
  if(a==std::string::npos || b==std::string::npos)throw std::runtime_error("Invalid source manifest");
  Entry e{line.substr(0,a),std::stoull(line.substr(a+1,b-a-1)),std::stoull(line.substr(b+1)),exe};
  if(e.offset<64 || e.offset>offset || e.size>offset-e.offset)throw std::runtime_error("Invalid source web entry");
  base=(std::min)(base,e.offset);p.entries.push_back(e);
 }
 if(p.entries.empty())throw std::runtime_error("Empty source manifest");p.base=base;return p;
}
void CopyBytes(std::ifstream& in,std::ofstream& out,uint64_t count){std::vector<char>b(1024*1024);while(count){auto n=(std::streamsize)(std::min<uint64_t>)(count,b.size());if(!in.read(b.data(),n))throw std::runtime_error("Input changed or truncated");out.write(b.data(),n);if(!out)throw std::runtime_error("Output write failed");count-=n;}}
#pragma pack(push,1)
struct IcoEntry{BYTE width,height,colors,reserved;WORD planes,bits;DWORD size,offset;};
struct GroupEntry{BYTE width,height,colors,reserved;WORD planes,bits;DWORD size;WORD id;};
#pragma pack(pop)
std::vector<char> Bytes(const fs::path& path){std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("Cannot read icon");return {(std::istreambuf_iterator<char>(in)),{}};}
void Icon(HANDLE update,const fs::path& path){
 auto data=Bytes(path);if(data.size()<6 || data.size()>16*1024*1024)throw std::runtime_error("Invalid ICO size");
 WORD header[3];memcpy(header,data.data(),6);unsigned count=header[2];if(header[0]!=0 || header[1]!=1 || !count || count>256 || data.size()<6+count*sizeof(IcoEntry))throw std::runtime_error("Invalid ICO header");
 std::vector<char> group(6+count*sizeof(GroupEntry));memcpy(group.data(),header,6);
 for(unsigned i=0;i<count;++i){IcoEntry e;memcpy(&e,data.data()+6+i*sizeof(e),sizeof(e));if(e.offset>data.size() || e.size>data.size()-e.offset)throw std::runtime_error("Invalid ICO image");
  WORD id=WORD(5000+i);GroupEntry g{e.width,e.height,e.colors,e.reserved,e.planes,e.bits,e.size,id};memcpy(group.data()+6+i*sizeof(g),&g,sizeof(g));
  if(!UpdateResourceW(update,RT_ICON,MAKEINTRESOURCEW(id),0x409,data.data()+e.offset,e.size))throw std::runtime_error("Icon resource update failed");
 }
 if(!UpdateResourceW(update,RT_GROUP_ICON,MAKEINTRESOURCEW(128),0x409,group.data(),(DWORD)group.size()) ||
    !UpdateResourceW(update,RT_RCDATA,MAKEINTRESOURCEW(408),0x409,data.data(),(DWORD)data.size()))throw std::runtime_error("App icon update failed");
}
using Blob=std::vector<unsigned char>;
void Word(Blob& b,WORD v){b.push_back(BYTE(v));b.push_back(BYTE(v>>8));}
void Dword(Blob& b,DWORD v){Word(b,WORD(v));Word(b,WORD(v>>16));}
void Align(Blob& b){while(b.size()%4)b.push_back(0);}
void Text(Blob& b,const std::wstring& s){for(wchar_t c:s)Word(b,WORD(c));Word(b,0);}
Blob Block(const std::wstring& key,WORD type,WORD valueLength,const Blob& value,const std::vector<Blob>& children={}){
 Blob b;Word(b,0);Word(b,valueLength);Word(b,type);Text(b,key);Align(b);b.insert(b.end(),value.begin(),value.end());
 for(auto& child:children){Align(b);b.insert(b.end(),child.begin(),child.end());}
 if(b.size()>65535)throw std::runtime_error("Version resource exceeds 64 KB");b[0]=BYTE(b.size());b[1]=BYTE(b.size()>>8);return b;
}
Blob StringBlock(const std::wstring& key,const std::wstring& text){Blob value;Text(value,text);return Block(key,1,WORD(text.size()+1),value);}
void Version(HANDLE update,const std::map<std::string,std::wstring>& options){
 auto get=[&](const std::string& key,const std::wstring& fallback){auto it=options.find(key);return it==options.end()?fallback:it->second;};
 std::wstring version=get("version",L"1.0.0.1");std::wistringstream in(version);WORD part[4];
 for(int i=0;i<4;++i){std::wstring token;if(!std::getline(in,token,L'.') || token.empty() || token.find_first_not_of(L"0123456789")!=std::wstring::npos)throw std::runtime_error("Version needs four numbers, A.B.C.D");unsigned long n=std::stoul(token);if(n>65535)throw std::runtime_error("Version component exceeds 65535");part[i]=WORD(n);}
 std::wstring extra;if(std::getline(in,extra,L'.') || version.back()==L'.')throw std::runtime_error("Version needs exactly four numbers");
 Blob fixed;for(DWORD n:{DWORD(0xFEEF04BD),DWORD(0x00010000),DWORD(part[0])<<16|part[1],DWORD(part[2])<<16|part[3],DWORD(part[0])<<16|part[1],DWORD(part[2])<<16|part[3],DWORD(0x3f),DWORD(0),DWORD(0x40004),DWORD(1),DWORD(0),DWORD(0),DWORD(0)})Dword(fixed,n);
 std::wstring name=get("name",L"Noon");
 std::vector<Blob> strings={StringBlock(L"FileDescription",name),StringBlock(L"ProductName",name),StringBlock(L"FileVersion",version),StringBlock(L"ProductVersion",version),StringBlock(L"CompanyName",L"noon-team"),StringBlock(L"LegalCopyright",get("copyright",L"Copyright (C) 2026 noon-team. All rights reserved."))};
 Blob table=Block(L"040904B0",1,0,{},strings),info=Block(L"StringFileInfo",1,0,{}, {table});
 Blob translation;Word(translation,0x409);Word(translation,1200);
 Blob vars=Block(L"VarFileInfo",1,0,{}, {Block(L"Translation",0,4,translation)});
 Blob resource=Block(L"VS_VERSION_INFO",0,52,fixed,{info,vars});
 if(!UpdateResourceW(update,MAKEINTRESOURCEW(16),MAKEINTRESOURCEW(1),0x409,resource.data(),(DWORD)resource.size()))throw std::runtime_error("Version resource update failed");
}

}
bool AuthorizeBuilderGui(const std::wstring& password){bool ok=password==L"noon";guiAuthorized.store(ok);return ok;}
void LockBuilderGui(){guiAuthorized.store(false);}
bool ExtractEmbeddedWebTo(const std::wstring& parentFolder,std::wstring& report,int indexFormat,int indexKinds,const std::wstring& indexRanges,int indexRanks){
 if(!guiAuthorized.load()){report=L"Builder GUI is locked";return false;}
 fs::path stage;bool created=false,keepStage=false;std::unique_ptr<AppDataReplace> replacement;
 try{
  fs::path destination=fs::absolute(parentFolder).lexically_normal();
  if(destination==destination.root_path()||fs::is_symlink(destination)||(fs::exists(destination)&&!fs::is_directory(destination)))throw std::runtime_error("Choose a regular destination folder");
  fs::create_directories(destination);fs::path base=fs::canonical(destination).parent_path();destination=fs::canonical(destination);
  stage=base/(L"Noon_Extracting_"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64()));if(!fs::create_directory(stage))throw std::runtime_error("Temporary extraction folder exists");created=true;
  std::wstring details;
  if(!ExportNoonAppData(stage.wstring(),details,indexFormat,indexKinds,indexRanges,indexRanks)){std::error_code ec;fs::remove_all(stage,ec);created=false;report=details;return false;}
  replacement=std::make_unique<AppDataReplace>(destination,stage);replacement->apply();replacement->commit();
  std::error_code cleanup;fs::remove_all(stage,cleanup);created=false;
  if(cleanup)details+=L"\nOld export backup could not be removed / تعذر حذف النسخة القديمة: "+stage.wstring();
  report=L"Extracted / تم الاستخراج: "+destination.wstring()+L"\n\nWeb app / تطبيق الويب: "+(destination/L"app").wstring()+L"\nDatabases / قواعد البيانات: "+(destination/L"db").wstring()+L"\nBundled indexes / الفهارس المضمنة: "+(destination/L"lucene").wstring()+L"\nGenerated viewer / ملفات العرض المولّدة: "+(destination/L"app"/L"www"/L"viewer").wstring()+L"\n"+details;return true;
 }catch(const std::exception& e){
  if(replacement&&!replacement->rollback())keepStage=true;
  if(created&&!keepStage){std::error_code ec;fs::remove_all(stage,ec);}
  report=Wide(e.what());if(keepStage)report+=L"\nPrevious export backup / نسخة الاستخراج السابقة: "+stage.wstring();return false;
 }
}
bool ExtractSignedBinTo(const std::wstring& folder,std::wstring& report){
 if(!guiAuthorized.load()){report=L"Builder GUI is locked";return false;}
 fs::path stage;bool created=false;
 try{
  fs::path destination=fs::absolute(folder).lexically_normal();
  if(destination==destination.root_path()||fs::is_symlink(destination)||(fs::exists(destination)&&(!fs::is_directory(destination)||!fs::is_empty(destination))))throw std::runtime_error("Choose an empty destination folder for the signed bin");
  auto payload=ReadPayload(Self());bool manifest=false,signature=false;std::set<fs::path> targets;
  for(const auto& e:payload.entries){if(e.path=="/__bin/NT/install_manifest.json")manifest=true;if(e.path=="/__bin/NT/install_manifest.sig")signature=true;}
  if(!manifest||!signature)throw std::runtime_error("This EXE has no embedded signed bin. Use the original embedded EXE.");
  fs::create_directories(destination.parent_path());
  stage=destination.parent_path()/(L"Noon_Signed_Extracting_"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64()));
  if(!fs::create_directory(stage))throw std::runtime_error("Temporary extraction folder exists");created=true;
  for(const auto& e:payload.entries){
   if(e.path.empty()||e.path[0]!='/')throw std::runtime_error("Invalid embedded file path");
   auto name=e.path.rfind("/__bin/",0)==0?e.path.substr(7):std::string("NT/app.dat/")+e.path.substr(1);
   fs::path relative=Wide(name);
   if(relative.empty()||relative.is_absolute()||relative.has_root_name()||name.find('\\')!=std::string::npos||name.find(':')!=std::string::npos)throw std::runtime_error("Invalid embedded file path");
   for(const auto& part:relative)if(part==L".."||part==L"."||part.empty())throw std::runtime_error("Invalid embedded file path");
   if(!targets.insert(relative).second)throw std::runtime_error("Duplicate embedded file path");
   auto target=stage/relative;fs::create_directories(target.parent_path());
   std::ifstream in(e.source,std::ios::binary);in.seekg(e.offset);std::ofstream out(target,std::ios::binary);CopyBytes(in,out,e.size);out.close();if(!out)throw std::runtime_error("Cannot finish extracted file");
  }
  std::wstring verification;
  if(!VerifyNoonSignedBin(stage.wstring(),verification))throw std::runtime_error(Utf8(verification));
  if(fs::exists(destination))fs::remove(destination);
  fs::rename(stage,destination);stage.clear();
  report=L"Signed bin extracted: "+destination.wstring()+L"\nAll embedded files copied without conversion. Signed manifest, complete protected file list, sizes and hashes verified.";return true;
 }catch(const std::exception& e){if(created&&!stage.empty()){std::error_code ec;fs::remove_all(stage,ec);}report=Wide(e.what());return false;}
}
std::vector<std::wstring> BuilderIndexRanges(){
 std::set<std::wstring> found;std::regex pattern("/(mn|sn|vb)([0-9]+)_sqlite/[^/]+\\.(sqlite|sqlite3|db)$",std::regex::icase);
 try{for(const auto& entry:ReadPayload(Self()).entries){std::smatch m;if(std::regex_search(entry.path,m,pattern)){auto code=Wide(m[1].str()+m[2].str());std::transform(code.begin(),code.end(),code.begin(),::towlower);if(code.rfind(L"vb",0)==0&&code!=L"vb4")continue;found.insert(code);}}}catch(...){}
 return {found.begin(),found.end()};
}
int BuilderExitCode(){return result;}
bool HasRuntimeOption(const std::string& key){return runtimeOptions.count(key)!=0;}
std::wstring BuiltOption(const std::string& key,const std::wstring& fallback){auto m=Defaults();for(auto& item:runtimeOptions)m[item.first]=item.second;auto it=m.find(key);return it==m.end()?fallback:it->second;}
std::wstring RuntimeAppType(){
 auto exe=fs::path(Self()),base=exe.parent_path();std::wstring setting=BuiltOption("app-type",L"MN");for(auto& c:setting)c=(wchar_t)towupper(c);std::wstring type=setting==L"SN"?L"sn":L"mn";
 // Establish the embedded default before allowing external settings to override it.
 try{for(const auto& e:ReadPayload(exe).entries)if(e.path=="/__bin/config.js"&&e.size<=4*1024*1024){std::ifstream in(e.source,std::ios::binary);in.seekg(e.offset);std::string bytes((size_t)e.size,'\0');if(in.read(bytes.data(),bytes.size())){auto embedded=ConfigAppType(bytes);if(!embedded.empty())type=embedded;}break;}}catch(...){}
 fs::path config=BuiltOption("config-path",L"bin/config.js");if(config.is_relative())config=base/config;
 std::error_code ec;if(fs::is_directory(config,ec))config/=L"config.js";
 if(!fs::is_regular_file(config,ec))config=base/L"NT"/L"config.js";
 auto external=ConfigFileAppType(config);if(!external.empty())type=external;
 return type;
}
std::wstring BuiltExternalRoot(){if(BuiltOption("web-mode")!=L"external")return L"";fs::path root=BuiltOption("app");if(root.is_relative())root=fs::path(Self()).parent_path()/root;return fs::absolute(root).wstring();}
std::string JsonString(const std::wstring& value){std::string out="\"";for(unsigned char c:Utf8(value)){if(c=='"'||c=='\\'){out+='\\';out+=(char)c;}else if(c<32){char b[7];sprintf_s(b,"\\u%04x",c);out+=b;}else out+=(char)c;}return out+'"';}
bool HandleBuilderCommand(){
 int argc=0;auto argv=CommandLineToArgvW(GetCommandLineW(),&argc);std::vector<std::wstring> args;for(int i=1;argv && i<argc;++i)args.push_back(argv[i]);if(argv)LocalFree(argv);
 if(std::find(args.begin(),args.end(),L"--clearalldata")!=args.end()){
  Console();if(args.size()!=1){fprintf(stderr,"Use --clearalldata by itself.\n");result=1;return true;}
  std::wstring report;bool ok=ClearBothBrowserData(report);result=ok?0:1;
  fprintf(ok?stdout:stderr,"%s\n",Utf8(report).c_str());return true;
 }
 if(std::find(args.begin(),args.end(),L"--aboutgui")!=args.end()){ShowAboutGui();return true;}
 if(std::find(args.begin(),args.end(),L"--gui")!=args.end()){ShowBuildGui();return true;}
 if(std::find(args.begin(),args.end(),L"--help")!=args.end() || std::find(args.begin(),args.end(),L"-h")!=args.end()){Console();Help();return true;}
  try{
   bool testPath=false,testOverride=false,autoDetect=false,hasAutoDetect=false;std::wstring testRoot,password;
   std::map<std::string,std::wstring> dataPaths;
   std::vector<std::wstring> filtered;
   for(size_t i=0;i<args.size();++i){
    if(args[i]==L"-pass" || args[i]==L"--path" || args[i]==L"--app_path" || args[i]==L"--app_lucy" || args[i]==L"--app_sqlite" || args[i]==L"--auto_detect"){
     auto key=args[i];if(i+1>=args.size())throw std::runtime_error("Missing option value");
     if(key==L"--auto_detect"){
      auto value=args[++i];if(value!=L"true" && value!=L"false")throw std::runtime_error("--auto_detect requires true or false");
      autoDetect=value==L"true";hasAutoDetect=true;
     }
     else if(key==L"-pass")password=args[++i];
     else{
      auto value=args[++i];if(value.empty())throw std::runtime_error("External path cannot be empty");
      testOverride=true;
      if(key==L"--path" || key==L"--app_path"){testRoot=value;testPath=true;}
      else dataPaths[key==L"--app_lucy"?"app-lucy":"app-sqlite"]=value;
     }
    }else filtered.push_back(args[i]);
   }
   if(hasAutoDetect && !testPath)throw std::runtime_error("--auto_detect requires --app_path or --path");
   if(hasAutoDetect)runtimeOptions["auto-detect"]=autoDetect?L"1":L"0";
   if(testOverride && BuiltOption("require-password",L"1")==L"1" && password!=BuiltOption("external-password",L"noon"))throw std::runtime_error("Access denied.");
   if(password==BuiltOption("external-password",L"noon"))runtimeOptions["external-authorized"]=L"1";
   args.swap(filtered);
   if(testPath){runtimeOptions["app"]=testRoot;runtimeOptions["web-mode"]=L"external";}
   for(size_t i=0;i<args.size();++i){
    auto arg=args[i];
    if(arg==L"--config" || arg==L"--config-path"){if(i+1>=args.size())throw std::runtime_error("Config option needs a path");runtimeOptions["config-path"]=args[++i];continue;}
    if(arg.rfind(L"--config=",0)==0){runtimeOptions["config-path"]=arg.substr(9);continue;}
    std::string key=Utf8(arg);if(key.rfind("--",0)!=0)throw std::runtime_error("Unknown runtime argument");key=key.substr(2);
    if(key!="web-mode" && key!="app" && key!="webview" && key!="port" && key!="name")throw std::runtime_error("Unknown runtime option: "+key);
    if((key=="app" || key=="web-mode") && !ExternalOverridesAllowed())throw std::runtime_error("External web folder overrides are disabled for this EXE");
    if(i+1>=args.size())throw std::runtime_error("Missing runtime option value");runtimeOptions[key]=args[++i];
   }
   if(testPath){runtimeOptions["app"]=testRoot;runtimeOptions["web-mode"]=L"external";}
   for(const auto& item:dataPaths){
    fs::path folder=item.second;if(folder.is_relative())folder=fs::path(Self()).parent_path()/folder;
    folder=fs::absolute(folder);
    if(item.first=="app-sqlite" && !fs::is_directory(folder))throw std::runtime_error("SQLite folder does not exist");
    if(item.first=="app-lucy" && fs::exists(folder) && !fs::is_directory(folder))throw std::runtime_error("Lucene path must be a folder");
    runtimeOptions[item.first]=folder.wstring();
   }
   auto mode=BuiltOption("web-mode",L"embedded");if(mode!=L"embedded" && mode!=L"external")throw std::runtime_error("Invalid --web-mode");
   if(runtimeOptions.count("app") && !runtimeOptions.count("web-mode"))runtimeOptions["web-mode"]=L"external";
   if(BuiltOption("web-mode",L"embedded")==L"external" && BuiltOption("app").empty())throw std::runtime_error("External mode needs --app");
   auto browser=BuiltOption("webview",L"auto");if(browser!=L"auto" && browser!=L"webview2" && browser!=L"nwjs")throw std::runtime_error("Invalid --webview");
   auto port=BuiltOption("port",L"4030");if(port.empty() || port.find_first_not_of(L"0123456789")!=std::wstring::npos || std::stoul(port)<1 || std::stoul(port)>65535)throw std::runtime_error("Invalid --port");
   return false;
  }catch(const std::exception& e){Console();fprintf(stderr,"Run failed: %s\nUse --help.\n",e.what());result=1;return true;}
 
}
bool BuildAppFromGui(const std::map<std::string,std::wstring>& values,std::wstring& report){
 if(!guiAuthorized.load()){report=L"Builder GUI is locked";return false;}
 std::vector<std::wstring> args;
 for(auto& item:values){args.push_back(L"--"+Wide(item.first));args.push_back(item.second);}
 fs::path temp,embeddedConfigTemp;
 try{
  std::map<std::string,std::wstring> options=Defaults();options.erase("app");options.erase("runtime-folder");options["web-mode"]=L"embedded";options["allow-external-override"]=L"0";options["external-secure"]=L"1";options.erase("generated-child");options["require-password"]=L"0";
  fs::path output,icon,nwIcon;bool hasApp=false;
  for(size_t i=0;i<args.size();++i){if(i+1>=args.size())throw std::runtime_error("Missing option value");
   std::string key=Utf8(args[i]);if(key.rfind("--",0)!=0)throw std::runtime_error("Expected --option");key=key.substr(2);std::wstring value=args[++i];
   if(value.empty() || value.find_first_of(L"\r\n\t")!=std::wstring::npos)throw std::runtime_error("Empty value or unsupported control character");
   if(key=="output")output=value;else if(key=="icon")icon=value;else if(key=="nw-icon")nwIcon=value;
   else if(key=="allow-external-override" || key=="external-secure" || key=="require-password"){if(value!=L"0" && value!=L"1")throw std::runtime_error("Invalid external override permission");options[key]=value;}
   else if(key=="runtime-folder" || key=="app-type" || key=="app" || key=="web-mode" || key=="name" || key=="webview" || key=="port" || key=="config-path" || key=="settings-path" || key=="external-password" || key=="boot-file" || key=="version" || key=="copyright"){options[key]=value;if(key=="app")hasApp=true;}
   else throw std::runtime_error("Unknown build option: "+key);
  }
  if(output.empty())throw std::runtime_error("--output is required");output=fs::absolute(output);
  std::error_code equivalentError;
  if(fs::equivalent(fs::path(Self()),output,equivalentError))throw std::runtime_error("Output must differ from the running EXE");
  if(fs::exists(output))throw std::runtime_error("Output already exists. Choose a new filename");
  if(options.count("runtime-folder")&&!ValidAppFolderName(options["runtime-folder"]))throw std::runtime_error("Custom folder name must be one valid folder name, not a path");
  if(options.count("app-type")&&options["app-type"]!=L"MN"&&options["app-type"]!=L"SN")throw std::runtime_error("App type must be MN or SN");
  auto mode=options["web-mode"];if(mode!=L"embedded" && mode!=L"external")throw std::runtime_error("Invalid --web-mode");
  auto webview=options.count("webview")?options["webview"]:L"auto";if(webview!=L"auto" && webview!=L"nwjs" && webview!=L"webview2")throw std::runtime_error("Invalid --webview");
  if(options.count("port")){auto t=options["port"];if(t.find_first_not_of(L"0123456789")!=std::wstring::npos || std::stoul(t)<1 || std::stoul(t)>65535)throw std::runtime_error("Invalid --port");}
  Payload old=ReadPayload(Self());std::vector<Entry> entries;
  if(mode==L"external"){if(!hasApp)throw std::runtime_error("External mode requires --app");}
  else if(!hasApp){entries=old.entries;if(entries.empty())throw std::runtime_error("No embedded app to reuse; specify --app");}
  else{
   fs::path root=fs::canonical(options["app"]);if(!fs::is_directory(root))throw std::runtime_error("--app must be a folder");
   for(auto& f:fs::recursive_directory_iterator(root)){
    if(fs::is_symlink(f.symlink_status()))throw std::runtime_error("Symlinks are not supported in embedded folders");
    if(!f.is_regular_file())continue;std::string path="/"+Utf8(f.path().lexically_relative(root).generic_wstring());
    if(path.find_first_of("\r\n\t")!=std::string::npos)throw std::runtime_error("Unsupported web filename");
    uint64_t size=f.file_size();if(size>INT_MAX)throw std::runtime_error("Web file exceeds 2 GB");entries.push_back({path,0,size,f.path()});
   }
   std::sort(entries.begin(),entries.end(),[](const Entry&a,const Entry&b){return a.path<b.path;});
   if(entries.empty())throw std::runtime_error("Web folder is empty");
  }
  if(mode==L"embedded" && hasApp){for(const auto& entry:old.entries)if(entry.path.rfind("/__bin/",0)==0)entries.push_back(entry);}
  if(mode==L"embedded"){
   std::string boot=Utf8(options.count("boot-file")?options["boot-file"]:L"dir.html");
   bool home=false;for(auto& e:entries)if(e.path=="/"+boot || e.path=="/www/"+boot || e.path=="/dir.html" || e.path=="/www/dir.html")home=true;
   if(!home)throw std::runtime_error("Web folder needs dir.html, index.html or main.html (root or www)");
   options.erase("app");
  }
  if(mode==L"embedded"){
   embeddedConfigTemp=output.wstring()+L".config-building-"+std::to_wstring(GetCurrentProcessId());
   if(fs::exists(embeddedConfigTemp))throw std::runtime_error("Temporary config already exists");
   auto configEntry=std::find_if(entries.begin(),entries.end(),[](const Entry& e){return e.path=="/__bin/config.js";});
   if(configEntry!=entries.end()){
    std::ifstream in(configEntry->source,std::ios::binary);in.seekg(configEntry->offset);std::ofstream out(embeddedConfigTemp,std::ios::binary);CopyBytes(in,out,configEntry->size);
   }
   std::wstring settingsError;
   if(!SaveNoonBuildSettings(embeddedConfigTemp.wstring(),std::stoi(options.count("port")?options["port"]:L"4030"),options.count("version")?options["version"]:L"1.0.0.1",L"",settingsError,options.count("boot-file")?options["boot-file"]:L"dir.html",options["require-password"]==L"1"?1:0,options.count("external-password")?options["external-password"]:L"noon",options.count("app-type")?options["app-type"]:L"MN"))throw std::runtime_error(Utf8(settingsError));
   Entry updated{"/__bin/config.js",0,fs::file_size(embeddedConfigTemp),embeddedConfigTemp};
   if(configEntry==entries.end())entries.push_back(updated);else *configEntry=updated;
  }
  temp=output.wstring()+L".building-"+std::to_wstring(GetCurrentProcessId());if(fs::exists(temp))throw std::runtime_error("Temporary output already exists");
  {std::ifstream in(fs::path(Self()),std::ios::binary);std::ofstream out(temp,std::ios::binary);CopyBytes(in,out,old.base);}
  // Strip old web overlay BEFORE resource editing. External output never appends web bytes.
  HANDLE update=BeginUpdateResourceW(temp.c_str(),FALSE);if(!update)throw std::runtime_error("Cannot open output resources");
  try{
   std::string metadata;for(auto& item:options)metadata+=item.first+"="+Utf8(item.second)+"\n";
   if(!UpdateResourceW(update,RT_RCDATA,MAKEINTRESOURCEW(501),0,&metadata[0],(DWORD)metadata.size()))throw std::runtime_error("Cannot write builder options");
   if(!icon.empty())Icon(update,icon);
   if(!nwIcon.empty()){auto png=Bytes(nwIcon);const unsigned char signature[]={137,80,78,71,13,10,26,10};if(png.size()<33 || png.size()>16*1024*1024 || memcmp(png.data(),signature,8)!=0)throw std::runtime_error("--nw-icon requires PNG");if(!UpdateResourceW(update,RT_RCDATA,MAKEINTRESOURCEW(409),0x409,png.data(),(DWORD)png.size()))throw std::runtime_error("NW.js icon update failed");}
   if(options.count("name") || options.count("version") || options.count("copyright"))Version(update,options);
  }catch(...){EndUpdateResourceW(update,TRUE);throw;}
  if(!EndUpdateResourceW(update,FALSE))throw std::runtime_error("Cannot commit resources");
  if(mode==L"embedded"){
   std::ofstream out(temp,std::ios::binary|std::ios::app);out.seekp(0,std::ios::end);if(!out || out.tellp()<std::streampos(64))throw std::runtime_error("Cannot position EXE output");std::ostringstream manifest;manifest<<"https://noon.invalid/dir.html\n";
   for(auto& e:entries){uint64_t at=(uint64_t)out.tellp();std::ifstream in(e.source,std::ios::binary);if(!in)throw std::runtime_error("Cannot read web file");in.seekg(e.offset);CopyBytes(in,out,e.size);manifest<<e.path<<'\t'<<at<<'\t'<<e.size<<'\n';}
   uint64_t offset=(uint64_t)out.tellp();std::string index=manifest.str();out.write(index.data(),index.size());out.write("NHTMLPK1",8);Write64(out,offset);Write64(out,index.size());out.flush();if(!out)throw std::runtime_error("Cannot finish web payload");
  }
  if(mode==L"external"){
   // Keep a private, non-archive overlay, without retaining any web assets.
   std::ofstream out(temp,std::ios::binary|std::ios::app);out.seekp(0,std::ios::end);if(!out || out.tellp()<std::streampos(64))throw std::runtime_error("Cannot position EXE output");uint64_t base=(uint64_t)out.tellp();
   out.write("NEXTAPP1",8);Write64(out,base);Write64(out,0);out.flush();
   if(!out)throw std::runtime_error("Cannot finish external app trailer");
  }
  Payload validated=ReadPayload(temp);if(mode==L"embedded" && validated.entries.size()!=entries.size())throw std::runtime_error("Invalid generated manifest");
  if(mode==L"external"){
   fs::path config=options.count("config-path")?options["config-path"]:L"bin/config.js";
   if(config.is_relative())config=output.parent_path()/config;
   if(fs::is_directory(config))config/=L"config.js";
   fs::path app=options["app"];if(app.is_relative())app=output.parent_path()/app;
   std::wstring settingsError;
   if(!SaveNoonBuildSettings(config.wstring(),std::stoi(options.count("port")?options["port"]:L"4030"),options.count("version")?options["version"]:L"1.0.0.1",app.wstring(),settingsError,options.count("boot-file")?options["boot-file"]:L"dir.html",options["require-password"]==L"1"?1:0,options.count("external-password")?options["external-password"]:L"noon",options.count("app-type")?options["app-type"]:L"MN"))throw std::runtime_error(Utf8(settingsError));
  }
  if(!MoveFileExW(temp.c_str(),output.c_str(),MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Cannot save generated EXE");temp.clear();
  if(!embeddedConfigTemp.empty()){std::error_code ec;fs::remove(embeddedConfigTemp,ec);embeddedConfigTemp.clear();}
  report=L"Built / تم الإنشاء: "+output.wstring()+L"\nMode: "+mode+L"\nWeb files embedded: "+std::to_wstring(mode==L"external"?size_t(0):entries.size());
 }catch(const std::exception& e){report=Wide(e.what());if(!embeddedConfigTemp.empty()){std::error_code ec;fs::remove(embeddedConfigTemp,ec);}if(!temp.empty()){std::error_code ec;fs::remove(temp,ec);}return false;}
 return true;
}
