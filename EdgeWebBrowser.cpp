#include "pch.h"
#include "AppRuntimePaths.h"
#include "resource.h"
#include <wrl.h>

using namespace Microsoft::WRL;

#include "EdgeWebBrowser.h"
#include "Messages.h"

#include "WebView2.h"
#include "WebView2EnvironmentOptions.h"

#include <sstream>
#include <iomanip>

#include <shlwapi.h>
#pragma comment(lib,"shlwapi.lib")
#include "shlobj.h"
#include "NoonServer.h"
#include <shellapi.h>

#pragma comment(lib,"Version.lib")

#include <string>
#include <memory>

#define CHECK_FAILURE_STRINGIFY(arg)         #arg
#define CHECK_FAILURE_FILE_LINE(file, line)  ([](HRESULT hr){ CheckFailure(hr, L"Failure at " CHECK_FAILURE_STRINGIFY(file) L"(" CHECK_FAILURE_STRINGIFY(line) L")"); })
#define CHECK_FAILURE                        CHECK_FAILURE_FILE_LINE(__FILE__, __LINE__)
#define CHECK_FAILURE_BOOL(value)            CHECK_FAILURE((value) ? S_OK : E_UNEXPECTED)

struct CWebBrowserImpl
{
   ComPtr<ICoreWebView2Environment>    m_webViewEnvironment;
   ComPtr<ICoreWebView2Environment2>   m_webViewEnvironment2;
   ComPtr<ICoreWebView2>               m_webView;
   ComPtr<ICoreWebView2_2>             m_webView2;
   ComPtr<ICoreWebView2Controller>     m_webController;
   ComPtr<ICoreWebView2Settings>       m_webSettings;
   EventRegistrationToken embeddedToken{};
};

void ShowFailure(HRESULT hr, CString const & message)
{
   CString text;
   text.Format(L"%s (0x%08X)", (LPCTSTR)message, hr);

   ::MessageBox(nullptr, static_cast<LPCTSTR>(text), L"Failure", MB_OK);
}

void CheckFailure(HRESULT hr, CString const & message)
{
   if (FAILED(hr))
   {    
      CString text;
      text.Format(L"%s : 0x%08X", (LPCTSTR)message, hr);

      // TODO: log text
     
      std::exit(hr);
   }
}

/////////////////////////////////////////////////////////////////////////////
// CWebBrowser

IMPLEMENT_DYNCREATE(CWebBrowser, CWnd)

/////////////////////////////////////////////////////////////////////////////
// CWebBrowser properties
BEGIN_MESSAGE_MAP(CWebBrowser, CWnd)
END_MESSAGE_MAP()

CWebBrowser::CWebBrowser():m_pImpl(new CWebBrowserImpl())
{
   m_callbacks[CallbackType::CreationCompleted] = nullptr;
   m_callbacks[CallbackType::NavigationCompleted] = nullptr;
}

CWebBrowser::~CWebBrowser()
{
   SetWindowLongPtr(m_hWnd, GWLP_USERDATA, 0);
   CloseWebView();
   delete m_pImpl;
}

BOOL CWebBrowser::CreateHostWindow(
   LPCTSTR lpszClassName,
   LPCTSTR lpszWindowName,
   DWORD dwStyle,
   const RECT& rect,
   CWnd* pParentWnd,
   UINT nID)
{
   if (lpszClassName == nullptr)
      lpszClassName = GetWindowClass();

   if (!CWnd::Create(lpszClassName, lpszWindowName, dwStyle, rect, pParentWnd, nID))
      return FALSE;

   ::SetWindowLongPtr(m_hWnd, GWLP_USERDATA, (LONG_PTR)this);

   return TRUE;
}

BOOL CWebBrowser::Create(
   LPCTSTR lpszClassName,
   LPCTSTR lpszWindowName,
   DWORD dwStyle,
   const RECT& rect,
   CWnd* pParentWnd, 
   UINT nID,
   CCreateContext*)
{
   if (!CreateHostWindow(lpszClassName, lpszWindowName, dwStyle, rect, pParentWnd, nID))
      return FALSE;

   InitializeWebView();

   return TRUE;
}

