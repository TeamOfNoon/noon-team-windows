#include "pch.h"
#include "BuildGui.h"
#include "AppBuilder.h"
#include "NoonServer.h"
#include "AppFolderName.h"
#include <commdlg.h>
#include <shlobj.h>
#include <filesystem>
#include <string>
#include <vector>
#include <algorithm>
#include <thread>
#include <atomic>
#include <functional>
#include <fstream>
#include <stdexcept>
#include <utility>
#include <commctrl.h>
#pragma comment(lib,"comctl32.lib")
#pragma comment(lib,"comdlg32.lib")
#pragma comment(lib,"shell32.lib")
namespace {
constexpr UINT JobCompleteMessage=WM_APP+73;
struct JobResult { bool ok=false;std::wstring text,title; };
struct ControlPlacement { HWND control;RECT bounds;bool visible; };
struct State {
 bool about=false,building=false,openBuilder=false,advanced=false,extraction=false;
 std::vector<ControlPlacement> placements;int clicks=0,scroll=0; HWND hwnd=nullptr;HFONT font=nullptr;
 std::thread worker;std::atomic<bool> complete{false};JobResult result;
 ~State(){if(worker.joinable())worker.join();}
};
void Show(bool about,bool extraction=false);
std::wstring Exe(){wchar_t b[32768]{};GetModuleFileNameW(nullptr,b,32768);return b;}
std::wstring Value(HWND w,int id){HWND c=GetDlgItem(w,id);int n=GetWindowTextLengthW(c);std::wstring s(n+1,L'\0');GetWindowTextW(c,&s[0],n+1);s.resize(n);return s;}
void Control(State* s,const wchar_t* cls,const wchar_t* text,DWORD style,int id,int x,int y,int width,int height){auto c=CreateWindowExW(cls==std::wstring(L"EDIT")?WS_EX_CLIENTEDGE:0,cls,text,WS_CHILD|WS_VISIBLE|style,x,y,width,height,s->hwnd,(HMENU)(INT_PTR)id,GetModuleHandleW(nullptr),nullptr);SendMessageW(c,WM_SETFONT,(WPARAM)s->font,TRUE);}
void Row(State* s,int id,int y,const wchar_t* label,const std::wstring& value,int picker=0){Control(s,L"STATIC",label,0,id+1000,18,y+5,250,24);Control(s,L"EDIT",value.c_str(),WS_TABSTOP|ES_AUTOHSCROLL,id,275,y,445,28);if(picker)Control(s,L"BUTTON",L"Browse / اختيار",WS_TABSTOP,picker,730,y,135,28);}
std::wstring PickFile(HWND owner,bool save,const wchar_t* filter,const std::wstring& initial=L""){wchar_t b[32768]{};if(!initial.empty())wcsncpy_s(b,initial.c_str(),_TRUNCATE);OPENFILENAMEW o{};o.lStructSize=sizeof(o);o.hwndOwner=owner;o.lpstrFile=b;o.nMaxFile=32768;o.lpstrFilter=filter;o.Flags=OFN_EXPLORER|OFN_NOCHANGEDIR|OFN_PATHMUSTEXIST|(save?OFN_OVERWRITEPROMPT:OFN_FILEMUSTEXIST);if(save)o.lpstrDefExt=L"exe";return (save?GetSaveFileNameW(&o):GetOpenFileNameW(&o))?b:L"";}
int CALLBACK FolderInit(HWND dialog,UINT message,LPARAM,LPARAM data){if(message==BFFM_INITIALIZED && data)SendMessageW(dialog,BFFM_SETSELECTIONW,TRUE,data);return 0;}
std::wstring PickFolder(HWND owner,const std::wstring& initial=L"",const wchar_t* title=L"Web folder / مجلد ملفات الويب"){
 std::wstring start=initial.empty()?std::filesystem::path(Exe()).parent_path().wstring():initial;
 if(!std::filesystem::exists(start))start=std::filesystem::path(Exe()).parent_path().wstring();
 BROWSEINFOW b{};b.hwndOwner=owner;b.lpszTitle=title;b.ulFlags=BIF_RETURNONLYFSDIRS|BIF_NEWDIALOGSTYLE;b.lpfn=FolderInit;b.lParam=(LPARAM)start.c_str();
 PIDLIST_ABSOLUTE item=SHBrowseForFolderW(&b);wchar_t path[MAX_PATH]{};if(item){SHGetPathFromIDListW(item,path);CoTaskMemFree(item);}return path;
}
std::wstring Quote(const std::wstring& s){std::wstring out=L"\"";unsigned slash=0;for(wchar_t c:s){if(c==L'\\'){++slash;continue;}if(c==L'"'){out.append(slash*2+1,L'\\');out+=c;}else{out.append(slash,L'\\');out+=c;}slash=0;}out.append(slash*2,L'\\');return out+L'"';}
struct PasswordState{HWND edit=nullptr;bool accepted=false;std::wstring password;};
LRESULT CALLBACK PasswordProc(HWND w,UINT msg,WPARAM wp,LPARAM lp){
 auto p=(PasswordState*)GetWindowLongPtrW(w,GWLP_USERDATA);
 if(msg==WM_NCCREATE){p=(PasswordState*)((CREATESTRUCTW*)lp)->lpCreateParams;SetWindowLongPtrW(w,GWLP_USERDATA,(LONG_PTR)p);}
 if(!p)return DefWindowProcW(w,msg,wp,lp);
 if(msg==WM_CREATE){
  auto font=(HFONT)GetStockObject(DEFAULT_GUI_FONT);
  auto label=CreateWindowW(L"STATIC",L"Password / كلمة المرور",WS_CHILD|WS_VISIBLE,18,18,300,24,w,nullptr,GetModuleHandleW(nullptr),nullptr);
  p->edit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",L"",WS_CHILD|WS_VISIBLE|WS_TABSTOP|ES_PASSWORD|ES_AUTOHSCROLL,18,48,320,28,w,(HMENU)210,GetModuleHandleW(nullptr),nullptr);
  auto ok=CreateWindowW(L"BUTTON",L"OK / موافق",WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_DEFPUSHBUTTON,45,92,120,30,w,(HMENU)IDOK,GetModuleHandleW(nullptr),nullptr);
  auto cancel=CreateWindowW(L"BUTTON",L"Cancel / إلغاء",WS_CHILD|WS_VISIBLE|WS_TABSTOP,190,92,130,30,w,(HMENU)IDCANCEL,GetModuleHandleW(nullptr),nullptr);
  for(auto c:{label,p->edit,ok,cancel})SendMessageW(c,WM_SETFONT,(WPARAM)font,TRUE);SendMessageW(p->edit,EM_SETLIMITTEXT,128,0);SetFocus(p->edit);return 0;
 }
 if(msg==WM_COMMAND && HIWORD(wp)==BN_CLICKED){if(LOWORD(wp)==IDOK){p->password=Value(w,210);p->accepted=true;DestroyWindow(w);}if(LOWORD(wp)==IDCANCEL)DestroyWindow(w);return 0;}
 if(msg==WM_CLOSE){DestroyWindow(w);return 0;}return DefWindowProcW(w,msg,wp,lp);
}
bool AskPassword(HWND owner,std::wstring& password){
 WNDCLASSW cls{};cls.lpfnWndProc=PasswordProc;cls.hInstance=GetModuleHandleW(nullptr);cls.lpszClassName=L"NoonExtractPassword";cls.hCursor=LoadCursor(nullptr,IDC_ARROW);cls.hbrBackground=(HBRUSH)(COLOR_BTNFACE+1);RegisterClassW(&cls);
 PasswordState state;HWND w=CreateWindowExW(WS_EX_DLGMODALFRAME,cls.lpszClassName,L"Unlock builder / فتح أداة الإنشاء",WS_CAPTION|WS_SYSMENU,CW_USEDEFAULT,CW_USEDEFAULT,375,175,owner,nullptr,cls.hInstance,&state);
 if(!w){std::wstring error=L"Cannot open password dialog. Windows error: "+std::to_wstring(GetLastError());MessageBoxW(owner,error.c_str(),L"Noon",MB_ICONERROR);return false;}
 RECT rect{};if(GetWindowRect(owner,&rect))SetWindowPos(w,nullptr,rect.left+((rect.right-rect.left)-375)/2,rect.top+((rect.bottom-rect.top)-175)/2,0,0,SWP_NOSIZE|SWP_NOZORDER);
 EnableWindow(owner,FALSE);ShowWindow(w,SW_SHOW);SetForegroundWindow(w);SetFocus(state.edit);MSG msg;
 while(IsWindow(w) && GetMessageW(&msg,nullptr,0,0)>0){if(!IsDialogMessageW(w,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}
 EnableWindow(owner,TRUE);SetActiveWindow(owner);password=state.password;return state.accepted;
}
void SetBusy(State* s,bool busy,const wchar_t* status){
 s->building=busy;
 for(int id:{101,102,103,104,105,106,107,108,109,110,111,113,114,115,116,117,118,126,127,128,129,130,139,140,141,143,144,131,132,133,136,120,122,125,142,301,302,306,307,309,313})EnableWindow(GetDlgItem(s->hwnd,id),!busy);
 HWND progress=GetDlgItem(s->hwnd,123);ShowWindow(progress,busy?SW_SHOW:SW_HIDE);SendMessageW(progress,PBM_SETMARQUEE,busy,35);
 SetWindowTextW(GetDlgItem(s->hwnd,124),busy?status:L"");
 if(busy){SetWindowTextW(GetDlgItem(s->hwnd,121),status);SetTimer(s->hwnd,1,100,nullptr);}else KillTimer(s->hwnd,1);
}
std::wstring EditLines(const std::wstring& text){std::wstring result;for(size_t i=0;i<text.size();++i){if(text[i]==L'\n'&&(i==0||text[i-1]!=L'\r'))result+=L'\r';result+=text[i];}return result;}
void FinishJob(State* s){
 if(!s->building || !s->complete.exchange(false,std::memory_order_acquire))return;
 if(s->worker.joinable())s->worker.join();
 JobResult result=std::move(s->result);SetBusy(s,false,L"");
 auto display=EditLines(result.text);SetWindowTextW(GetDlgItem(s->hwnd,121),display.c_str());
 MessageBoxW(s->hwnd,result.text.c_str(),result.title.c_str(),result.ok?MB_ICONINFORMATION:MB_ICONERROR);
}
void StartJob(State* s,const wchar_t* status,const wchar_t* title,std::function<void(JobResult&)> action){
 if(s->building)return;
 s->complete.store(false,std::memory_order_relaxed);SetBusy(s,true,status);
 try{
  s->worker=std::thread([s,action,title=std::wstring(title)](){
   JobResult result;result.title=title;
   try{action(result);}catch(const std::exception& e){
    std::string text=e.what();int n=MultiByteToWideChar(CP_UTF8,0,text.data(),(int)text.size(),nullptr,0);result.text.resize(n);
    if(n)MultiByteToWideChar(CP_UTF8,0,text.data(),(int)text.size(),&result.text[0],n);
   }catch(...){result.text=L"Operation failed / فشلت العملية";}
   s->result=std::move(result);s->complete.store(true,std::memory_order_release);PostMessageW(s->hwnd,JobCompleteMessage,0,0);
  });
 }catch(...){SetBusy(s,false,L"");MessageBoxW(s->hwnd,L"Cannot start background operation / تعذر بدء العملية",L"Noon",MB_ICONERROR);}
}
void Extract(State* s,bool indexes=false){
 if(s->building)return;
 int format=indexes?(int)SendMessageW(GetDlgItem(s->hwnd,114),CB_GETCURSEL,0,0)+1:0;
 int kinds=0;for(int id:{115,116,117})if(SendMessageW(GetDlgItem(s->hwnd,id),BM_GETCHECK,0,0)==BST_CHECKED)kinds|=1<<(id-115);
 if(indexes&&kinds==0){MessageBoxW(s->hwnd,L"Select Full, Partial or Stemmed / اختر نوع فهرسة واحدا على الأقل",L"Noon",MB_ICONWARNING);return;}
 int ranks=0;for(int id:{126,127,128,129})if(SendMessageW(GetDlgItem(s->hwnd,id),BM_GETCHECK,0,0)==BST_CHECKED)ranks|=1<<(id-126);
 if(indexes&&ranks==0){MessageBoxW(s->hwnd,L"Select at least one ranking / اختر ترتيبا واحدا على الأقل",L"Noon",MB_ICONWARNING);return;}
 std::wstring ranges;if(indexes){HWND list=GetDlgItem(s->hwnd,118);int count=(int)SendMessageW(list,LB_GETCOUNT,0,0);for(int i=0;i<count;i++)if(SendMessageW(list,LB_GETSEL,i,0)>0){int length=(int)SendMessageW(list,LB_GETTEXTLEN,i,0);std::wstring name(length+1,L'\0');SendMessageW(list,LB_GETTEXT,i,(LPARAM)&name[0]);name.resize(length);if(!ranges.empty())ranges+=L",";ranges+=name;}if(ranges.empty()){MessageBoxW(s->hwnd,L"Select an available range / اختر قسما متاحا للفهرسة",L"Noon",MB_ICONWARNING);return;}}
 auto folder=Value(s->hwnd,113);if(folder.empty()){MessageBoxW(s->hwnd,L"Choose an extraction folder / اختر مجلد الاستخراج",L"Noon",MB_ICONINFORMATION);return;}
 StartJob(s,indexes?L"Generating selected offline indexes... / جار توليد الفهارس المختارة...":L"Extracting databases and generating viewer... / جار استخراج البيانات وتوليد ملفات العرض...",indexes?L"Generate indexes / توليد الفهارس":L"Extract app data / استخراج بيانات التطبيق",
  [folder,format,kinds,ranges,ranks](JobResult& result){result.ok=ExtractEmbeddedWebTo(folder,result.text,format,kinds,ranges,ranks);});
}

std::wstring ConfigPassword(const std::wstring& password){std::wstring result=L"\"";for(wchar_t c:password){if(c==L'\\'||c==L'\"')result+=L'\\';result+=c;}return result+L"\"";}
std::wstring FullConfigPreview(const std::wstring& password,bool required,const std::wstring& boot=L"dir.html",const std::wstring& version=L"1.0.0.1",const std::wstring& port=L"4030",bool secure=false,const std::wstring& appType=L"MN"){
 std::wstring number=port;if(number.empty()||number.find_first_not_of(L"0123456789")!=std::wstring::npos||number.size()>5)number=L"4030";else if(std::stoul(number)<1||std::stoul(number)>65535)number=L"4030";
 std::wstring text=L"(function (w) {\r\n  w.NT_CONFIG = {\r\n";
 if(required)text+=L"  \"pass\": "+ConfigPassword(password)+L",\r\n";
 text+=L"  \"secure\": "+std::wstring(secure?L"true":L"false")+L",\r\n";
 text+=L"  \"external_app_path\": \"app\",\r\n  \"external_content_type\": \"auto\",\r\n";
 text+=L"  \"boot_file\": "+ConfigPassword(boot.empty()?L"dir.html":boot)+L",\r\n";
 text+=L"  \"app_version\": "+ConfigPassword(version.empty()?L"1.0.0.1":version)+L",\r\n  \"server_port\": "+number+L",\r\n";
 text+=LR"CONFIG(  // Browser: auto, webview2, nwjs (aliases: nw, node-webkit).
  "webview_type": "auto",
  "show_navigation_bar": false,
  "show_menu_bar": false,
  // show_toolbar is an alias for show_navigation_bar; either true shows the bar.
  // "show_toolbar": false,
  // Web settings: inherited from the supplied web app when omitted.
  // "hide_console_output": false,
  // "support_email": "noon@noon-team.org",
  // "original_date_source": "content",
  // "search_page_batch": 10,
  // "stop_word_path": "res/SW.js",
  // "show_initial_index_dialog_again": false,
  // "static_view": false,
  // Legacy lists: prefer forum_defaults/forum_overrides with auto detection.
  // "hidden_search_forms": ["vb6", "vb5", "vb2"],
  // "viewer_forms": ["mn24", "mn33", "vb2", "vb4", "vb5", "vb6"],
  // "search_forms": [{"formid":"mn33", "show":true, "enabled":true}],
  // "search_ranges": [{"formid":"mn33", "show":true, "enabled":true}],
  // "forums": [{"id":"mn33", "show_in_index":true}],
  "verify_url": "https://www.noon-team.org/verify",
  "update_api": "",
  "installable_forms_base": "",
  "update_secure": true,
  "console_debug": true,
  "server_wait_ms": 1800,
  "lucene_index_done": false,
  "updatbleformids": ["ar:mn33,und:vb6,fa:mn54"],
  "cache_update": 1,
  "first_launch": false,
  "update_mode": "static_state",
  "app_type": "__APP_TYPE__",
  "search_index_type": "lucene",
  "search_engin_type": "lucene",
  "forum_defaults": {
    "dir": "rtl",
    "show_in_viewer": true,
    "show_in_search": true,
    "show_in_index": true
  },
  "forum_overrides": {
    "mn33": {"dir": "rtl"},
    "mn24": {"dir": "ltr"},
    "vb2": {"show_in_search": false, "show_in_index": false},
    "vb5": {"show_in_search": false, "show_in_index": false},
    "vb6": {"show_in_search": false, "show_in_index": false}
  },
  "ui": {
    // Web app selector options (availability depends on the supplied web assets).
    // "searchModeSelector": {"show":true, "options":{"phrase":true,"all":true,"any":true}},
    "searchTypeSelector": {
      "show": true,
      "options": {"full_index": true, "partial_index": true, "root_index": true, "wildcard_index": true}
    },
    "searchSortSelector": {
      "show": true,
      "options": {"occurrences": true, "relevance": true, "date_newest": true, "date_oldest": true}
    },
    "searchRanges": {"show": true, "showCustomButton": false},
    "indexingOptionsSheet": {"show": true}
  }
  };
})(window);
)CONFIG";
 if(secure){auto path=text.find(L"\"external_app_path\": \"app\"");if(path!=std::wstring::npos)text.replace(path,std::wstring(L"\"external_app_path\": \"app\"").size(),L"\"external_app_path\": \"NT/app.dat\"");}
 auto at=text.find(L"__APP_TYPE__");if(at!=std::wstring::npos)text.replace(at,12,appType==L"SN"?L"SN":L"MN");
 return text;
}
struct ConfigExample { std::wstring name,description,text; };
std::vector<ConfigExample> ConfigExamples(const std::wstring& password=L"noon",bool required=false,const std::wstring& full=L""){
 if(!full.empty())return {{L"config",required?L"Require password ON":L"Require password OFF",full}};
 const std::wstring settings=L"  boot_file: \"dir.html\",\r\n"
  L"  external_content_type: \"auto\",\r\n"
  L"  // Optional folder relative to bin: app, ., or NT/app.dat.\r\n"
  L"  // external_app_path: \"app\",\r\n"
  L"  server_port: 4030,\r\n"
  L"  app_version: \"1.0.0.1\"\r\n  };\r\n})(window);\r\n";
 return {{L"config",required?L"Require password ON":L"Require password OFF",L"(function (w) {\r\n  w.NT_CONFIG = {\r\n"+(required?L"  pass: "+ConfigPassword(password)+L",\r\n":std::wstring())+settings}};
}
std::wstring ParameterHelpText(const std::wstring& password=L"noon",bool required=false,const std::wstring& full=L""){
 std::wstring text=LR"HELP(CONFIGURATION REFERENCE / مرجع جميع خيارات الإعدادات

Save config.js in the Settings path folder (default bin/) beside the EXE.
Custom settings folder is checked first; bin/ is the content/config fallback.
Use (function (w) { w.NT_CONFIG = { ... }; })(window), matching res/config.js.
Comments, nested objects and arrays are supported.
Relative external_app_path resolves from the selected config folder.
Configuration order: web res/config.js -> packaged bin/config.js -> external
bin/config.js -> runtime availability. Objects merge; arrays are replaced.
The template below uses current GUI boot/version/port/security/app type values.
Commented examples are optional; uncomment only settings you want to override.
Export config.js exports ONLY the template, without this reference text.

1. BROWSER / المتصفح
webview_type: auto | webview2 | nwjs. auto prefers WebView2, falls back to NW.js.
Aliases nw and node-webkit mean nwjs. Runtime --webview overrides config.
show_navigation_bar: true | false. Show the browser navigation bar.
show_toolbar: true | false. Alias; either navigation flag true enables the bar.
show_menu_bar: true | false. Show the application menu bar.
Browser flags default false. Browser config uses --config / --config-path
(or --config=PATH); otherwise the build's config path, normally bin/config.js.

2. CONTENT, SECURITY AND SERVER / المحتوى والأمان والخادم
pass: string matching the builder password when Require password is ON.
external_password, password: legacy aliases (priority pass, external_password,
password). OFF omits all credentials from the exported template.
secure: true | false. true requires signed NT/app.dat and default layout.
A build with Secure ON cannot be weakened by secure:false in config.
external_app_path: folder or app.dat path; examples NT/app.dat, app, .,
or an absolute path. Omit to auto-detect available content.
external_content_type: auto | default | static. auto detects; default uses
SQLite/Lucene layout; static opens exported HTML. Secure forbids static.
boot_file: entry file name, e.g. dir.html; fallback is dir.html.
app_version: version string, e.g. 1.0.0.1.
server_port: integer 1..65535; external setting overrides embedded port.
app_type: MN | SN. Separates app branding and cache/temp roots for both browsers.
Cache/temp roots: LOCALAPPDATA/mn_<ExeName>/ or sn_<ExeName>/.

3. SEARCH AND WEB SETTINGS / البحث وإعدادات الواجهة
search_index_type: lucene | js | bin (canonical json_js | json_bin also work).
Aliases: lucen/lucy -> lucene; json/json-js -> json_js;
binary/json-bin -> json_bin. Unknown types fall back to json_js.
Legacy keys, in fallback order: search_engin_type, search_engine_type,
search_engn_type. Prefer search_index_type; avoid conflicting aliases.
stop_word_path: stop-word JS path relative to www, e.g. res/SW.js.
hide_console_output: true | false; application console visibility.
support_email: Help contact address string.
original_date_source: content | db; statement text or database dates.
search_page_batch: positive result-page batch size, e.g. 10.
show_initial_index_dialog_again: true | false; initial indexing prompt option.
static_view: true | false; static export flag; normally controlled by exporter.
Web-only options require matching web app assets. This source archive does not
contain the external www/res/config.js: retain any additional options declared
by that app there. The server forwards additional properties to NT_CONFIG.

4. UPDATE / التحديث
verify_url: verification endpoint URL string.
update_api: update API base URL string; empty example means no configured URL.
installable_forms_base: forum package base URL string.
update_secure: true | false; web updater secure package setting.
console_debug: true | false; web update/debug output setting.
server_wait_ms: server wait in milliseconds, e.g. 1800.
lucene_index_done: boolean completion flag; live server status is authoritative.
updatbleformids: array of language:forum lists (spelling retained for compatibility),
e.g. ["ar:mn33,und:vb6,fa:mn54"].
cache_update: numeric cache revision, e.g. 1.
first_launch: true | false; first-launch state.
update_mode: string; supplied config uses static_state. Other modes depend on
the supplied web updater; the native launcher does not validate this field.

5. FORUMS AND GENERATED STATE / الأقسام وحالة الفهرسة
forum_defaults: object defining defaults for every detected forum.
forum_overrides: object keyed by forum code, e.g. mn33, mn24, vb4.
Each supports dir: rtl | ltr; show_in_viewer, show_in_search, show_in_index:
true | false. An override replaces the corresponding default for that forum.
Legacy hidden_search_forms: array of forum IDs excluded from search/index.
Legacy viewer_forms: array of allowed forum IDs.
Legacy search_forms/search_ranges: arrays of IDs or objects with formid
(or id), show, enabled, installed, has_sqlite (boolean flags).
Legacy forums: array of objects with id (or formid), show_in_index,
installed, has_sqlite. Prefer defaults/overrides over hand-maintained lists.
Runtime generates availability/range data from the databases and indexes:
installed, has_sqlite, indexed, partially_indexed, indexed_count, index_kinds.
index_kinds keys: full_index, wildcard_index, partial_index, root_index.
Do not invent installed/indexed values to enable unavailable data or indexes.
Export/runtime may regenerate search_forms, search_ranges and viewer_forms.

6. UI OBJECTS / خيارات عناصر الواجهة
ui.searchTypeSelector.show: true | false.
ui.searchTypeSelector.options: full_index, partial_index, root_index,
wildcard_index (each true | false). Available indexes still control availability.
ui.searchSortSelector.show: true | false.
ui.searchSortSelector.options: occurrences, relevance, date_newest,
date_oldest (each true | false).
ui.searchModeSelector.show: true | false; web app dependent.
ui.searchModeSelector.options: phrase, all, any (each true | false).
ui.searchRanges.show: true | false.
ui.searchRanges.showCustomButton: true | false.
ui.indexingOptionsSheet.show: true | false.

7. BUILDER OPTIONS ARE NOT CONFIG.JS ATTRIBUTES / خيارات البناء
config_path (Settings path), name, copyright, icon, source/output paths,
Require password and embedded/external build mode belong to the builder.
Choose them in --gui; adding these names to config.js does not configure builds.

CONFIG.JS TEMPLATE / نموذج الإعدادات

)HELP";
 for(const auto& example:ConfigExamples(password,required,full))text+=example.description+L"\r\n\r\n"+example.text+L"\r\n";
 return text;
}
struct HelpState { HFONT font=nullptr;std::wstring password,config;bool required=false; };
LRESULT CALLBACK ParameterHelpProc(HWND w,UINT msg,WPARAM wp,LPARAM lp){
 auto state=(HelpState*)GetWindowLongPtrW(w,GWLP_USERDATA);
 if(msg==WM_NCCREATE){state=(HelpState*)((CREATESTRUCTW*)lp)->lpCreateParams;SetWindowLongPtrW(w,GWLP_USERDATA,(LONG_PTR)state);}
 if(!state)return DefWindowProcW(w,msg,wp,lp);
 if(msg==WM_CREATE){
  auto edit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",L"",WS_CHILD|WS_VISIBLE|WS_TABSTOP|WS_VSCROLL|WS_HSCROLL|ES_MULTILINE|ES_READONLY|ES_AUTOVSCROLL|ES_AUTOHSCROLL,16,16,800,480,w,(HMENU)401,GetModuleHandleW(nullptr),nullptr);
  SendMessageW(edit,EM_SETLIMITTEXT,1024*1024,0);auto text=EditLines(ParameterHelpText(state->password,state->required,state->config));SetWindowTextW(edit,text.c_str());SendMessageW(edit,WM_SETFONT,(WPARAM)state->font,TRUE);
  for(auto item:{std::make_pair(404,L"Export config.js / تصدير الإعدادات"),std::make_pair(403,L"Close / إغلاق")}){
   auto button=CreateWindowW(L"BUTTON",item.second,WS_CHILD|WS_VISIBLE|WS_TABSTOP,16,510,330,32,w,(HMENU)(INT_PTR)item.first,GetModuleHandleW(nullptr),nullptr);SendMessageW(button,WM_SETFONT,(WPARAM)state->font,TRUE);
  }
  return 0;
 }
 if(msg==WM_GETMINMAXINFO){auto bounds=(MINMAXINFO*)lp;bounds->ptMinTrackSize.x=600;bounds->ptMinTrackSize.y=360;return 0;}
 if(msg==WM_SIZE){int width=LOWORD(lp),height=HIWORD(lp);MoveWindow(GetDlgItem(w,401),16,16,(std::max)(100,width-32),(std::max)(80,height-78),TRUE);MoveWindow(GetDlgItem(w,404),16,(std::max)(140,height-48),360,32,TRUE);MoveWindow(GetDlgItem(w,403),(std::max)(390,width-166),(std::max)(140,height-48),150,32,TRUE);return 0;}
 if(msg==WM_COMMAND){
  if(LOWORD(wp)==403){DestroyWindow(w);return 0;}
  if(LOWORD(wp)==404){
   auto selected=PickFolder(w,L"",L"Save config.js / حفظ الإعدادات");if(selected.empty())return 0;
   auto root=std::filesystem::path(selected)/L"Noon_Config";
   try{
    unsigned suffix=1;while(std::filesystem::exists(root))root=std::filesystem::path(selected)/(L"Noon_Config_"+std::to_wstring(suffix++));
    std::filesystem::create_directories(root);
    for(const auto& example:ConfigExamples(state->password,state->required,state->config)){
     auto directory=root;
     auto text=example.text;int count=WideCharToMultiByte(CP_UTF8,0,text.data(),(int)text.size(),nullptr,0,nullptr,nullptr);std::string bytes(count,'\0');WideCharToMultiByte(CP_UTF8,0,text.data(),(int)text.size(),bytes.data(),count,nullptr,nullptr);
     std::ofstream file(directory/L"config.js",std::ios::binary);file<<bytes;file.close();if(!file)throw std::runtime_error("Cannot write config sample");
    }
    auto message=L"Saved config.js matching Require password. Copy it to bin/config.js beside the EXE.\nتم حفظ الإعدادات حسب خيار كلمة المرور. انسخ الملف إلى bin/config.js.\n"+root.wstring();MessageBoxW(w,message.c_str(),L"Noon",MB_OK);
   }catch(const std::exception&){MessageBoxW(w,L"Could not save all config samples. Check folder permissions.",L"Noon",MB_ICONERROR);}
   return 0;
  }

 }
 if(msg==WM_CLOSE){DestroyWindow(w);return 0;}return DefWindowProcW(w,msg,wp,lp);
}
void ShowParameterHelp(HWND owner,HFONT font,const std::wstring& password,bool required){
 HelpState state;state.font=font;state.password=password;state.required=required;
 state.config=FullConfigPreview(password,required,Value(owner,141),Value(owner,104),Value(owner,108),SendMessageW(GetDlgItem(owner,130),BM_GETCHECK,0,0)==BST_CHECKED,SendMessageW(GetDlgItem(owner,143),CB_GETCURSEL,0,0)==1?L"SN":L"MN");
 WNDCLASSW c{};c.lpfnWndProc=ParameterHelpProc;c.hInstance=GetModuleHandleW(nullptr);c.lpszClassName=L"NoonParameterHelp";c.hCursor=LoadCursor(nullptr,IDC_ARROW);c.hbrBackground=(HBRUSH)(COLOR_BTNFACE+1);RegisterClassW(&c);
 RECT work{};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);
 HWND w=CreateWindowExW(WS_EX_DLGMODALFRAME,c.lpszClassName,L"Configuration Help / مرجع الإعدادات",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,(std::min)(1000,(int)(work.right-work.left)-32),(std::min)(740,(int)(work.bottom-work.top)-32),owner,nullptr,c.hInstance,&state);
 if(!w)return;EnableWindow(owner,FALSE);ShowWindow(w,SW_SHOW);MSG msg;
 while(IsWindow(w)&&GetMessageW(&msg,nullptr,0,0)>0){if(!IsDialogMessageW(w,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}
 EnableWindow(owner,TRUE);SetActiveWindow(owner);
}
void Build(State* s){
 if(s->building)return;
 std::wstring output=Value(s->hwnd,101),app=Value(s->hwnd,102);bool external=SendMessageW(GetDlgItem(s->hwnd,110),CB_GETCURSEL,0,0)==1;
 if(output.empty() || (external && app.empty())){MessageBoxW(s->hwnd,L"Choose an output EXE. External mode also requires a web folder.\nحدد ملف EXE الناتج ومجلد الويب للوضع الخارجي.",L"Noon",MB_ICONWARNING);return;}
 if(std::filesystem::exists(output)){MessageBoxW(s->hwnd,L"Output already exists. Choose a new filename.\nالملف موجود. اختر اسما جديدا.",L"Noon",MB_ICONWARNING);return;}
 std::map<std::string,std::wstring> values;
 auto customFolder=Value(s->hwnd,144);
 if(!customFolder.empty()){
  if(!ValidAppFolderName(customFolder)){MessageBoxW(s->hwnd,L"Enter one valid custom folder name, without a path.",L"Noon",MB_ICONWARNING);return;}
  values["runtime-folder"]=customFolder;
 }
 values["app-type"]=SendMessageW(GetDlgItem(s->hwnd,143),CB_GETCURSEL,0,0)==1?L"SN":L"MN";
 values["allow-external-override"]=L"0";
 values["external-secure"]=SendMessageW(GetDlgItem(s->hwnd,130),BM_GETCHECK,0,0)==BST_CHECKED?L"1":L"0";
 values["require-password"]=SendMessageW(GetDlgItem(s->hwnd,139),BM_GETCHECK,0,0)==BST_CHECKED?L"1":L"0";
 values["boot-file"]=Value(s->hwnd,141).empty()?L"dir.html":Value(s->hwnd,141);
 values["external-password"]=Value(s->hwnd,140);
 if(values["require-password"]==L"1" && values["external-password"].empty()){MessageBoxW(s->hwnd,L"Enter a password or turn off Require password.",L"Noon",MB_ICONWARNING);return;}
 if(values["external-password"].empty())values["external-password"]=L"noon";
 values["output"]=output;values["web-mode"]=external?L"external":L"embedded";
 auto option=[&](const std::string& name,int id){auto value=Value(s->hwnd,id);if(!value.empty())values[name]=value;};
 if(!app.empty())option("app",102);option("name",103);option("version",104);option("copyright",105);option("icon",106);option("nw-icon",107);option("port",108);
 values["settings-path"]=Value(s->hwnd,109).empty()?L"bin/":Value(s->hwnd,109);
 values["config-path"]=(std::filesystem::path(values["settings-path"])/L"config.js").wstring();
 int engine=(int)SendMessageW(GetDlgItem(s->hwnd,111),CB_GETCURSEL,0,0);values["webview"]=engine==1?L"webview2":engine==2?L"nwjs":L"auto";
 StartJob(s,L"Building EXE... / جار إنشاء الملف...",L"Build EXE / إنشاء EXE",[values](JobResult& result){result.ok=BuildAppFromGui(values,result.text);});

}


void UpdateModeFields(State* s){
 bool external=SendMessageW(GetDlgItem(s->hwnd,110),CB_GETCURSEL,0,0)==1;
 SetWindowTextW(GetDlgItem(s->hwnd,1102),external?L"External path / مسار الملفات الخارجية":L"App folder to embed / مجلد التضمين");
 if(external&&Value(s->hwnd,102).empty())SetWindowTextW(GetDlgItem(s->hwnd,102),L"bin/");
}
int BuilderContentHeight(const State* s){return s->extraction?714:s->advanced?982:806;}
void ApplyBuilderLayout(State* s){
 HWND w=s->hwnd;s->scroll=0;
 for(const auto& placement:s->placements)ShowWindow(placement.control,SW_HIDE);
 auto show=[&](int id,int x,int y,int width,int height){if(!s->extraction&&id!=143&&id!=1143&&y>=124)y+=36;auto control=GetDlgItem(w,id);MoveWindow(control,x,y,width,height,TRUE);ShowWindow(control,SW_SHOW);};
 auto row=[&](int id,int y,int picker){show(id+1000,18,y+5,250,24);show(id,275,y,445,28);if(picker)show(picker,730,y,135,28);};
 show(137,18,16,690,32);show(131,730,16,135,30);
 SetWindowTextW(GetDlgItem(w,137),s->extraction?L"Extract data and indexes / استخراج البيانات والفهارس":L"Create application / إنشاء التطبيق");
 int footer;
 if(s->extraction){
  row(113,68,313);
  show(1114,18,113,250,24);show(114,275,108,445,110);
  show(115,275,148,155,26);show(116,440,148,210,26);show(117,665,148,200,26);
  show(1126,18,193,250,24);show(126,275,188,290,26);show(127,575,188,290,26);show(128,275,224,290,26);show(129,575,224,290,26);
  show(1118,18,273,250,48);show(118,275,268,590,130);
  show(122,18,420,400,36);show(125,430,420,435,36);show(142,18,466,847,36);footer=512;
 }else{
  row(101,52,301);row(103,88,0);show(1143,18,129,250,24);show(143,275,124,445,150);row(104,124,0);row(105,160,0);
  show(1110,18,201,250,24);show(110,275,196,445,150);
  row(102,232,302);row(109,268,309);row(141,304,0);row(108,340,0);
  show(130,18,380,340,28);show(139,400,380,465,28);row(140,420,0);
  EnableWindow(GetDlgItem(w,140),SendMessageW(GetDlgItem(w,139),BM_GETCHECK,0,0)==BST_CHECKED);
  show(132,18,466,240,36);show(136,275,466,300,36);show(120,625,466,240,36);
  SetWindowTextW(GetDlgItem(w,132),s->advanced?L"Fewer settings / خيارات أقل":L"More settings / خيارات إضافية");
  if(s->advanced){row(106,536,306);row(107,576,307);show(1111,18,621,250,24);show(111,275,616,445,150);row(144,656,0);}
  footer=s->advanced?706:526;UpdateModeFields(s);
 }
 show(124,18,footer,847,24);MoveWindow(GetDlgItem(w,123),18,footer+30+(s->extraction?0:36),847,18,TRUE);ShowWindow(GetDlgItem(w,123),s->building?SW_SHOW:SW_HIDE);
 show(121,18,footer+60,847,72);show(133,730,footer+142,135,30);
 RECT work{};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);
 int height=s->extraction?766:s->advanced?982:806;
 SetWindowPos(w,nullptr,0,0,910,(std::min)(height,(int)(work.bottom-work.top)-16),SWP_NOMOVE|SWP_NOZORDER);
 RECT client{};GetClientRect(w,&client);SCROLLINFO info{sizeof(info),SIF_RANGE|SIF_PAGE|SIF_POS};info.nMax=BuilderContentHeight(s);info.nPage=client.bottom;info.nPos=0;SetScrollInfo(w,SB_VERT,&info,TRUE);InvalidateRect(w,nullptr,TRUE);
}
LRESULT CALLBACK Proc(HWND w,UINT message,WPARAM wp,LPARAM lp){
 auto s=(State*)GetWindowLongPtrW(w,GWLP_USERDATA);
 if(message==WM_NCCREATE){s=(State*)((CREATESTRUCTW*)lp)->lpCreateParams;s->hwnd=w;SetWindowLongPtrW(w,GWLP_USERDATA,(LONG_PTR)s);}
 if(!s)return DefWindowProcW(w,message,wp,lp);
 if(message==JobCompleteMessage){FinishJob(s);return 0;}
 if(message==WM_TIMER && wp==1){FinishJob(s);return 0;}
 if(message==WM_SIZE&&!s->about){SCROLLINFO info{sizeof(info),SIF_RANGE|SIF_PAGE};info.nMax=BuilderContentHeight(s);info.nPage=HIWORD(lp);SetScrollInfo(w,SB_VERT,&info,TRUE);return 0;}
 if(message==WM_VSCROLL&&!s->about){SCROLLINFO info{sizeof(info),SIF_ALL};GetScrollInfo(w,SB_VERT,&info);int next=s->scroll;switch(LOWORD(wp)){case SB_LINEUP:next-=28;break;case SB_LINEDOWN:next+=28;break;case SB_PAGEUP:next-=(int)info.nPage;break;case SB_PAGEDOWN:next+=(int)info.nPage;break;case SB_THUMBTRACK:next=info.nTrackPos;break;}next=(std::max)(0,(std::min)(next,info.nMax-(int)info.nPage+1));ScrollWindowEx(w,0,s->scroll-next,nullptr,nullptr,nullptr,nullptr,SW_SCROLLCHILDREN|SW_INVALIDATE|SW_ERASE);s->scroll=next;SetScrollPos(w,SB_VERT,next,TRUE);return 0;}
 if(message==WM_MOUSEWHEEL&&!s->about){SendMessageW(w,WM_VSCROLL,GET_WHEEL_DELTA_WPARAM(wp)>0?SB_LINEUP:SB_LINEDOWN,0);return 0;}
 if(message==WM_CREATE){s->font=(HFONT)GetStockObject(DEFAULT_GUI_FONT);
  if(s->about){
   Control(s,L"STATIC",L"",SS_ICON|SS_NOTIFY,200,210,22,96,96);SendMessageW(GetDlgItem(w,200),STM_SETICON,(WPARAM)LoadImageW(GetModuleHandleW(nullptr),MAKEINTRESOURCEW(128),IMAGE_ICON,96,96,LR_DEFAULTCOLOR),0);
   auto text=BuiltOption("name",L"Noon")+L"\nVersion / الإصدار: "+BuiltOption("version",L"1.0.0.1")+L"\n\nجميع الحقوق محفوظة لمنتديات البشرى الإسلامية\nAll rights reserved to Al-Bushra Islamic Forums.\n\n"+BuiltOption("copyright",L"Copyright (C) 2026 noon-team.")+L"\nOwner / المالك: noon-team\nhttps://nasser-alyamani.org/\n\nIncludes modified MfcEdgeDemo by Marius Bancila (CC BY 4.0).";
   Control(s,L"STATIC",text.c_str(),SS_CENTER,0,18,130,485,255);Control(s,L"BUTTON",L"Close / إغلاق",WS_TABSTOP,201,190,395,145,30);
  }else{
   auto exeFolder=std::filesystem::path(Exe()).parent_path();
   Row(s,101,18,L"Output EXE / الملف الناتج",(exeFolder/L"Noon_Generated.exe").wstring(),301);Row(s,102,54,L"App folder / مجلد التطبيق",BuiltOption("app",L""),302);
   Control(s,L"STATIC",L"Blank folder in embedded mode reuses existing files / اترك المجلد فارغا لإعادة استخدام الملفات المضمنة",0,0,275,86,600,24);
   Control(s,L"STATIC",L"App type / نوع التطبيق",0,1143,18,129,250,24);
   Control(s,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP,143,275,124,445,150);auto appType=GetDlgItem(w,143);
   SendMessageW(appType,CB_ADDSTRING,0,(LPARAM)L"MN");SendMessageW(appType,CB_ADDSTRING,0,(LPARAM)L"SN");SendMessageW(appType,CB_SETCURSEL,RuntimeAppType()==L"sn"?1:0,0);
   Row(s,144,656,L"Custom base name (+ _mn / _sn)",BuiltOption("runtime-folder",L""));
   Row(s,103,112,L"App name / اسم التطبيق",BuiltOption("name",L"Noon"));Row(s,104,148,L"Version / الإصدار",BuiltOption("version",L"1.0.0.1"));Row(s,105,184,L"Copyright / حقوق النشر",BuiltOption("copyright",L"Copyright (C) 2026 noon-team."));
   auto base=std::filesystem::path(Exe()).parent_path()/L"res";Row(s,106,220,L"EXE icon ICO / أيقونة EXE",std::filesystem::exists(base/L"MfcEdgeDemo.ico")?(base/L"MfcEdgeDemo.ico").wstring():L"",306);Row(s,107,256,L"NW.js icon PNG / أيقونة NW",std::filesystem::exists(base/L"MfcEdgeDemo.png")?(base/L"MfcEdgeDemo.png").wstring():L"",307);
   Row(s,108,292,L"HTTP port / المنفذ",BuiltOption("port",L"4030"));Row(s,109,328,L"config_path / مجلد الإعدادات",BuiltOption("settings-path",L"bin/"),309);
   Control(s,L"STATIC",L"Mode / الوضع",0,1110,18,371,250,24);Control(s,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP,110,275,366,445,150);for(auto t:{L"Embedded / داخل EXE",L"External / مجلد خارجي"})SendMessageW(GetDlgItem(w,110),CB_ADDSTRING,0,(LPARAM)t);SendMessageW(GetDlgItem(w,110),CB_SETCURSEL,BuiltOption("web-mode")==L"external"?1:0,0);
   Control(s,L"BUTTON",L"Help / مساعدة",WS_TABSTOP,131,730,366,135,28);
   Control(s,L"STATIC",L"Browser / المتصفح",0,1111,18,409,250,24);Control(s,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP,111,275,402,445,150);for(auto t:{L"Auto / تلقائي",L"WebView2",L"NW.js"})SendMessageW(GetDlgItem(w,111),CB_ADDSTRING,0,(LPARAM)t);auto type=BuiltOption("webview",L"auto");SendMessageW(GetDlgItem(w,111),CB_SETCURSEL,type==L"webview2"?1:type==L"nwjs"?2:0,0);
   Row(s,113,480,L"Extract folder / مجلد الاستخراج",(exeFolder/L"bin").wstring(),313);
   Control(s,L"STATIC",L"Offline index / فهرس الويب",0,1114,18,524,250,24);
   Control(s,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP,114,275,520,445,110);
   for(auto t:{L"index_js / JavaScript",L"index_bin / Binary"})SendMessageW(GetDlgItem(w,114),CB_ADDSTRING,0,(LPARAM)t);
   SendMessageW(GetDlgItem(w,114),CB_SETCURSEL,0,0);
   Control(s,L"BUTTON",L"Full / كامل",BS_AUTOCHECKBOX|WS_TABSTOP,115,275,558,155,26);
   Control(s,L"BUTTON",L"Partial / جزئي",BS_AUTOCHECKBOX|WS_TABSTOP,116,440,558,210,26);
   Control(s,L"BUTTON",L"Stemmed / جذور",BS_AUTOCHECKBOX|WS_TABSTOP,117,665,558,200,26);
   for(int id:{115,116,117})SendMessageW(GetDlgItem(w,id),BM_SETCHECK,BST_CHECKED,0);
   Control(s,L"STATIC",L"Rankings / ترتيب النتائج",0,1126,18,593,250,24);
   Control(s,L"BUTTON",L"Occurrences / الأكثر تكرارا",BS_AUTOCHECKBOX|WS_TABSTOP,126,275,590,290,26);
   Control(s,L"BUTTON",L"Relevance / الصلة",BS_AUTOCHECKBOX|WS_TABSTOP,127,575,590,290,26);
   Control(s,L"BUTTON",L"Newest / الأحدث",BS_AUTOCHECKBOX|WS_TABSTOP,128,275,624,290,26);
   Control(s,L"BUTTON",L"Oldest / الأقدم",BS_AUTOCHECKBOX|WS_TABSTOP,129,575,624,290,26);
   for(int id:{126,127,128,129})SendMessageW(GetDlgItem(w,id),BM_SETCHECK,BST_CHECKED,0);
   Control(s,L"STATIC",L"Available ranges / الأقسام المتاحة\nClick to select / اضغط للتحديد",0,1118,18,671,250,48);
   Control(s,L"LISTBOX",L"",LBS_MULTIPLESEL|LBS_NOTIFY|WS_BORDER|WS_VSCROLL|WS_TABSTOP,118,275,671,590,78);
   if(s->extraction)for(const auto& range:BuilderIndexRanges())SendMessageW(GetDlgItem(w,118),LB_ADDSTRING,0,(LPARAM)range.c_str());
   SendMessageW(GetDlgItem(w,118),LB_SETSEL,TRUE,-1);
   Control(s,L"BUTTON",L"Build EXE / إنشاء EXE",WS_TABSTOP,120,18,766,215,36);
   Control(s,L"BUTTON",L"Extract signed bin / استخراج bin الموقّع",WS_TABSTOP,142,18,812,847,36);
   Control(s,L"BUTTON",L"Extract app + DB / استخراج البيانات",WS_TABSTOP,122,245,766,275,36);
   Control(s,L"BUTTON",L"Extract + generate indexes / استخراج وفهرسة",WS_TABSTOP,125,532,766,333,36);
   Control(s,L"STATIC",L"",0,124,18,810,847,24);
   Control(s,PROGRESS_CLASSW,L"",PBS_MARQUEE,123,18,838,847,18);ShowWindow(GetDlgItem(w,123),SW_HIDE);
   Control(s,L"EDIT",L"",ES_MULTILINE|ES_READONLY|WS_VSCROLL,121,18,868,847,65);
   Control(s,L"BUTTON",L"Secure / التحقق من التوقيع",BS_AUTOCHECKBOX|WS_TABSTOP,130,18,344,340,28);
   Control(s,L"BUTTON",L"Require password / طلب كلمة مرور",BS_AUTOCHECKBOX|WS_TABSTOP,139,400,344,465,28);
   SendMessageW(GetDlgItem(w,130),BM_SETCHECK,BuiltOption("external-secure",L"0")==L"1"?BST_CHECKED:BST_UNCHECKED,0);
   SendMessageW(GetDlgItem(w,139),BM_SETCHECK,BuiltOption("require-password",L"0")==L"1"?BST_CHECKED:BST_UNCHECKED,0);
   Row(s,140,384,L"Password / كلمة المرور",BuiltOption("external-password",L"noon"));
   Row(s,141,420,L"Boot file / ملف البداية",BuiltOption("boot-file",L"dir.html"));
   Control(s,L"BUTTON",L"More settings / خيارات إضافية",WS_TABSTOP,132,730,112,135,28);
   Control(s,L"BUTTON",L"Extract EXE contents / استخراج EXE",WS_TABSTOP,136,18,942,300,36);
   Control(s,L"STATIC",L"",0,137,18,16,690,32);
   Control(s,L"BUTTON",L"Close / إغلاق",WS_TABSTOP,133,730,942,135,28);
   Control(s,L"STATIC",L"Generated EXEs run without a password. / التطبيق الناتج يعمل دون كلمة مرور.",0,134,18,978,847,30);
   for(HWND child=GetWindow(w,GW_CHILD);child;child=GetWindow(child,GW_HWNDNEXT)){
    RECT bounds{};GetWindowRect(child,&bounds);MapWindowPoints(nullptr,w,(POINT*)&bounds,2);
    s->placements.push_back({child,bounds,(GetWindowLongPtrW(child,GWL_STYLE)&WS_VISIBLE)!=0});
   }
   ApplyBuilderLayout(s);

  }
  return 0;
 }
 if(message==WM_COMMAND){int id=LOWORD(wp);if(s->about){if(id==200 && HIWORD(wp)==STN_CLICKED && ++s->clicks==7){s->openBuilder=true;DestroyWindow(w);}if(id==201)DestroyWindow(w);return 0;}
  if(s->building)return 0;if(id==110&&HIWORD(wp)==CBN_SELCHANGE){UpdateModeFields(s);return 0;}if(id==139){EnableWindow(GetDlgItem(w,140),SendMessageW(GetDlgItem(w,139),BM_GETCHECK,0,0)==BST_CHECKED);return 0;}if(id==136){Show(false,true);return 0;}if(id==132){s->advanced=!s->advanced;ApplyBuilderLayout(s);return 0;}if(id==133){DestroyWindow(w);return 0;}if(id==131){ShowParameterHelp(w,s->font,Value(w,140),SendMessageW(GetDlgItem(w,139),BM_GETCHECK,0,0)==BST_CHECKED);return 0;}if(id==120){Build(s);return 0;}if(id==122){Extract(s);return 0;}if(id==125){Extract(s,true);return 0;}if(id==142){auto folder=Value(w,113);if(folder.empty()){MessageBoxW(w,L"Choose an empty extraction folder",L"Noon",MB_ICONINFORMATION);return 0;}StartJob(s,L"Extracting signed bin...",L"Extract signed bin",[folder](JobResult& result){result.ok=ExtractSignedBinTo(folder,result.text);});return 0;}std::wstring selected;int target=0;
  if(id==313){selected=PickFolder(w,Value(w,113),L"Extraction destination / مجلد الاستخراج");target=113;}
  if(id==301){selected=PickFile(w,true,L"Executable (*.exe)\0*.exe\0\0",Value(w,101));target=101;}if(id==302){selected=PickFolder(w,Value(w,102));target=102;}if(id==306){selected=PickFile(w,false,L"Windows icon (*.ico)\0*.ico\0\0");target=106;}if(id==307){selected=PickFile(w,false,L"PNG image (*.png)\0*.png\0\0");target=107;}if(id==309){selected=PickFolder(w,Value(w,109),L"Settings folder / مجلد الإعدادات");target=109;}if(!selected.empty())SetWindowTextW(GetDlgItem(w,target),selected.c_str());return 0;
 }
 if(message==WM_CLOSE){if(!s->building)DestroyWindow(w);return 0;}return DefWindowProcW(w,message,wp,lp);
}
void Show(bool about,bool extraction){INITCOMMONCONTROLSEX controls{sizeof(controls),ICC_PROGRESS_CLASS};InitCommonControlsEx(&controls);HRESULT hr=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);State s;s.about=about;s.extraction=extraction;WNDCLASSW c{};c.lpfnWndProc=Proc;c.hInstance=GetModuleHandleW(nullptr);c.lpszClassName=L"NoonBuildGui";c.hCursor=LoadCursor(nullptr,IDC_ARROW);c.hbrBackground=(HBRUSH)(COLOR_BTNFACE+1);c.hIcon=LoadIconW(c.hInstance,MAKEINTRESOURCEW(128));RegisterClassW(&c);
 RECT work{};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);int height=about?480:(std::min)(extraction?766:806,(int)(work.bottom-work.top)-16);
 HWND owner=GetActiveWindow();HWND w=CreateWindowExW(WS_EX_DLGMODALFRAME,c.lpszClassName,about?L"About App / حول التطبيق":extraction?L"Extract / Index / استخراج وفهرسة":L"Create EXE / إنشاء التطبيق",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX|(about?0:WS_VSCROLL),CW_USEDEFAULT,CW_USEDEFAULT,about?530:910,height,owner,nullptr,c.hInstance,&s);
 if(w){if(owner)EnableWindow(owner,FALSE);ShowWindow(w,SW_SHOW);MSG msg;while(IsWindow(w) && GetMessageW(&msg,nullptr,0,0)>0){if(!IsDialogMessageW(w,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}if(owner){EnableWindow(owner,TRUE);SetActiveWindow(owner);}}
 if(SUCCEEDED(hr))CoUninitialize();if(s.openBuilder)ShowBuildGui();
}
}
void ShowBuildGui(){
 std::wstring password;HWND owner=GetActiveWindow();
 if(!AskPassword(owner,password))return;
 bool allowed=AuthorizeBuilderGui(password);std::fill(password.begin(),password.end(),L'\0');
 if(!allowed){MessageBoxW(owner,L"Incorrect password / كلمة المرور غير صحيحة",L"Noon",MB_ICONERROR);return;}
 Show(false);LockBuilderGui();
}
void ShowAboutGui(){Show(true);}
