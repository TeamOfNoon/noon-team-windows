
// MfcEdgeDemoView.cpp : implementation of the CMfcEdgeDemoView class
//

#include "pch.h"
#include "framework.h"
// SHARED_HANDLERS can be defined in an ATL project implementing preview, thumbnail
// and search filter handlers and allows sharing of document code with that project.
#ifndef SHARED_HANDLERS
#include "MfcEdgeDemo.h"
#endif

#include "MfcEdgeDemoDoc.h"
#include "MfcEdgeDemoView.h"
#include <string>
#include "NoonServer.h"
#include "BrowserLaunch.h"

#ifdef _DEBUG
#define new DEBUG_NEW
#endif


// CMfcEdgeDemoView

IMPLEMENT_DYNCREATE(CMfcEdgeDemoView, CView)

BEGIN_MESSAGE_MAP(CMfcEdgeDemoView, CView)
   ON_WM_SIZE()
   ON_COMMAND(ID_APP_CLEAR_ALL_DATA, &CMfcEdgeDemoView::OnClearAllAppData)
   ON_UPDATE_COMMAND_UI(ID_APP_CLEAR_ALL_DATA, &CMfcEdgeDemoView::OnUpdateClearAppCache)
   ON_COMMAND(ID_APP_CLEAR_CACHE, &CMfcEdgeDemoView::OnClearAppCache)
   ON_UPDATE_COMMAND_UI(ID_APP_CLEAR_CACHE, &CMfcEdgeDemoView::OnUpdateClearAppCache)
END_MESSAGE_MAP()

// CMfcEdgeDemoView construction/destruction

CMfcEdgeDemoView::CMfcEdgeDemoView() noexcept
{
	// TODO: add construction code here

}

CMfcEdgeDemoView::~CMfcEdgeDemoView()
{
}

BOOL CMfcEdgeDemoView::PreCreateWindow(CREATESTRUCT& cs)
{
	// TODO: Modify the Window class or styles here by modifying
	//  the CREATESTRUCT cs

	return CView::PreCreateWindow(cs);
}

// CMfcEdgeDemoView drawing

void CMfcEdgeDemoView::OnDraw(CDC* /*pDC*/)
{
	CMfcEdgeDemoDoc* pDoc = GetDocument();
	ASSERT_VALID(pDoc);
	if (!pDoc)
		return;

	// TODO: add draw code for native data here
}


// CMfcEdgeDemoView diagnostics

#ifdef _DEBUG
void CMfcEdgeDemoView::AssertValid() const
{
	CView::AssertValid();
}

void CMfcEdgeDemoView::Dump(CDumpContext& dc) const
{
	CView::Dump(dc);
}

CMfcEdgeDemoDoc* CMfcEdgeDemoView::GetDocument() const // non-debug version is inline
{
	ASSERT(m_pDocument->IsKindOf(RUNTIME_CLASS(CMfcEdgeDemoDoc)));
	return (CMfcEdgeDemoDoc*)m_pDocument;
}
#endif //_DEBUG


// CMfcEdgeDemoView message handlers


