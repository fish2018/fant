#include "app_scheme.h"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <system_error>

#include "include/cef_parser.h"
#include "include/cef_request.h"
#include "include/cef_stream.h"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_stream_resource_handler.h"

namespace {

namespace fs = std::filesystem;

bool IsWithin(const fs::path &root, const fs::path &candidate) {
  auto root_part = root.begin();
  auto candidate_part = candidate.begin();
  for (; root_part != root.end(); ++root_part, ++candidate_part) {
    if (candidate_part == candidate.end() || *root_part != *candidate_part) { return false; }
  }
  return true;
}

class StringReadHandler final : public CefReadHandler {
public:
  explicit StringReadHandler(std::string value) : value_(std::move(value)) {}

  size_t Read(void *ptr, size_t size, size_t count) override {
    if (!size || position_ >= value_.size()) return 0;
    size_t available = (value_.size() - position_) / size;
    size_t read = std::min(count, available);
    memcpy(ptr, value_.data() + position_, read * size);
    position_ += read * size;
    return read;
  }

  int Seek(int64_t offset, int whence) override {
    int64_t base = whence == SEEK_SET   ? 0
                   : whence == SEEK_CUR ? static_cast<int64_t>(position_)
                                        : static_cast<int64_t>(value_.size());
    int64_t next = base + offset;
    if (next < 0 || static_cast<size_t>(next) > value_.size()) return -1;
    position_ = static_cast<size_t>(next);
    return 0;
  }

  int64_t Tell() override {
    return static_cast<int64_t>(position_);
  }
  int Eof() override {
    return position_ >= value_.size();
  }
  bool MayBlock() override {
    return false;
  }

private:
  std::string value_;
  size_t position_ = 0;
  IMPLEMENT_REFCOUNTING(StringReadHandler);
};

CefRefPtr<CefResourceHandler> StringResource(const std::string &mime, std::string value) {
  CefRefPtr<CefStreamReader> stream = CefStreamReader::CreateForHandler(new StringReadHandler(std::move(value)));
  return new CefStreamResourceHandler(mime, stream);
}

std::string Trim(std::string value) {
  size_t start = value.find_first_not_of(" \t\r\n");
  size_t end = value.find_last_not_of(" \t\r\n");
  return start == std::string::npos ? "" : value.substr(start, end - start + 1);
}

std::string NamedBindings(std::string value) {
  for (size_t alias = value.find(" as "); alias != std::string::npos; alias = value.find(" as ", alias + 2)) {
    value.replace(alias, 4, ": ");
  }
  return value;
}

std::string BindModule(const std::string &names, const std::string &require) {
  if (names.empty()) return require + ";";
  if (names.front() == '{') return "const " + NamedBindings(names) + "=" + require + ";";
  if (names.starts_with("* as ")) return "const " + Trim(names.substr(5)) + "=" + require + ";";
  size_t comma = names.find(',');
  if (comma == std::string::npos) return "const " + names + "=" + require + ";";
  std::string primary = Trim(names.substr(0, comma));
  std::string secondary = Trim(names.substr(comma + 1));
  if (secondary.starts_with("* as ")) secondary = Trim(secondary.substr(5));
  else secondary = NamedBindings(secondary);
  return "const " + primary + "=" + require + "," + secondary + "=" + primary + ";";
}

bool RewriteIntegrationImports(std::string *source) {
  size_t search = 0;
  for (;;) {
    size_t import = source->find("import", search);
    if (import == std::string::npos) return true;
    size_t statement_end = source->find(';', import);
    if (statement_end == std::string::npos) statement_end = source->size();
    size_t specifier = std::string::npos;
    for (const char *prefix : {"'node:", "\"node:", "'ant:", "\"ant:"}) {
      size_t candidate = source->find(prefix, import);
      if (candidate < specifier) specifier = candidate;
    }
    if (specifier == std::string::npos || specifier > statement_end) {
      search = statement_end;
      continue;
    }
    char quote = (*source)[specifier];
    size_t specifier_end = source->find(quote, specifier + 1);
    if (specifier_end == std::string::npos) return false;
    std::string module = source->substr(specifier + 1, specifier_end - specifier - 1);
    if (module.starts_with("ant://")) {
      search = specifier_end;
      continue;
    }
    size_t end = source->find(';', specifier_end);
    if (end == std::string::npos) end = specifier_end;
    std::string replacement;
    size_t from = source->rfind("from", specifier);
    if (from != std::string::npos && from >= import) {
      std::string names = Trim(source->substr(import + 6, from - import - 6));
      replacement = BindModule(names, "require('" + module + "')");
    } else if (source->compare(import, 7, "import(") == 0) {
      replacement = "Promise.resolve(require('" + module + "'))";
    } else {
      replacement = "require('" + module + "');";
    }
    source->replace(import, end - import + 1, replacement);
    search = import + replacement.size();
  }
}

class AntAppSchemeFactory final : public CefSchemeHandlerFactory {
public:
  AntAppSchemeFactory(std::string root, bool node_integration) : node_integration_(node_integration) {
    std::error_code error;
    root_ = fs::weakly_canonical(fs::path(std::move(root)), error);
    if (error) root_.clear();
  }

  CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                       const CefString &scheme_name, CefRefPtr<CefRequest> request) override {
    CEF_REQUIRE_IO_THREAD();
    CefURLParts parts;
    if (root_.empty() || !CefParseURL(request->GetURL(), parts) || CefString(&parts.host).ToString() != "app") {
      return nullptr;
    }

    std::string relative = CefURIDecode(CefString(&parts.path), false, UU_SPACES).ToString();
    while (!relative.empty() && relative.front() == '/')
      relative.erase(0, 1);
    if (relative.empty()) relative = "index.html";
    std::error_code error;
    fs::path file = fs::weakly_canonical(root_ / fs::path(relative), error);
    if (error || !IsWithin(root_, file) || !fs::is_regular_file(file, error)) { return nullptr; }

    std::string extension = file.extension().string();
    if (node_integration_ &&
        (extension == ".js" || extension == ".mjs" || extension == ".html" || extension == ".htm")) {
      std::ifstream input(file, std::ios::binary);
      std::ostringstream contents;
      contents << input.rdbuf();
      if (!input.good() && !input.eof()) return nullptr;
      std::string source = contents.str();
      if (!RewriteIntegrationImports(&source)) return nullptr;
      std::string mime = extension == ".html" || extension == ".htm" ? "text/html" : "text/javascript";
      return StringResource(mime, std::move(source));
    }

    CefRefPtr<CefStreamReader> stream = CefStreamReader::CreateForFile(file.string());
    if (!stream) return nullptr;
    if (!extension.empty() && extension.front() == '.') extension.erase(0, 1);
    CefString mime = CefGetMimeType(extension);
    if (mime.empty()) mime = "application/octet-stream";
    return new CefStreamResourceHandler(mime, stream);
  }

private:
  fs::path root_;
  bool node_integration_ = false;
  IMPLEMENT_REFCOUNTING(AntAppSchemeFactory);
};

} // namespace

bool RegisterAntAppSchemeHandler(const std::string &root, bool node_integration) {
  return !root.empty() &&
         CefRegisterSchemeHandlerFactory("ant", "app", new AntAppSchemeFactory(root, node_integration));
}
