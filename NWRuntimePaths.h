#pragma once
#include <filesystem>
#include <string>
#include <vector>
#include <algorithm>
#include <cwctype>
#include <cstdint>

inline std::wstring NWPackageName(const std::wstring& folder){
 std::wstring result=L"noon-";uint64_t hash=14695981039346656037ULL;
 for(wchar_t c:folder){c=(wchar_t)towlower(c);hash^=(uint64_t)c;hash*=1099511628211ULL;result+=(c>=L'a'&&c<=L'z')||(c>=L'0'&&c<=L'9')||c==L'_'||c==L'-'||c==L'.'?c:L'_';}
 return result+L"-"+std::to_wstring(hash);
}
inline std::wstring NWProfileArguments(const std::filesystem::path& profile){
 // Node-Webkit before 0.13 uses data-path; newer NW.js uses user-data-dir.
 return L" --data-path=\""+profile.wstring()+L"\" --user-data-dir=\""+profile.wstring()+L"\" --disk-cache-dir=\""+(profile/L"Cache").wstring()+L"\"";
}
inline std::vector<wchar_t> NWEnvironment(const wchar_t* inherited,const std::wstring& temporary){
 std::vector<std::wstring> values;
 for(auto p=inherited;p&&*p;){std::wstring value=p;p+=value.size()+1;auto equal=value.find(L'=');auto key=value.substr(0,equal);for(auto& c:key)c=(wchar_t)towupper(c);if(key!=L"TEMP"&&key!=L"TMP")values.push_back(value);}
 values.push_back(L"TEMP="+temporary);values.push_back(L"TMP="+temporary);
 std::sort(values.begin(),values.end(),[](const auto& a,const auto& b){return std::lexicographical_compare(a.begin(),a.end(),b.begin(),b.end(),[](wchar_t x,wchar_t y){return towupper(x)<towupper(y);});});
 std::vector<wchar_t> result;for(const auto& value:values){result.insert(result.end(),value.begin(),value.end());result.push_back(0);}result.push_back(0);return result;
}
