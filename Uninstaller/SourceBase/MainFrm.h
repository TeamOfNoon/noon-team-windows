
// MainFrm.h : interface of the CMainFrame class
//

#pragma once

#include "NavigationBar.h"

class CMainFrame : public CFrameWnd
{
	
protected: // create from serialization only
	CMainFrame() noexcept;
	DECLARE_DYNCREATE(CMainFrame)

// Attributes
public:

// Operations
public:

// Overrides
public:
	virtual BOOL PreCreateWindow(CREATESTRUCT& cs);

// Implementation
public:
	virtual ~CMainFrame();
#ifdef _DEBUG
	virtual void AssertValid() const;
	virtual void Dump(CDumpContext& dc) const;
#endif

protected:  // control bar embedded members
	CReBar            m_wndReBar;
	CNavigationBar    m_wndDlgBar;
	CStatusBar        m_wndStatusBar;

// Generated message map functions
protected:
	afx_msg int OnCreate(LPCREATESTRUCT lpCreateStruct);
	DECLARE_MESSAGE_MAP()

public:
	afx_msg void OnBnClickedButtonCopySelection();
	afx_msg void OnBnClickedButtonChangeBackgroundColor();
	afx_msg void OnBnClickedButtonTestPost();
	afx_msg void OnBnClickedButtonBack();
	afx_msg void OnBnClickedButtonForward();
	afx_msg void OnBnClickedButtonReload();
	afx_msg LRESULT OnNavigateToUrl(WPARAM wParam, LPARAM lParam);
	afx_msg void OnButtonReload();
	afx_msg void OnUpdateButtonReload(CCmdUI* pCmdUI);
	afx_msg void OnButtonShowPrintUI();
	afx_msg void OnButtonPrintToPdf();
};


