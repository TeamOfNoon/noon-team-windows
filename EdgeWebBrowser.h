#pragma once

#include <EventToken.h>
#include <functional>
#include <map>

struct ICoreWebView2Environment;
struct ICoreWebView2Controller;

struct CWebBrowserImpl;
class CView;

class CWebBrowser : public CWnd
{
public:
   enum class CallbackType 
   {
      CreationCompleted,
      CreationFailed,
      NavigationCompleted,
      TitleChanged,
   };

   using CallbackFunc = std::function<void()>;
   using TextSelectionFunc = std::function<void(CString const &)>;

public:
   CWebBrowser();
   virtual ~CWebBrowser();

   virtual BOOL Create(
      LPCTSTR lpszClassName,
      LPCTSTR lpszWindowName, 
      DWORD dwStyle,
      const RECT& rect,
      CWnd* pParentWnd, 
      UINT nID,
      CCreateContext* = NULL) override;

   BOOL CreateAsync(
      DWORD dwStyle,
      const RECT& rect,
      CWnd* pParentWnd,
      UINT nID,
      CallbackFunc onCreated);

   void RegisterCallback(CallbackType const type, CallbackFunc callback);
   void CreationFailure(HRESULT result);
   bool m_creationFailed = false;

   RECT GetBounds();
   void SetBounds(LONG const width, LONG const height) { Resize(width, height); }
   void Resize(LONG const width, LONG const height);

   CString GetLocationURL();

   void Navigate(CString const & url, CallbackFunc onComplete);
   void NavigatePost(CString const& url, CString const& content, CString const& headers, CallbackFunc onComplete = nullptr);
   void GoBack();
   void GoForward();
   void Reload();
   void ClearAppCache();
   void ClearAllAppData();
   void Stop();
   bool IsNavigating() const { return m_isNavigating; }
   void DisablePopups();

   void PrintDocument();
   CString GetTitle() const { return m_strTitle; }

   void SetParentView(CView* pViewParent) { m_pViewParent = pViewParent; }
   bool IsWebViewCreated() const;

   void GetSelectedText(TextSelectionFunc callback);
   void ExecuteScript(CString const & code);

protected:
   DECLARE_DYNCREATE(CWebBrowser)
   DECLARE_MESSAGE_MAP()

private:
   CWebBrowserImpl* m_pImpl;
   std::map<CallbackType, CallbackFunc> m_callbacks;

   EventRegistrationToken m_navigationCompletedToken = {};
   EventRegistrationToken m_navigationStartingToken = {};
   EventRegistrationToken m_documentTitleChangedToken = {};
   EventRegistrationToken m_webMessageToken = {};

   bool m_isNavigating = false;
   CView* m_pViewParent = nullptr;
   CString m_strTitle;

private:
   void RunAsync(CallbackFunc callback);

   void CloseWebView();
   void RegisterEventHandlers();
   void ResizeToClientArea();
   void NavigateTo(CString url);
   CString NormalizeUrl(CString url);

   static CString GetUserDataFolder();

   void InitializeWebView();
   HRESULT OnCreateEnvironmentCompleted(HRESULT result, ICoreWebView2Environment* environment);
   HRESULT OnCreateWebViewControllerCompleted(HRESULT result, ICoreWebView2Controller* controller);

   static PCTSTR GetWindowClass();
   static LRESULT CALLBACK WndProcStatic(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam);
   bool HandleWindowMessage(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam, LRESULT* result);

   BOOL CreateHostWindow(
      LPCTSTR lpszClassName,
      LPCTSTR lpszWindowName,
      DWORD dwStyle,
      const RECT& rect,
      CWnd* pParentWnd,
      UINT nID);
};