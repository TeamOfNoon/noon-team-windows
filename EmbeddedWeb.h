#pragma once
#include <shlwapi.h>
#include <vector>
#include <sstream>
#include <string>
#include <cwchar>
#include <cstring>

struct PackagedWebFile { std::wstring path; unsigned long long offset; DWORD bytes; };
class EmbeddedPackage {
    HANDLE file = INVALID_HANDLE_VALUE, mapping = nullptr;
public:
    const BYTE* data = nullptr;
    std::wstring startup = L"https://noon.invalid/dir.html";
    std::vector<PackagedWebFile> files;
    EmbeddedPackage() {
        wchar_t exe[32768]{}; GetModuleFileNameW(nullptr, exe, 32768);
        file = CreateFileW(exe, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
        LARGE_INTEGER size{};
        if (file == INVALID_HANDLE_VALUE || !GetFileSizeEx(file, &size) || size.QuadPart < 24) return;
        mapping = CreateFileMappingW(file, nullptr, PAGE_READONLY, 0, 0, nullptr);
        if (!mapping) return;
        data = static_cast<const BYTE*>(MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0));
        if (!data) return;
        unsigned long long total = static_cast<unsigned long long>(size.QuadPart), offset = 0, bytes = 0;
        const BYTE* trailer = data + total - 24;
        if (memcmp(trailer, "NHTMLPK1", 8) != 0) return;
        memcpy(&offset, trailer + 8, 8); memcpy(&bytes, trailer + 16, 8);
        if (offset > total - 24 || bytes > total - 24 - offset || bytes > 0x7fffffff) return;
        int count = MultiByteToWideChar(CP_UTF8, 0, reinterpret_cast<const char*>(data + offset), static_cast<int>(bytes), nullptr, 0);
        std::wstring text(count, L'\0');
        if (count) MultiByteToWideChar(CP_UTF8, 0, reinterpret_cast<const char*>(data + offset), static_cast<int>(bytes), &text[0], count);
        std::wistringstream input(text); std::wstring line;
        if (!std::getline(input, startup)) return;
        while (std::getline(input, line)) {
            auto tab = line.find(L'\t'), tab2 = line.find(L'\t', tab == std::wstring::npos ? 0 : tab + 1);
            if (tab == std::wstring::npos || tab2 == std::wstring::npos) continue;
            auto position = _wcstoui64(line.substr(tab + 1, tab2 - tab - 1).c_str(), nullptr, 10);
            auto length = _wcstoui64(line.substr(tab2 + 1).c_str(), nullptr, 10);
            if (position > offset || length > offset - position || length > 0x7fffffff) continue;
            files.push_back({line.substr(0, tab), position, static_cast<DWORD>(length)});
        }
    }
    ~EmbeddedPackage() { if (data) UnmapViewOfFile(data); if (mapping) CloseHandle(mapping); if (file != INVALID_HANDLE_VALUE) CloseHandle(file); }
};
inline EmbeddedPackage& PackageData() { static EmbeddedPackage value; return value; }
inline std::wstring EmbeddedStartupUrl() { return PackageData().startup; }
inline const std::vector<PackagedWebFile>& PackagedFiles() { return PackageData().files; }

inline const wchar_t* EmbeddedMime(const std::wstring& path) {
    auto dot = path.find_last_of(L'.');
    std::wstring ext = dot == std::wstring::npos ? L"" : path.substr(dot);
    if (ext == L".html" || ext == L".htm") return L"text/html; charset=utf-8";
    if (ext == L".js") return L"application/javascript; charset=utf-8";
    if (ext == L".css") return L"text/css; charset=utf-8";
    if (ext == L".json") return L"application/json; charset=utf-8";
    if (ext == L".svg") return L"image/svg+xml";
    if (ext == L".png") return L"image/png";
    if (ext == L".jpg" || ext == L".jpeg") return L"image/jpeg";
    if (ext == L".gif") return L"image/gif";
    if (ext == L".ico") return L"image/x-icon";
    if (ext == L".woff") return L"font/woff";
    if (ext == L".woff2") return L"font/woff2";
    if (ext == L".ttf") return L"font/ttf";
    if (ext == L".wasm") return L"application/wasm";
    if (ext == L".mp3") return L"audio/mpeg";
    if (ext == L".mp4") return L"video/mp4";
    return L"application/octet-stream";
}

inline HRESULT ServeEmbeddedWeb(ICoreWebView2Environment* environment,
    ICoreWebView2WebResourceRequestedEventArgs* args) {
    Microsoft::WRL::ComPtr<ICoreWebView2WebResourceRequest> request;
    HRESULT hr = args->get_Request(&request);
    if (FAILED(hr)) return hr;
    LPWSTR uri = nullptr;
    hr = request->get_Uri(&uri);
    if (FAILED(hr)) return hr;
    std::wstring url = uri ? uri : L"";
    CoTaskMemFree(uri);
    const std::wstring origin = L"https://noon.invalid";
    if (url.compare(0, origin.size() + 1, origin + L"/") != 0) return S_OK;
    std::wstring path = url.substr(origin.size());
    path = path.substr(0, path.find_first_of(L"?#"));
    DWORD count = static_cast<DWORD>(path.size() + 1);
    std::wstring decoded(count, L'\0');
    hr = UrlUnescapeW(&path[0], &decoded[0], &count, 0);
    if (FAILED(hr)) return hr;
    decoded.resize(count);
    path = decoded;
    if (path == L"/") path = EmbeddedStartupUrl().substr(origin.size());
    Microsoft::WRL::ComPtr<IStream> stream;
    bool found = false;
    for (const auto& file : PackagedFiles()) {
        if (path != file.path) continue;
        const BYTE* data = PackageData().data + file.offset;
        stream.Attach(SHCreateMemStream(data, file.bytes));
        if (!stream) return E_OUTOFMEMORY;
        found = true;
        break;
    }
    std::wstring headers = L"Content-Type: ";
    headers += found ? EmbeddedMime(path) : L"text/plain; charset=utf-8";
    headers += L"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff";
    Microsoft::WRL::ComPtr<ICoreWebView2WebResourceResponse> response;
    hr = environment->CreateWebResourceResponse(stream.Get(), found ? 200 : 404,
        found ? L"OK" : L"Not Found", headers.c_str(), &response);
    return FAILED(hr) ? hr : args->put_Response(response.Get());
}
