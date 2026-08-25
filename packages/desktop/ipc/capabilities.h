#ifndef ANT_DESKTOP_CAPABILITIES_H
#define ANT_DESKTOP_CAPABILITIES_H

#include <set>
#include <string>

namespace ant::desktop {
std::set<std::string> ParseCapabilities(const std::string &manifest);
}

#endif
