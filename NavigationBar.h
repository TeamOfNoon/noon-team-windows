#pragma once


// CNavigationBar

class CNavigationBar : public CDialogBar
{
	DECLARE_DYNAMIC(CNavigationBar)

public:
	CNavigationBar();
	virtual ~CNavigationBar();

protected:
	DECLARE_MESSAGE_MAP()
public:
	virtual BOOL PreTranslateMessage(MSG* pMsg);
};


