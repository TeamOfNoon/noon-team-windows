
// MfcEdgeDemo.cpp : Defines the class behaviors for the application.
//

#include "pch.h"
#include "framework.h"
#include "afxwinappex.h"
#include "afxdialogex.h"
#include "MfcEdgeDemo.h"
#include "MainFrm.h"
#include "NoonServer.h"
#include "BrowserLaunch.h"
#include "AppBuilder.h"
#include "BuildGui.h"

#include "MfcEdgeDemoDoc.h"
#include "MfcEdgeDemoView.h"

#ifdef _DEBUG
#define new DEBUG_NEW
#endif


// CMfcEdgeDemoApp

BEGIN_MESSAGE_MAP(CMfcEdgeDemoApp, CWinApp)
	ON_COMMAND(ID_APP_ABOUT, &CMfcEdgeDemoApp::OnAppAbout)
	// Standard file based document commands
	ON_COMMAND(ID_FILE_NEW, &CWinApp::OnFileNew)
	ON_COMMAND(ID_FILE_OPEN, &CWinApp::OnFileOpen)
END_MESSAGE_MAP()


// CMfcEdgeDemoApp construction

CMfcEdgeDemoApp::CMfcEdgeDemoApp() noexcept
{
	// support Restart Manager
	m_dwRestartManagerSupportFlags = AFX_RESTART_MANAGER_SUPPORT_RESTART;
#ifdef _MANAGED
	// If the application is built using Common Language Runtime support (/clr):
	//     1) This additional setting is needed for Restart Manager support to work properly.
	//     2) In your project, you must add a reference to System.Windows.Forms in order to build.
	System::Windows::Forms::Application::SetUnhandledExceptionMode(System::Windows::Forms::UnhandledExceptionMode::ThrowException);
#endif

	// TODO: replace application ID string below with unique ID string; recommended
	// format for string is CompanyName.ProductName.SubProduct.VersionInformation
	SetAppID(_T("NoonTeam.Noon.Desktop"));

	// TODO: add construction code here,
	// Place all significant initialization in InitInstance
}

// The one and only CMfcEdgeDemoApp object

CMfcEdgeDemoApp theApp;

// CMfcEdgeDemoApp initialization

BOOL CMfcEdgeDemoApp::InitInstance()
{
	if (HandleBuilderCommand()) return FALSE;
	if (!AcquireNoonAppInstance()) return FALSE;
	// InitCommonControlsEx() is required on Windows XP if an application
	// manifest specifies use of ComCtl32.dll version 6 or later to enable
	// visual styles.  Otherwise, any window creation will fail.
	INITCOMMONCONTROLSEX InitCtrls;
	InitCtrls.dwSize = sizeof(InitCtrls);
	// Set this to include all the common control classes you want to use
	// in your application.
	InitCtrls.dwICC = ICC_WIN95_CLASSES;
	InitCommonControlsEx(&InitCtrls);

	CWinApp::InitInstance();
	if (!ReadBrowserConfig()) { AfxMessageBox(BrowserLaunchError().c_str()); return FALSE; }
	if (!StartNoonServer()) { if(!NoonServerError().empty()) AfxMessageBox((L"Cannot start the embedded HTTP server.\n\n" + NoonServerError()).c_str()); return FALSE; }

	if (UseNWBrowser()) { if (!LaunchNWBrowser()) AfxMessageBox(BrowserLaunchError().c_str()); return FALSE; }


	EnableTaskbarInteraction(FALSE);

	// AfxInitRichEdit2() is required to use RichEdit control
	// AfxInitRichEdit2();

	// Standard initialization
	// If you are not using these features and wish to reduce the size
	// of your final executable, you should remove from the following
	// the specific initialization routines you do not need
	// Change the registry key under which our settings are stored
	// TODO: You should modify this string to be something appropriate
	// such as the name of your company or organization
	SetRegistryKey(_T("Local AppWizard-Generated Applications"));
	LoadStdProfileSettings(4);  // Load standard INI file options (including MRU)


	// Register the application's document templates.  Document templates
	//  serve as the connection between documents, frame windows and views
	CSingleDocTemplate* pDocTemplate;
	pDocTemplate = new CSingleDocTemplate(
		IDR_MAINFRAME,
		RUNTIME_CLASS(CMfcEdgeDemoDoc),
		RUNTIME_CLASS(CMainFrame),       // main SDI frame window
		RUNTIME_CLASS(CMfcEdgeDemoView));
	if (!pDocTemplate)
		return FALSE;
	AddDocTemplate(pDocTemplate);


	::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

	// Always create the normal SDI document. Do not let MFC interpret our
	// command-line arguments as filenames to open in this embedded viewer.
	CCommandLineInfo cmdInfo;
	cmdInfo.m_nShellCommand = CCommandLineInfo::FileNew;
	if (!ProcessShellCommand(cmdInfo))
		return FALSE;

	// The one and only window has been initialized, so show and update it
	m_pMainWnd->SetWindowText(BuiltOption("name", L"Noon").c_str());
	if(!ShowMenuBar()){::SetMenu(m_pMainWnd->GetSafeHwnd(),nullptr);m_pMainWnd->DrawMenuBar();}
	m_pMainWnd->ShowWindow(SW_SHOW);
	m_pMainWnd->UpdateWindow();
	return TRUE;
}

BOOL CMfcEdgeDemoApp::ExitInstance()
{
	StopNoonServer();
	::CoUninitialize();

	int normalResult = CWinApp::ExitInstance();
	return BuilderExitCode() ? BuilderExitCode() : normalResult;
}

// CMfcEdgeDemoApp message handlers


// CAboutDlg dialog used for App About

class CAboutDlg : public CDialogEx
{
public:
	CAboutDlg() noexcept;

// Dialog Data
#ifdef AFX_DESIGN_TIME
	enum { IDD = IDD_ABOUTBOX };
#endif

protected:
	virtual void DoDataExchange(CDataExchange* pDX);    // DDX/DDV support

// Implementation
protected:
	DECLARE_MESSAGE_MAP()
};

CAboutDlg::CAboutDlg() noexcept : CDialogEx(IDD_ABOUTBOX)
{
}

void CAboutDlg::DoDataExchange(CDataExchange* pDX)
{
	CDialogEx::DoDataExchange(pDX);
}

BEGIN_MESSAGE_MAP(CAboutDlg, CDialogEx)
END_MESSAGE_MAP()

// App command to run the dialog
void CMfcEdgeDemoApp::OnAppAbout()
{
 ShowAboutGui();
}
