#pragma once

// Target the original Windows 10 API level.
// This keeps the VS2015/v140 toolchain from selecting APIs from a newer SDK.
#include <WinSDKVer.h>
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#ifndef WINVER
#define WINVER 0x0A00
#endif
#include <SDKDDKVer.h>
