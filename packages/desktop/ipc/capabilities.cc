#include "capabilities.h"

namespace ant::desktop {
std::set<std::string> ParseCapabilities(const std::string &manifest) {
  std::set<std::string> result;
  size_t start = 0;
  while (start < manifest.size()) {
    size_t end = manifest.find(';', start);
    if (end == std::string::npos) end = manifest.size();
    if (end > start) result.insert(manifest.substr(start, end - start));
    start = end + 1;
  }
  return result;
}
} // namespace ant::desktop