BOOL CWebBrowser::CreateAsync(
   DWORD dwStyle,
   const RECT& rect,
   CWnd* pParentWnd, 
   UINT nID,
   CallbackFunc onCreated)
{
   if (!CreateHostWindow(nullptr, nullptr, dwStyle, rect, pParentWnd, nID))
      return FALSE;

   m_callbacks[CallbackType::CreationCompleted] = onCreated;

   InitializeWebView();

   return TRUE;
}

void CWebBrowser::RegisterCallback(CallbackType const type, CallbackFunc callback)
{
   m_callbacks[type] = callback;
}

void CWebBrowser::CloseWebView()
{
   if (m_pImpl->m_webView)
   {
      m_pImpl->m_webView->remove_WebResourceRequested(m_pImpl->embeddedToken);
      m_pImpl->m_webView->remove_NavigationCompleted(m_navigationCompletedToken);
      m_pImpl->m_webView->remove_NavigationStarting(m_navigationStartingToken);
      m_pImpl->m_webView->remove_DocumentTitleChanged(m_documentTitleChangedToken);
      m_pImpl->m_webView->remove_WebMessageReceived(m_webMessageToken);

      m_pImpl->m_webController->Close();

      m_pImpl->m_webController = nullptr;
      m_pImpl->m_webView = nullptr;
      m_pImpl->m_webView2 = nullptr;
      m_pImpl->m_webSettings = nullptr;
   }

   m_pImpl->m_webViewEnvironment2 = nullptr;
   m_pImpl->m_webViewEnvironment = nullptr;
}

void CWebBrowser::CreationFailure(HRESULT result)
{
   if (m_creationFailed) return;
   m_creationFailed = true;
   auto callback = m_callbacks[CallbackType::CreationFailed];
   if (callback) RunAsync(callback);
   else ShowFailure(result, L"Cannot start WebView2.");
}
void CWebBrowser::InitializeWebView()
{
   CloseWebView();
   m_creationFailed = false;
   auto options = Microsoft::WRL::Make<CoreWebView2EnvironmentOptions>();
   if (!options) { CreationFailure(E_OUTOFMEMORY); return; }
   HRESULT hr = options->put_AdditionalBrowserArguments(L"--host-resolver-rules=\"MAP noon.invalid ^NOTFOUND\"");
   if (FAILED(hr)) { CreationFailure(hr); return; }
   hr = CreateCoreWebView2EnvironmentWithOptions(nullptr, GetUserDataFolder(), options.Get(),
      Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(this,
         &CWebBrowser::OnCreateEnvironmentCompleted).Get());
   if (FAILED(hr)) CreationFailure(hr);
}
HRESULT CWebBrowser::OnCreateEnvironmentCompleted(HRESULT result, ICoreWebView2Environment* environment)
{
   if (FAILED(result) || !environment) { CreationFailure(FAILED(result) ? result : E_FAIL); return S_OK; }
   HRESULT hr = environment->QueryInterface(IID_PPV_ARGS(&m_pImpl->m_webViewEnvironment));
   if (FAILED(hr)) { CreationFailure(hr); return S_OK; }
   hr = environment->QueryInterface(IID_PPV_ARGS(&m_pImpl->m_webViewEnvironment2));
   if (FAILED(hr)) { CreationFailure(hr); return S_OK; }
   hr = environment->CreateCoreWebView2Controller(m_hWnd,
      Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(this,
         &CWebBrowser::OnCreateWebViewControllerCompleted).Get());
   if (FAILED(hr)) CreationFailure(hr);
   return S_OK;
}
HRESULT CWebBrowser::OnCreateWebViewControllerCompleted(HRESULT result, ICoreWebView2Controller* controller)
{
   if (FAILED(result) || !controller) { CreationFailure(FAILED(result) ? result : E_FAIL); return S_OK; }
   m_pImpl->m_webController = controller;
   HRESULT hr = controller->get_CoreWebView2(&m_pImpl->m_webView);
   if (FAILED(hr) || !m_pImpl->m_webView) { CreationFailure(FAILED(hr) ? hr : E_FAIL); return S_OK; }
   hr = m_pImpl->m_webView->QueryInterface(IID_PPV_ARGS(&m_pImpl->m_webView2));
   if (FAILED(hr)) { CreationFailure(hr); return S_OK; }
   hr = m_pImpl->m_webView->get_Settings(&m_pImpl->m_webSettings);
   if (FAILED(hr)) { CreationFailure(hr); return S_OK; }
   RegisterEventHandlers();
   ResizeToClientArea();
   auto callback = m_callbacks[CallbackType::CreationCompleted];
   if (callback) RunAsync(callback);
   return S_OK;
}

