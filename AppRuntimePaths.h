#pragma once
#include <filesystem>
#include <stdexcept>
#include "AppBuilder.h"
#include "AppFolderName.h"

inline std::filesystem::path AppRuntimeRoot(){
 static const auto root=[](){
 wchar_t exe[32768]{},local[32768]{};
 auto n=GetModuleFileNameW(nullptr,exe,32768);auto m=GetEnvironmentVariableW(L"LOCALAPPDATA",local,32768);
 if(!n||n>=32768||!m||m>=32768)throw std::runtime_error("Cannot locate app data folder");
 return std::filesystem::path(local)/AppFolderName(std::filesystem::path(exe).stem().wstring(),RuntimeAppType(),BuiltOption("runtime-folder",L""));
 }();return root;
}
inline std::filesystem::path AppRuntimeFolder(const wchar_t* name){
 auto path=AppRuntimeRoot()/name;std::filesystem::create_directories(path);return path;
}
