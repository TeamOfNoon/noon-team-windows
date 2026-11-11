
// MfcEdgeDemo.h : main header file for the MfcEdgeDemo application
//
#pragma once

#ifndef __AFXWIN_H__
	#error "include 'pch.h' before including this file for PCH"
#endif

#include "resource.h"       // main symbols


// CMfcEdgeDemoApp:
// See MfcEdgeDemo.cpp for the implementation of this class
//

class CMfcEdgeDemoApp : public CWinApp
{
public:
	CMfcEdgeDemoApp() noexcept;


// Overrides
public:
	virtual BOOL InitInstance() override;
	virtual BOOL ExitInstance() override;

// Implementation
	afx_msg void OnAppAbout();
	DECLARE_MESSAGE_MAP()
};

extern CMfcEdgeDemoApp theApp;