void CMfcEdgeDemoView::OnInitialUpdate()
{
   CView::OnInitialUpdate();

   // Embedded mode: no external server launcher.

   this->ModifyStyleEx(WS_EX_CLIENTEDGE | WS_EX_WINDOWEDGE, 0, 0);
   this->ModifyStyle(WS_CAPTION | WS_SYSMENU | WS_MAXIMIZEBOX | WS_MINIMIZEBOX | WS_THICKFRAME | WS_BORDER, 0, 0);

   m_pWebBrowser = std::make_unique<CWebBrowser>();

   if (m_pWebBrowser != nullptr)
   {
      CRect rectClient;
      GetClientRect(rectClient);

      m_pWebBrowser->RegisterCallback(CWebBrowser::CallbackType::CreationFailed, [this]() {
         if (BrowserIsAuto()) {
            AfxGetMainWnd()->ShowWindow(SW_HIDE);
            if (!LaunchNWBrowser()) AfxMessageBox(BrowserLaunchError().c_str());
         } else AfxMessageBox(L"WebView2 could not start. Install its Runtime or select nwjs/auto.");
         AfxGetMainWnd()->PostMessage(WM_CLOSE);
      });
      m_pWebBrowser->CreateAsync(
         WS_VISIBLE | WS_CHILD,
         rectClient,
         this,
         1,
         [this]() {
            m_pWebBrowser->SetParentView(this);
            m_pWebBrowser->DisablePopups();
            std::wstring startUrl = NoonServerUrl();
            m_pWebBrowser->Navigate(startUrl.c_str(), nullptr);

            m_pWebBrowser->RegisterCallback(CWebBrowser::CallbackType::TitleChanged, [this]() {
               CString title = m_pWebBrowser->GetTitle();

               if (GetDocument() != nullptr)
               {
                  GetDocument()->SetTitle(title);
               }

               AfxGetMainWnd()->SetWindowText(title);
            });
         });
   }
}


BOOL CMfcEdgeDemoView::DestroyWindow()
{
   m_pWebBrowser.reset();

   return CView::DestroyWindow();
}


void CMfcEdgeDemoView::OnSize(UINT nType, int cx, int cy)
{
   CView::OnSize(nType, cx, cy);

   CRect rectClient;
   GetClientRect(rectClient);

   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->Resize(cx, cy);
}

void CMfcEdgeDemoView::NavigateTo(CString const& url, std::function<void()> callback)
{
   if(m_pWebBrowser != nullptr)
      m_pWebBrowser->Navigate(url, callback);
}

void CMfcEdgeDemoView::NavigatePost(CString const& url, CString const& content, CString const& headers, std::function<void()> callback)
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->NavigatePost(url, content, headers, callback);
}

void CMfcEdgeDemoView::NavigateBack()
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->GoBack();
}

void CMfcEdgeDemoView::NavigateForward()
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->GoForward();
}

void CMfcEdgeDemoView::StopNavigation()
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->Stop();
}

void CMfcEdgeDemoView::ReloadPage()
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->Reload();
}

void CMfcEdgeDemoView::ServeUrl(CString const& url)
{
   (void)url;
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->Navigate(NoonServerUrl().c_str(), nullptr);
}

CString CMfcEdgeDemoView::GetCurrentUrl()
{
   return (m_pWebBrowser != nullptr) ? m_pWebBrowser->GetLocationURL() : L"";
}

bool CMfcEdgeDemoView::IsNavigating()
{
   return (m_pWebBrowser != nullptr) ? m_pWebBrowser->IsNavigating() : false;
}

void CMfcEdgeDemoView::GetSelection(std::function<void(CString const &)> callback)
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->GetSelectedText(callback);
}

void CMfcEdgeDemoView::ExecuteScript(CString const& code)
{
   if (m_pWebBrowser != nullptr)
      m_pWebBrowser->ExecuteScript(code);
}
void CMfcEdgeDemoView::OnClearAppCache()
{
   if (m_pWebBrowser) m_pWebBrowser->ClearAppCache();
}
void CMfcEdgeDemoView::OnUpdateClearAppCache(CCmdUI* pCmdUI)
{
   pCmdUI->Enable(m_pWebBrowser && m_pWebBrowser->IsWebViewCreated());
}

void CMfcEdgeDemoView::OnClearAllAppData()
{
   if (!m_pWebBrowser || !m_pWebBrowser->IsWebViewCreated()) return;
   if (MessageBoxW(L"Clear all app browser data?\n\nThis removes saved settings, bookmarks, cookies, history, cache and browser databases. Embedded web files are kept.",
       L"Clear all app data", MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2) == IDYES)
       m_pWebBrowser->ClearAllAppData();
}
