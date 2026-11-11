// NavigationBar.cpp : implementation file
//

#include "pch.h"
#include "MfcEdgeDemo.h"
#include "NavigationBar.h"
#include "Messages.h"

// CNavigationBar

IMPLEMENT_DYNAMIC(CNavigationBar, CDialogBar)

CNavigationBar::CNavigationBar()
{

}

CNavigationBar::~CNavigationBar()
{
}


BEGIN_MESSAGE_MAP(CNavigationBar, CDialogBar)
END_MESSAGE_MAP()



// CNavigationBar message handlers




BOOL CNavigationBar::PreTranslateMessage(MSG* pMsg)
{
   if (pMsg->message == WM_KEYDOWN && pMsg->wParam == VK_RETURN)
   {
      CString url;
      GetDlgItemText(IDC_EDIT_URL, url);
      AfxGetMainWnd()->SendMessage(MSG_NAVIGATE, 0, reinterpret_cast<LPARAM>(static_cast<LPCTSTR>(url)));

      SendMessage(WM_COMMAND, (WPARAM)IDC_EDIT_URL, LPARAM(0));
   }

	return CDialogBar::PreTranslateMessage(pMsg);
}
