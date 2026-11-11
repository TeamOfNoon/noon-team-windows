#pragma once
#include <string>
#include <cctype>
#include <fstream>
#include <filesystem>

// Read only a root app_type string; never execute config.js.
inline std::wstring ConfigAppType(std::string text){
 if(text.size()>=2&&((unsigned char)text[0]==255||(unsigned char)text[0]==254)){
  bool le=(unsigned char)text[0]==255;std::string decoded;
  for(size_t i=2;i+1<text.size();i+=2)decoded+=text[i+(le?1:0)]==0?text[i+(le?0:1)]:'?';text=decoded;
 }
 auto skip=[&](size_t& i){for(;;){while(i<text.size()&&isspace((unsigned char)text[i]))++i;if(text.compare(i,2,"//")==0){i=text.find('\n',i);if(i==std::string::npos)i=text.size();}else if(text.compare(i,2,"/*")==0){auto end=text.find("*/",i+2);i=end==std::string::npos?text.size():end+2;}else break;}};
 auto token=[&](size_t& i){std::string value;if(i>=text.size())return value;char quote=text[i];if(quote=='\''||quote=='"'){++i;while(i<text.size()){char c=text[i++];if(c==quote)break;if(c=='\\'&&i<text.size())c=text[i++];value+=c;}}else{while(i<text.size()&&(isalnum((unsigned char)text[i])||text[i]=='_'))value+=text[i++];}return value;};
 int depth=0;bool key=false;
 for(size_t i=0;i<text.size();){skip(i);if(i==text.size())break;char c=text[i];
  if(c=='{'){++depth;++i;if(depth==1)key=true;continue;}
  if(c=='}'){--depth;++i;continue;}
  if(c==','){if(depth==1)key=true;++i;continue;}
  if(c=='\''||c=='"'||isalpha((unsigned char)c)||c=='_'){
   auto name=token(i);if(depth==1&&key){key=false;skip(i);if(i<text.size()&&text[i]==':'&&name=="app_type"){++i;skip(i);auto value=token(i);for(auto& ch:value)ch=(char)toupper((unsigned char)ch);if(value=="MN")return L"mn";if(value=="SN")return L"sn";return L"";}}continue;
  }++i;
 }return L"";
}
inline std::wstring ConfigFileAppType(const std::filesystem::path& file){
 std::ifstream in(file,std::ios::binary);if(!in)return L"";in.seekg(0,std::ios::end);auto size=in.tellg();if(size<0||size>4*1024*1024)return L"";std::string bytes((size_t)size,'\0');in.seekg(0);if(!bytes.empty()&&!in.read(bytes.data(),bytes.size()))return L"";return ConfigAppType(bytes);
}
