#pragma once
#include <string>
bool ReadBrowserConfig();
bool UseNWBrowser();
bool BrowserIsAuto();
bool LaunchNWBrowser();
std::wstring BrowserLaunchError();

bool ShowNavigationBar();
bool ShowMenuBar();

bool ClearBothBrowserData(std::wstring& report);

bool AcquireNoonAppInstance();
