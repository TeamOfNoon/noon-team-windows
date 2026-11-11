#include "pch.h"
#include "MfcEdgeDemo.h"
bool RunNoonUninstall();
BEGIN_MESSAGE_MAP(CMfcEdgeDemoApp,CWinApp)
 ON_COMMAND(ID_APP_ABOUT,&CMfcEdgeDemoApp::OnAppAbout)
END_MESSAGE_MAP()
CMfcEdgeDemoApp theApp;
CMfcEdgeDemoApp::CMfcEdgeDemoApp() noexcept {}
BOOL CMfcEdgeDemoApp::InitInstance(){CWinApp::InitInstance();RunNoonUninstall();return FALSE;}
int CMfcEdgeDemoApp::ExitInstance(){return CWinApp::ExitInstance();}
void CMfcEdgeDemoApp::OnAppAbout(){AfxMessageBox(L"Noon Uninstaller / إزالة نون");}