void CWebBrowser::RegisterEventHandlers()
{
   // Settings buttons dispatch the same commands as the native context menu.
   // Accept only exact cleanup messages from this process's loopback server.
   CHECK_FAILURE(m_pImpl->m_webSettings->put_IsWebMessageEnabled(TRUE));
   CHECK_FAILURE(m_pImpl->m_webView->add_WebMessageReceived(
      Callback<ICoreWebView2WebMessageReceivedEventHandler>(
         [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
            LPWSTR source = nullptr, message = nullptr;
            HRESULT hr = args->get_Source(&source);
            std::wstring origin = NoonServerUrl();
            bool trusted = SUCCEEDED(hr) && source && !origin.empty() &&
               wcsncmp(source, origin.c_str(), origin.size()) == 0;
            CoTaskMemFree(source);
            if (!trusted) return S_OK;
            hr = args->TryGetWebMessageAsString(&message);
            UINT command = 0;
            if (SUCCEEDED(hr) && message) {
               if (wcscmp(message, L"noon:clear-cache") == 0) command = ID_APP_CLEAR_CACHE;
               else if (wcscmp(message, L"noon:clear-all") == 0) command = ID_APP_CLEAR_ALL_DATA;
            }
            CoTaskMemFree(message);
            if (command && AfxGetMainWnd()) AfxGetMainWnd()->PostMessage(WM_COMMAND, command);
            return S_OK;
         }).Get(), &m_webMessageToken));

   // Extend the native menu without removing its existing commands.
   ComPtr<ICoreWebView2_11> menuView;ComPtr<ICoreWebView2Environment9> menuEnvironment;
   if(SUCCEEDED(m_pImpl->m_webView.As(&menuView)) && SUCCEEDED(m_pImpl->m_webViewEnvironment.As(&menuEnvironment))){
      EventRegistrationToken token{};
      menuView->add_ContextMenuRequested(Callback<ICoreWebView2ContextMenuRequestedEventHandler>(
         [menuEnvironment](ICoreWebView2*,ICoreWebView2ContextMenuRequestedEventArgs* args)->HRESULT{
            ComPtr<ICoreWebView2ContextMenuItemCollection> items;ComPtr<ICoreWebView2ContextMenuTarget> target;
            if(FAILED(args->get_MenuItems(&items)) || FAILED(args->get_ContextMenuTarget(&target)))return S_OK;
            auto add=[&](const wchar_t* label,std::function<void()> action){
               ComPtr<ICoreWebView2ContextMenuItem> item;
               if(FAILED(menuEnvironment->CreateContextMenuItem(label,nullptr,COREWEBVIEW2_CONTEXT_MENU_ITEM_KIND_COMMAND,&item)))return;
               EventRegistrationToken selected{};
               item->add_CustomItemSelected(Callback<ICoreWebView2CustomItemSelectedEventHandler>(
                  [action](ICoreWebView2ContextMenuItem*,IUnknown*)->HRESULT{action();return S_OK;}).Get(),&selected);
               UINT32 count=0;items->get_Count(&count);items->InsertValueAtIndex(count,item.Get());
            };
            BOOL hasLink=FALSE;target->get_HasLinkUri(&hasLink);
            if(hasLink){LPWSTR raw=nullptr;if(SUCCEEDED(target->get_LinkUri(&raw)) && raw){
               std::wstring link(raw);CoTaskMemFree(raw);
               if(link.rfind(L"http://",0)==0 || link.rfind(L"https://",0)==0 || link.rfind(L"mailto:",0)==0)
                  add(L"Open link in default browser / فتح الرابط في المتصفح الافتراضي",[link](){ShellExecuteW(nullptr,L"open",link.c_str(),nullptr,nullptr,SW_SHOWNORMAL);});
            }}
            add(L"Clear cache and browsing history / مسح التخزين المؤقت وسجل التصفح",[](){if(AfxGetMainWnd())AfxGetMainWnd()->PostMessage(WM_COMMAND,ID_APP_CLEAR_CACHE);});
            add(L"Clear all data / مسح جميع البيانات",[](){if(AfxGetMainWnd())AfxGetMainWnd()->PostMessage(WM_COMMAND,ID_APP_CLEAR_ALL_DATA);});
            add(L"About App / حول التطبيق",[](){if(AfxGetMainWnd())AfxGetMainWnd()->PostMessage(WM_COMMAND,ID_APP_ABOUT);});
            return S_OK;
         }).Get(),&token);
   }

   // NavigationCompleted handler
   CHECK_FAILURE(m_pImpl->m_webView->add_NavigationCompleted(
      Callback<ICoreWebView2NavigationCompletedEventHandler>(
         [this](
            ICoreWebView2*, 
            ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT
         {
            m_isNavigating = false;

            BOOL success;
            CHECK_FAILURE(args->get_IsSuccess(&success));

            if (!success)
            {
               COREWEBVIEW2_WEB_ERROR_STATUS webErrorStatus{};
               CHECK_FAILURE(args->get_WebErrorStatus(&webErrorStatus));
               if (webErrorStatus == COREWEBVIEW2_WEB_ERROR_STATUS_DISCONNECTED)
               {
                  // Do something here if you want to handle a specific error case.
                  // In most cases this isn't necessary, because the WebView will
                  // display its own error page automatically.
               }
            }

            LPWSTR uri = nullptr;
            m_pImpl->m_webView->get_Source(&uri);

            if (uri != nullptr && wcscmp(uri, L"about:blank") == 0)
            {
               CoTaskMemFree(uri);
               uri = nullptr;
            }

            auto callback = m_callbacks[CallbackType::NavigationCompleted];
            if (callback != nullptr)
               RunAsync(callback);

            return S_OK;
         })
      .Get(),
            &m_navigationCompletedToken));

   // NavigationStarting handler
   CHECK_FAILURE(m_pImpl->m_webView->add_NavigationStarting(
      Callback<ICoreWebView2NavigationStartingEventHandler>(
         [this](
            ICoreWebView2*,
            ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT
         {
            LPWSTR uri = nullptr;
            CHECK_FAILURE(args->get_Uri(&uri));
            if (uri != nullptr)
               CoTaskMemFree(uri);

            m_isNavigating = true;
            
            return S_OK;
         }).Get(), &m_navigationStartingToken));

   // DocumentTitleChanged handler
   CHECK_FAILURE(m_pImpl->m_webView->add_DocumentTitleChanged(
      Callback<ICoreWebView2DocumentTitleChangedEventHandler>(
         [this](ICoreWebView2* sender, IUnknown* args) -> HRESULT {
            LPWSTR title = nullptr;
            CHECK_FAILURE(sender->get_DocumentTitle(&title));

            m_strTitle = (title != nullptr) ? title : L"";
            if (title != nullptr)
               CoTaskMemFree(title);
            
            auto callback = m_callbacks[CallbackType::TitleChanged];
            if (callback != nullptr)
               RunAsync(callback);

            return S_OK;
         })
      .Get(), &m_documentTitleChangedToken));
}

void CWebBrowser::ResizeToClientArea()
{
   if (m_pImpl->m_webController)
   {
      RECT bounds;
      GetClientRect(&bounds);
      m_pImpl->m_webController->put_Bounds(bounds);
   }
}

RECT CWebBrowser::GetBounds()
{
   RECT rc{0,0,0,0};
   if (m_pImpl->m_webController)
   {
      m_pImpl->m_webController->get_Bounds(&rc);
   }

   return rc;
}

void CWebBrowser::Resize(LONG const width, LONG const height)
{
   SetWindowPos(nullptr, 0, 0, width, height, SWP_NOMOVE| SWP_NOREPOSITION);
}

CString CWebBrowser::GetLocationURL()
{
   CString url;
   if (m_pImpl->m_webView)
   {
      LPWSTR uri = nullptr;
      m_pImpl->m_webView->get_Source(&uri);

      if (uri != nullptr && wcscmp(uri, L"about:blank") != 0)
         url = uri;
      else
         url = L"";

      if (uri != nullptr)
         CoTaskMemFree(uri);
   }

   return url;
}

CString CWebBrowser::NormalizeUrl(CString url)
{
   if (url.Find(_T("://")) < 0)
   {
      if (url.GetLength() > 1 && url[1] == ':')
         url = _T("file://") + url;
      else
         url = _T("http://") + url;
   }

   return url;
}

void CWebBrowser::NavigateTo(CString url)
{
   m_pImpl->m_webView->Navigate(NormalizeUrl(url));
}

void CWebBrowser::Navigate(CString const & url, CallbackFunc onComplete)
{
   if (m_pImpl->m_webView)
   {      
      m_callbacks[CallbackType::NavigationCompleted] = onComplete;
      NavigateTo(url);
   }
}

// The raw request header string delimited by CRLF(optional in last header).
void CWebBrowser::NavigatePost(CString const& url, CString const& content, CString const& headers, std::function<void()> onComplete)
{
   if (!m_pImpl->m_webView) return;

   CString normalizedUrl{ NormalizeUrl(url) };

   m_callbacks[CallbackType::NavigationCompleted] = onComplete;

   ComPtr<ICoreWebView2WebResourceRequest> webResourceRequest;
   ComPtr<IStream> postDataStream = SHCreateMemStream(
      reinterpret_cast<const BYTE*>(static_cast<LPCTSTR>(content)),
      content.GetLength() + 1);

   CHECK_FAILURE(m_pImpl->m_webViewEnvironment2->CreateWebResourceRequest(
      CT2W(normalizedUrl),
      L"POST",
      postDataStream.Get(),
      CT2W(headers),
      webResourceRequest.GetAddressOf()));

   CHECK_FAILURE(m_pImpl->m_webView2->NavigateWithWebResourceRequest(webResourceRequest.Get()));
}
void CWebBrowser::PrintDocument()
{
   if (m_pImpl->m_webView)
   {
      m_pImpl->m_webView->ExecuteScript(L"window.print();", nullptr);
   }
}

void CWebBrowser::Stop()
{
   if (m_pImpl->m_webView)
   {
      m_pImpl->m_webView->Stop();
   }
}

void CWebBrowser::Reload()
{
   if (m_pImpl->m_webView)
   {
      m_pImpl->m_webView->Reload();
   }
}

void CWebBrowser::GoBack()
{
   if (m_pImpl->m_webView)
   {
      BOOL possible = FALSE;
      m_pImpl->m_webView->get_CanGoBack(&possible);
      if(possible)
         m_pImpl->m_webView->GoBack();
   }
}

void CWebBrowser::GoForward()
{
   if (m_pImpl->m_webView)
   {
      BOOL possible = FALSE;
      m_pImpl->m_webView->get_CanGoForward(&possible);
      if (possible)
         m_pImpl->m_webView->GoForward();
   }
}

void CWebBrowser::DisablePopups()
{
   if (m_pImpl->m_webSettings)
   {
      m_pImpl->m_webSettings->put_AreDefaultScriptDialogsEnabled(FALSE);
   }
}

PCTSTR CWebBrowser::GetWindowClass()
{
   static PCTSTR windowClass = []
   {
      static TCHAR const * className = L"EdgeBrowserHost";

      WNDCLASSEX wcex;
      wcex.cbSize = sizeof(WNDCLASSEX);

      wcex.style = CS_HREDRAW | CS_VREDRAW;
      wcex.lpfnWndProc = WndProcStatic;
      wcex.cbClsExtra = 0;
      wcex.cbWndExtra = 0;
      wcex.hInstance = AfxGetInstanceHandle();
      wcex.hIcon = nullptr;
      wcex.hCursor = LoadCursor(nullptr, IDC_ARROW);
      wcex.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
      wcex.lpszMenuName = nullptr;
      wcex.lpszClassName = className;
      wcex.hIconSm = nullptr;

      ATOM result = RegisterClassEx(&wcex);
      if (result == 0)
      {
         DWORD lastError = ::GetLastError();
      }

      return className;
   }();

   return windowClass;
}

LRESULT CALLBACK CWebBrowser::WndProcStatic(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam)
{
   if (auto app = (CWebBrowser*)::GetWindowLongPtr(hWnd, GWLP_USERDATA))
   {
      LRESULT result = 0;
      if (app->HandleWindowMessage(hWnd, message, wParam, lParam, &result))
      {
         return result;
      }
   }

   return ::DefWindowProc(hWnd, message, wParam, lParam);
}

bool CWebBrowser::HandleWindowMessage(
   HWND, UINT message, WPARAM wParam, LPARAM lParam, LRESULT* result)
{
   *result = 0;
   
   switch (message)
   {
   case WM_SIZE:
   {
      if (lParam != 0)
      {
         ResizeToClientArea();
         return true;
      }
   }
   break;
   case MSG_RUN_ASYNC_CALLBACK:
   {
      auto* task = reinterpret_cast<CallbackFunc*>(wParam);
      (*task)();
      delete task;
      return true;
   }
   break;
   }

   return false;
}

void CWebBrowser::RunAsync(CallbackFunc callback)
{
   auto* task = new CallbackFunc(callback);
   PostMessage(MSG_RUN_ASYNC_CALLBACK, reinterpret_cast<WPARAM>(task), 0);
}

bool CWebBrowser::IsWebViewCreated() const
{
   return m_pImpl->m_webView != nullptr;
}

CString CWebBrowser::GetUserDataFolder()
{
   auto path=AppRuntimeFolder(L"cache")/L"WebView2";
   return CString{ path.c_str() };
}

void CWebBrowser::GetSelectedText(TextSelectionFunc callback)
{
   if (m_pImpl->m_webView != nullptr)
   {
      m_pImpl->m_webView->ExecuteScript(L"window.getSelection().toString()",
         Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
            [callback](HRESULT error, PCWSTR result) -> HRESULT
            {
               if (error != S_OK) {
                  ShowFailure(error, L"ExecuteScript failed");
               }

               CW2T text(result);
               callback(CString(text.m_psz));

               return S_OK;
            }).Get());
   }
}

void CWebBrowser::ExecuteScript(CString const& code)
{
   if (m_pImpl->m_webView != nullptr)
   {
      m_pImpl->m_webView->ExecuteScript(CT2W(code).m_psz,
         Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
            [](HRESULT error, PCWSTR result) -> HRESULT
            {
               if (error != S_OK) {
                  ShowFailure(error, L"ExecuteScript failed");
               }

               return S_OK;
            }).Get());
   }
}
// Use the profile API; do not delete an active WebView2 data folder.
void CWebBrowser::ClearAppCache()
{
   if (!m_pImpl->m_webView) return;
   ComPtr<ICoreWebView2_13> webView13;
   ComPtr<ICoreWebView2Profile> profile;
   ComPtr<ICoreWebView2Profile2> profile2;
   HRESULT hr = m_pImpl->m_webView.As(&webView13);
   if (SUCCEEDED(hr)) hr = webView13->get_Profile(&profile);
   if (SUCCEEDED(hr)) hr = profile.As(&profile2);
   if (FAILED(hr)) {
      ShowFailure(hr, L"Cache cleanup is unavailable. Update Microsoft Edge WebView2 Runtime.");
      return;
   }
   // Cache and browsing history; retain settings, cookies and IndexedDB.
   auto kinds = static_cast<COREWEBVIEW2_BROWSING_DATA_KINDS>(
       COREWEBVIEW2_BROWSING_DATA_KINDS_DISK_CACHE |
       COREWEBVIEW2_BROWSING_DATA_KINDS_CACHE_STORAGE |
       COREWEBVIEW2_BROWSING_DATA_KINDS_BROWSING_HISTORY);
   hr = profile2->ClearBrowsingData(kinds,
       Callback<ICoreWebView2ClearBrowsingDataCompletedHandler>(
           [](HRESULT result) -> HRESULT {
               if (FAILED(result)) {
                   ShowFailure(result, L"Could not clear the app cache.");
               } else {
                   ::MessageBoxW(nullptr,
                       L"Cache and browsing history cleared. Saved settings and bookmarks were kept.\n"
                       L"WebView2 may create new cache files as you continue using the app.",
                       L"Clear app cache", MB_OK | MB_ICONINFORMATION);
               }
               return S_OK;
           }).Get());
   if (FAILED(hr)) ShowFailure(hr, L"Could not start app cache cleanup.");
}

