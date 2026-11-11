
// MainFrm.cpp : implementation of the CMainFrame class
//

#include "pch.h"
#include "framework.h"
#include "MfcEdgeDemo.h"
#include "MfcEdgeDemoView.h"

#include "MainFrm.h"
#include "BrowserLaunch.h"
#include <string>

static void ApplyExternalLogoIcon(CWnd* pWnd)
{
   if (pWnd == NULL || pWnd->GetSafeHwnd() == NULL)
      return;

   wchar_t exePath[MAX_PATH] = { 0 };
   if (GetModuleFileNameW(NULL, exePath, MAX_PATH) == 0)
      return;

   std::wstring logoPath(exePath);
   size_t slash = logoPath.find_last_of(L"\\/");
   if (slash != std::wstring::npos)
      logoPath.resize(slash + 1);
   else
      logoPath.clear();

   logoPath += L"logo.ico";

   DWORD attr = GetFileAttributesW(logoPath.c_str());
   if (attr == INVALID_FILE_ATTRIBUTES || (attr & FILE_ATTRIBUTE_DIRECTORY))
      return;

   HICON hBig = (HICON)LoadImageW(
      NULL, logoPath.c_str(), IMAGE_ICON,
      GetSystemMetrics(SM_CXICON), GetSystemMetrics(SM_CYICON),
      LR_LOADFROMFILE);

   HICON hSmall = (HICON)LoadImageW(
      NULL, logoPath.c_str(), IMAGE_ICON,
      GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON),
      LR_LOADFROMFILE);

   if (hBig != NULL)
      pWnd->SendMessage(WM_SETICON, ICON_BIG, (LPARAM)hBig);

   if (hSmall != NULL)
      pWnd->SendMessage(WM_SETICON, ICON_SMALL, (LPARAM)hSmall);
}

#include "Messages.h"

#ifdef _DEBUG
#define new DEBUG_NEW
#endif

// CMainFrame

IMPLEMENT_DYNCREATE(CMainFrame, CFrameWnd)

BEGIN_MESSAGE_MAP(CMainFrame, CFrameWnd)
	ON_WM_CREATE()
	ON_BN_CLICKED(IDC_BUTTON_CHANGE_BACKGROUND_COLOR, &CMainFrame::OnBnClickedButtonChangeBackgroundColor)
	ON_BN_CLICKED(IDC_BUTTON_COPY_SELECTION, &CMainFrame::OnBnClickedButtonCopySelection)
	ON_BN_CLICKED(IDC_BUTTON_BACK, &CMainFrame::OnBnClickedButtonBack)
	ON_BN_CLICKED(IDC_BUTTON_FORWARD, &CMainFrame::OnBnClickedButtonForward)
	ON_BN_CLICKED(IDC_BUTTON_RELOAD, &CMainFrame::OnBnClickedButtonReload)
	ON_BN_CLICKED(IDC_BUTTON_SERVE, &CMainFrame::OnBnClickedButtonServe)
	ON_MESSAGE(MSG_NAVIGATE, &CMainFrame::OnNavigateToUrl)
	ON_COMMAND(IDC_BUTTON_RELOAD, &CMainFrame::OnButtonReload)
	ON_UPDATE_COMMAND_UI(IDC_BUTTON_RELOAD, &CMainFrame::OnUpdateButtonReload)
END_MESSAGE_MAP()

static UINT indicators[] =
{
	ID_SEPARATOR,           // status line indicator
	ID_INDICATOR_CAPS,
	ID_INDICATOR_NUM,
	ID_INDICATOR_SCRL,
};

// CMainFrame construction/destruction

CMainFrame::CMainFrame() noexcept
{
	// TODO: add member initialization code here
}

CMainFrame::~CMainFrame()
{
}

