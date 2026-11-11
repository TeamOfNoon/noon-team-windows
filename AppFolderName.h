#pragma once
#include <string>
#include <cwctype>
#include <stdexcept>

inline bool ValidAppFolderName(const std::wstring& name){
 if(name.empty()||name.size()>120||name==L"."||name==L".."||name.back()==L'.'||name.back()==L' ')return false;
 for(auto c:name)if(c<32||std::wstring(L"<>:\"/\\|?*").find(c)!=std::wstring::npos)return false;
 auto base=name.substr(0,name.find(L'.'));for(auto& c:base)c=(wchar_t)towupper(c);
 if(base==L"CON"||base==L"PRN"||base==L"AUX"||base==L"NUL"||base==L"CONIN$"||base==L"CONOUT$")return false;
 if(base.size()==4&&(base.substr(0,3)==L"COM"||base.substr(0,3)==L"LPT")&&base[3]>=L'1'&&base[3]<=L'9')return false;
 return true;
}
inline std::wstring AppFolderName(const std::wstring& exeName,const std::wstring& type,const std::wstring& custom){
 std::wstring edition=type;for(auto& c:edition)c=(wchar_t)towlower(c);
 const std::wstring suffix=edition==L"sn"?L"_sn":L"_mn";
 std::wstring base=custom.empty()?exeName:custom;
 if(!ValidAppFolderName(base))throw std::runtime_error("App folder name must be a valid single folder name");
 // Treat a previously entered edition suffix as a suffix, not part of the base.
 std::wstring lower=base;for(auto& c:lower)c=(wchar_t)towlower(c);
 if(!custom.empty()&&base.size()>3&&(lower.substr(lower.size()-3)==L"_mn"||lower.substr(lower.size()-3)==L"_sn"))base.resize(base.size()-3);
 auto result=base+suffix;
 if(!ValidAppFolderName(result))throw std::runtime_error("App folder name is too long");
 return result;
}
