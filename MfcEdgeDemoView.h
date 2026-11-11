// MfcEdgeDemoView.h : interface of the CMfcEdgeDemoView class
//
#pragma once

#include <memory>
#include "EdgeWebBrowser.h"

class CMfcEdgeDemoDoc;

class CMfcEdgeDemoView : public CView
{
protected: // create from serialization only
	CMfcEdgeDemoView() noexcept;
	DECLARE_DYNCREATE(CMfcEdgeDemoView)

// Attributes
public:
	CMfcEdgeDemoDoc* GetDocument() const;

// Operations
public:
	void NavigateTo(CString const& url, std::function<void()> callback = nullptr);
	void NavigatePost(CString const& url, CString const& content, CString const& headers, std::function<void()> callback = nullptr);
	void NavigateBack();
	void NavigateForward();
	void StopNavigation();
	void ReloadPage();
   afx_msg void OnClearAppCache();
   afx_msg void OnClearAllAppData();
   afx_msg void OnUpdateClearAppCache(CCmdUI* pCmdUI);
	void ServeUrl(CString const& url);
	CString GetCurrentUrl();
	bool IsNavigating();
	void GetSelection(std::function<void(CString const &)> callback);
	void ExecuteScript(CString const & code);

// Overrides
public:
	virtual void OnDraw(CDC* pDC);  // overridden to draw this view
	virtual BOOL PreCreateWindow(CREATESTRUCT& cs);
protected:

// Implementation
public:
	virtual ~CMfcEdgeDemoView();
#ifdef _DEBUG
	virtual void AssertValid() const;
	virtual void Dump(CDumpContext& dc) const;
#endif

private:
	std::unique_ptr<CWebBrowser> m_pWebBrowser{};

// Generated message map functions
protected:
	DECLARE_MESSAGE_MAP()
public:
	virtual void OnInitialUpdate();
	virtual BOOL DestroyWindow();
	afx_msg void OnSize(UINT nType, int cx, int cy);
};

#ifndef _DEBUG  // debug version in MfcEdgeDemoView.cpp
inline CMfcEdgeDemoDoc* CMfcEdgeDemoView::GetDocument() const
   { return reinterpret_cast<CMfcEdgeDemoDoc*>(m_pDocument); }
#endif