void CWebBrowser::ClearAllAppData()
{
   if (!m_pImpl->m_webView) return;
   ComPtr<ICoreWebView2_13> webView13;
   ComPtr<ICoreWebView2Profile> profile;
   ComPtr<ICoreWebView2Profile2> profile2;
   HRESULT hr = m_pImpl->m_webView.As(&webView13);
   if (SUCCEEDED(hr)) hr = webView13->get_Profile(&profile);
   if (SUCCEEDED(hr)) hr = profile.As(&profile2);
   if (FAILED(hr)) {
      ShowFailure(hr, L"Data cleanup is unavailable. Update Microsoft Edge WebView2 Runtime.");
      return;
   }
   // Unload all app frames before clearing, so active scripts cannot restore settings.
   ComPtr<ICoreWebView2> webView = m_pImpl->m_webView;
   auto token = std::make_shared<EventRegistrationToken>();
   hr = webView->add_NavigationCompleted(
       Callback<ICoreWebView2NavigationCompletedEventHandler>(
           [webView, profile2, token](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs*) -> HRESULT {
               LPWSTR source = nullptr;
               HRESULT result = webView->get_Source(&source);
               bool blank = SUCCEEDED(result) && source && wcscmp(source, L"about:blank") == 0;
               CoTaskMemFree(source);
               if (!blank) return S_OK;
               webView->remove_NavigationCompleted(*token);
               result = profile2->ClearBrowsingDataAll(
                   Callback<ICoreWebView2ClearBrowsingDataCompletedHandler>(
                       [webView](HRESULT cleared) -> HRESULT {
                           if (FAILED(cleared)) ShowFailure(cleared, L"Could not clear all app data.");
                           else ::MessageBoxW(nullptr, L"All app browser data cleared. The app will now reload with default settings.",
                               L"Clear all app data", MB_OK | MB_ICONINFORMATION);
                           webView->Navigate((NoonServerUrl()+L"__startup/").c_str());
                           return S_OK;
                       }).Get());
               if (FAILED(result)) {
                   ShowFailure(result, L"Could not start app data cleanup.");
                   webView->Navigate((NoonServerUrl()+L"__startup/").c_str());
               }
               return S_OK;
           }).Get(), token.get());
   if (FAILED(hr)) { ShowFailure(hr, L"Could not prepare app data cleanup."); return; }
   hr = webView->Navigate(L"about:blank");
   if (FAILED(hr)) {
       webView->remove_NavigationCompleted(*token);
       ShowFailure(hr, L"Could not unload the app before cleanup.");
   }
}
