#pragma once
#include <string>
#include <map>
#include <vector>
bool HandleBuilderCommand();
int BuilderExitCode();
std::wstring BuiltOption(const std::string& key, const std::wstring& fallback=L"");
std::wstring BuiltExternalRoot();
std::string JsonString(const std::wstring& value);

bool HasRuntimeOption(const std::string& key);

bool ExtractEmbeddedWebTo(const std::wstring& parentFolder,std::wstring& report,int indexFormat=0,int indexKinds=7,const std::wstring& indexRanges=L"",int indexRanks=15);
bool AuthorizeBuilderGui(const std::wstring& password);
void LockBuilderGui();
bool BuildAppFromGui(const std::map<std::string,std::wstring>& values,std::wstring& report);

std::vector<std::wstring> BuilderIndexRanges();

bool ExtractSignedBinTo(const std::wstring& folder,std::wstring& report);

std::wstring RuntimeAppType();
