#include "pch.h"
#include "PortDialog.h"
#include <vector>
namespace {
struct PortState{int previous,chosen=0;std::wstring reason,config;std::function<int()> suggest;bool accepted=false;};
void Suggest(HWND window,PortState* s){int value=s->suggest();if(value>0)SetDlgItemInt(window,301,value,FALSE);else MessageBoxW(window,L"Cannot find an available port. Enter one manually. / تعذر اقتراح منفذ. أدخله يدوياً.",L"Noon",MB_ICONWARNING);}
LRESULT CALLBACK Proc(HWND w,UINT m,WPARAM wp,LPARAM lp){
 auto s=reinterpret_cast<PortState*>(GetWindowLongPtrW(w,GWLP_USERDATA));
 if(m==WM_NCCREATE){s=reinterpret_cast<PortState*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);SetWindowLongPtrW(w,GWLP_USERDATA,(LONG_PTR)s);}
 if(!s)return DefWindowProcW(w,m,wp,lp);
 if(m==WM_CREATE){
  auto add=[&](const wchar_t* cls,const wchar_t* text,DWORD style,int id,int x,int y,int width,int height){auto c=CreateWindowExW(cls==std::wstring(L"EDIT")?WS_EX_CLIENTEDGE:0,cls,text,WS_CHILD|WS_VISIBLE|style,x,y,width,height,w,(HMENU)(INT_PTR)id,GetModuleHandleW(nullptr),nullptr);SendMessageW(c,WM_SETFONT,(WPARAM)GetStockObject(DEFAULT_GUI_FONT),TRUE);};
  add(L"STATIC",s->reason.c_str(),0,0,20,18,550,65);
  add(L"STATIC",L"Port (1–65535) / المنفذ",0,0,20,91,260,24);
  add(L"EDIT",L"",WS_TABSTOP|ES_NUMBER|ES_AUTOHSCROLL,301,20,119,170,30);SendDlgItemMessageW(w,301,EM_SETLIMITTEXT,5,0);
  add(L"BUTTON",L"Suggest free port / اقتراح منفذ متاح",WS_TABSTOP,302,210,117,350,34);
  auto note=L"The selected port is saved after connecting / يُحفظ المنفذ بعد نجاح الاتصال:\n"+s->config;
  add(L"STATIC",note.c_str(),0,0,20,168,550,60);
  add(L"BUTTON",L"OK / موافق",BS_DEFPUSHBUTTON|WS_TABSTOP,IDOK,280,244,125,34);
  add(L"BUTTON",L"Cancel / إلغاء",WS_TABSTOP,IDCANCEL,425,244,135,34);
  Suggest(w,s);SetFocus(GetDlgItem(w,301));return 0;
 }
 if(m==WM_COMMAND){int id=LOWORD(wp);if(id==302){Suggest(w,s);return 0;}if(id==IDCANCEL){DestroyWindow(w);return 0;}
  if(id==IDOK){BOOL valid=FALSE;UINT port=GetDlgItemInt(w,301,&valid,FALSE);if(!valid||port<1||port>65535){MessageBoxW(w,L"Enter a number from 1 to 65535. / أدخل رقماً من 1 إلى 65535.",L"Noon",MB_ICONWARNING);return 0;}s->chosen=(int)port;s->accepted=true;DestroyWindow(w);return 0;}}
 if(m==WM_CLOSE){DestroyWindow(w);return 0;}return DefWindowProcW(w,m,wp,lp);
}
}
bool ChooseNoonPort(int previous,const std::wstring& reason,const std::wstring& config,const std::function<int()>& suggest,int& chosen){
 WNDCLASSW c{};c.lpfnWndProc=Proc;c.hInstance=GetModuleHandleW(nullptr);c.lpszClassName=L"NoonChoosePort";c.hCursor=::LoadCursorW(nullptr,IDC_ARROW);c.hbrBackground=(HBRUSH)(COLOR_BTNFACE+1);RegisterClassW(&c);
 PortState s{previous,0,reason,config,suggest,false};HWND owner=GetActiveWindow();
 auto w=CreateWindowExW(WS_EX_DLGMODALFRAME,c.lpszClassName,L"Choose local port / اختيار المنفذ المحلي",WS_CAPTION|WS_SYSMENU,CW_USEDEFAULT,CW_USEDEFAULT,610,330,owner,nullptr,c.hInstance,&s);
 if(!w)return false;if(owner)EnableWindow(owner,FALSE);ShowWindow(w,SW_SHOW);SetFocus(GetDlgItem(w,301));
 MSG msg;while(IsWindow(w)&&GetMessageW(&msg,nullptr,0,0)>0){if(!IsDialogMessageW(w,&msg)){TranslateMessage(&msg);DispatchMessageW(&msg);}}
 if(owner){EnableWindow(owner,TRUE);SetActiveWindow(owner);}chosen=s.chosen;return s.accepted;
}
