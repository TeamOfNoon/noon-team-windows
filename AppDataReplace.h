#pragma once
#include <filesystem>
#include <functional>
#include <vector>
#include <utility>
#include <string>
#include <stdexcept>

// Replace exported app/db only. Roll back both if any move fails.
class AppDataReplace {
 using Path=std::filesystem::path;
 struct Item {Path target,source,backup;bool saved=false,installed=false,skip=false;};
 Path destination_,stage_;
 std::vector<Item> items_;
 bool committed_=false;
 std::function<void(const Path&,const Path&)> move_;
public:
 AppDataReplace(Path destination,Path stage,std::function<void(const Path&,const Path&)> move={})
  :destination_(std::move(destination)),stage_(std::move(stage)),move_(std::move(move)){
  if(!move_)move_=[](const Path& a,const Path& b){std::filesystem::rename(a,b);};
  for(const auto* name:{"app","db","config.js","templets","lucene"})
   items_.push_back({destination_/name,stage_/name,stage_/(std::string(".previous-")+name)});
 }
 AppDataReplace(const AppDataReplace&)=delete;
 AppDataReplace& operator=(const AppDataReplace&)=delete;
 ~AppDataReplace(){if(!committed_)rollback();}
 void apply(){
  namespace fs=std::filesystem;
  // Validate everything before moving an existing folder.
  for(size_t i=0;i<items_.size();++i){auto& item=items_[i];
   if(i>=2){item.skip=fs::exists(item.target)||fs::is_symlink(item.target)||!fs::exists(item.source);continue;}
   if(!fs::is_directory(item.source)||fs::is_symlink(item.source))throw std::runtime_error("Exported app/db folder missing");
   if(fs::is_symlink(item.target)||(fs::exists(item.target)&&!fs::is_directory(item.target)))throw std::runtime_error("Destination app/db must be regular folders");
  }
  for(auto& item:items_){if(item.skip)continue;
   if(fs::exists(item.target)){move_(item.target,item.backup);item.saved=true;}
   move_(item.source,item.target);item.installed=true;
  }
 }
 bool rollback() noexcept{
  if(committed_)return true;
  bool ok=true;
  for(auto it=items_.rbegin();it!=items_.rend();++it){std::error_code error;
   if(it->installed){std::filesystem::remove_all(it->target,error);if(error)ok=false;else it->installed=false;}
   if(it->saved&&!it->installed){error.clear();std::filesystem::rename(it->backup,it->target,error);if(error)ok=false;else it->saved=false;}
  }return ok;
 }
 void commit() noexcept{committed_=true;}
};