int CMainFrame::OnCreate(LPCREATESTRUCT lpCreateStruct)
{
   ApplyExternalLogoIcon(this);

	if (CFrameWnd::OnCreate(lpCreateStruct) == -1)
		return -1;

	if (!m_wndDlgBar.Create(this, IDR_MAINFRAME, CBRS_ALIGN_TOP, AFX_IDW_DIALOGBAR))
	{
		TRACE0("Failed to create dialogbar\n");
		return -1;		// fail to create
	}

	if (!m_wndReBar.Create(this) || !m_wndReBar.AddBar(&m_wndDlgBar))
	{
		TRACE0("Failed to create rebar\n");
		return -1;      // fail to create
	}

	if (!m_wndStatusBar.Create(this))
	{
		TRACE0("Failed to create status bar\n");
		return -1;      // fail to create
	}
	m_wndStatusBar.SetIndicators(indicators, sizeof(indicators)/sizeof(UINT));
	m_wndStatusBar.ShowWindow(SW_HIDE);

 ShowControlBar(&m_wndReBar,ShowNavigationBar(),FALSE);
 ShowControlBar(&m_wndDlgBar,ShowNavigationBar(),FALSE);
 if(!ShowMenuBar()){::SetMenu(GetSafeHwnd(),nullptr);DrawMenuBar();}
 RecalcLayout();
	// TODO: Remove this if you don't want tool tips

	return 0;
}

BOOL CMainFrame::PreCreateWindow(CREATESTRUCT& cs)
{
	if( !CFrameWnd::PreCreateWindow(cs) )
		return FALSE;
	// TODO: Modify the Window class or styles here by modifying
	//  the CREATESTRUCT cs

	return TRUE;
}

// CMainFrame diagnostics

#ifdef _DEBUG
void CMainFrame::AssertValid() const
{
	CFrameWnd::AssertValid();
}

void CMainFrame::Dump(CDumpContext& dc) const
{
	CFrameWnd::Dump(dc);
}
#endif //_DEBUG


// CMainFrame message handlers

void CMainFrame::OnBnClickedButtonBack()
{
	auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
	if (view != nullptr)
		view->NavigateBack();
}

void CMainFrame::OnBnClickedButtonForward()
{
   auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
   if (view != nullptr)
      view->NavigateForward();
}


void CMainFrame::OnBnClickedButtonReload()
{
   auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
   if (view != nullptr)
      view->ReloadPage();
}

void CMainFrame::OnBnClickedButtonServe()
{
   CString url;
   m_wndDlgBar.GetDlgItemText(IDC_EDIT_URL, url);
   url.Trim();

   auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
   if (view != nullptr && !url.IsEmpty())
      view->ServeUrl(url);
}

LRESULT CMainFrame::OnNavigateToUrl(WPARAM wParam, LPARAM lParam)
{
	auto url = reinterpret_cast<LPCTSTR>(lParam);
   auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
   if (view != nullptr)
		view->NavigateTo(url, [this, view]() {
			m_wndDlgBar.SetDlgItemText(IDC_EDIT_URL, view->GetCurrentUrl());
		});

	return 0;
}

void CMainFrame::OnButtonReload()
{
	auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
	if (view->IsNavigating())
		view->StopNavigation();
	else
		view->ReloadPage();
}

void CMainFrame::OnUpdateButtonReload(CCmdUI* pCmdUI)
{
   auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
	pCmdUI->SetText(view != nullptr && view->IsNavigating() ? L"X" : L"R");
}

void CMainFrame::OnBnClickedButtonCopySelection()
{
	auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
	if (view != nullptr)
	{
		view->GetSelection([](CString const& text) {
			AfxMessageBox(L"Selected text: " + text, MB_OK);
			});		
	}
}

void CMainFrame::OnBnClickedButtonChangeBackgroundColor()
{
	auto view = dynamic_cast<CMfcEdgeDemoView*>(GetActiveView());
	if (view != nullptr)
	{
		view->ExecuteScript(L"document.body.style.backgroundColor = \"red\"");
	}
}