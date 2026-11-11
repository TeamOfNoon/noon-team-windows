#pragma once
#include <string>
#include <functional>
bool ChooseNoonPort(int previous,const std::wstring& reason,const std::wstring& config,const std::function<int()>& suggest,int& chosen);
