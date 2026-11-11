#pragma once
#include <string>
bool StartNoonServer();
void StopNoonServer();
std::wstring NoonServerUrl();
std::wstring NoonServerError();

bool ExportNoonAppData(const std::wstring& destination,std::wstring& report,int indexFormat=0,int indexKinds=7,const std::wstring& indexRanges=L"",int indexRanks=15);

bool SaveNoonBuildSettings(const std::wstring& config,int port,const std::wstring& version,const std::wstring& app,std::wstring& report,const std::wstring& bootFile=L"dir.html",int passwordRequired=-1,const std::wstring& password=L"noon",const std::wstring& appType=L"");

bool VerifyNoonSignedBin(const std::wstring& destination,std::wstring& report);
