#pragma once
#include "../AppTypeConfig.h"
#include <filesystem>
#include <fstream>
#include <string>
#include <cstdint>

inline std::wstring ExistingInstallType(const std::filesystem::path& folder){
 namespace fs=std::filesystem;
 if(fs::is_regular_file(folder/L".noon-edition")){
  std::ifstream in(folder/L".noon-edition",std::ios::binary);char bytes[9]{};in.read(bytes,9);auto n=in.gcount();
  if(n==8&&std::string(bytes,8)=="NTAPP_MN")return L"mn";
  if(n==8&&std::string(bytes,8)=="NTAPP_SN")return L"sn";
  return L"invalid";
 }
 std::wstring type;
 // Older installers have no edition marker. Read their raw embedded config.
 std::ifstream in(folder/L"Noon.exe",std::ios::binary);in.seekg(0,std::ios::end);auto total=in.tellg();
 if(total>=24){
  in.seekg(total-std::streamoff(24));char footer[24]{};in.read(footer,24);
  auto number=[&](int at){uint64_t value=0;for(int i=7;i>=0;--i)value=(value<<8)|(unsigned char)footer[at+i];return value;};
  auto offset=number(8),size=number(16);
  if(std::string(footer,8)=="NHTMLPK1"&&size<=64*1024*1024&&offset<=(uint64_t)total-24&&size==(uint64_t)total-24-offset){
   in.seekg(offset);std::string manifest((size_t)size,'\0');if(in.read(manifest.data(),manifest.size())){
    size_t start=0;
    while(start<manifest.size()){
     auto end=manifest.find('\n',start);auto line=manifest.substr(start,end==std::string::npos?std::string::npos:end-start);auto a=line.find('\t'),b=line.find('\t',a==std::string::npos?0:a+1);
     if(a!=std::string::npos&&b!=std::string::npos&&line.substr(0,a)=="/__bin/config.js"){
      try{auto at=std::stoull(line.substr(a+1,b-a-1)),length=std::stoull(line.substr(b+1));if(length<=4*1024*1024&&at<=offset&&length<=offset-at){in.clear();in.seekg(at);std::string bytes((size_t)length,'\0');if(in.read(bytes.data(),bytes.size()))type=ConfigAppType(bytes);}}catch(...){}break;
     }
     if(end==std::string::npos)break;start=end+1;
    }
   }
  }
 }
 auto external=ConfigFileAppType(folder/L"bin"/L"config.js");if(!external.empty())type=external;
 return type;
}
inline bool IsExistingInstall(const std::filesystem::path& folder,const std::wstring& edition,bool registered=false){
 namespace fs=std::filesystem;std::error_code ec;
 if(folder.empty()||fs::is_symlink(folder,ec)||!fs::is_regular_file(folder/L"Noon.exe",ec))return false;
 if(!registered){
  std::ifstream marker(folder/L".noon-install");std::string value;std::getline(marker,value);if(!value.empty()&&value.back()=='\r')value.pop_back();
  if(value!="NOON_INSTALL_V1"||!fs::is_regular_file(folder/L".noon-files",ec))return false;
 }
 try{auto type=ExistingInstallType(folder);auto expected=edition==L"SN"?L"sn":L"mn";return type==expected||(registered&&type.empty());}catch(...){return false;}
}
