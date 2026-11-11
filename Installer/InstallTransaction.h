#pragma once
#include <filesystem>
#include <string>
#include <vector>
#include <utility>

// No Windows UI/registry dependencies: file replacement can be verified separately.
class NoonInstallTransaction {
    using Path = std::filesystem::path;
    Path destination_, stage_, previous_, destinationBackup_, previousBackup_;
    bool installed_ = false, committed_ = false;
public:
    NoonInstallTransaction(Path destination, Path stage, Path previous)
        : destination_(std::move(destination)), stage_(std::move(stage)), previous_(std::move(previous)) {}
    NoonInstallTransaction(const NoonInstallTransaction&) = delete;
    NoonInstallTransaction& operator=(const NoonInstallTransaction&) = delete;
    ~NoonInstallTransaction() { if (!committed_) rollback(); }
    void install(const std::wstring& token) {
        namespace fs = std::filesystem;
        if (fs::exists(destination_)) {
            Path backup = destination_.parent_path() / (std::wstring(L"Noon-Previous-") + token);
            fs::rename(destination_, backup); destinationBackup_ = backup;
        }
        if (!previous_.empty() && fs::exists(previous_)) {
            Path backup = previous_.parent_path() / (std::wstring(L"Noon-Previous-App-") + token);
            fs::rename(previous_, backup); previousBackup_ = backup;
        }
        fs::rename(stage_, destination_); installed_ = true;
    }
    bool committed() const noexcept { return committed_; }
    bool rollback() noexcept {
        if (committed_) return true;
        namespace fs = std::filesystem;
        std::error_code error; bool ok = true;
        if (installed_) {
            fs::remove_all(destination_, error);
            if (error) ok = false; else installed_ = false;
        }
        if (!destinationBackup_.empty() && !installed_) {
            error.clear(); fs::rename(destinationBackup_, destination_, error);
            if (error) ok = false; else destinationBackup_.clear();
        }
        if (!previousBackup_.empty()) {
            error.clear(); fs::rename(previousBackup_, previous_, error);
            if (error) ok = false; else previousBackup_.clear();
        }
        return ok;
    }
    std::vector<Path> commit() {
        committed_ = true;
        std::vector<Path> remaining;
        for (const Path& backup : {destinationBackup_, previousBackup_}) {
            if (backup.empty()) continue;
            std::error_code error; std::filesystem::remove_all(backup, error);
            if (error) remaining.push_back(backup);
        }
        return remaining;
    }
    const Path& destinationBackup() const { return destinationBackup_; }
    const Path& previousBackup() const { return previousBackup_; }
};
