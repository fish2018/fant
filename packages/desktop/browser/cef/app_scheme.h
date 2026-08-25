#ifndef ANT_DESKTOP_CEF_APP_SCHEME_H
#define ANT_DESKTOP_CEF_APP_SCHEME_H

#include <string>

#include "include/cef_scheme.h"

inline void RegisterAntCustomSchemes(CefRawPtr<CefSchemeRegistrar> registrar) {
  registrar->AddCustomScheme("ant", CEF_SCHEME_OPTION_STANDARD | CEF_SCHEME_OPTION_SECURE |
                                      CEF_SCHEME_OPTION_CORS_ENABLED | CEF_SCHEME_OPTION_FETCH_ENABLED);
}

bool RegisterAntAppSchemeHandler(const std::string &root, bool node_integration);

#endif
