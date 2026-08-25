#ifndef ANT_DESKTOP_CEF_IPC_H
#define ANT_DESKTOP_CEF_IPC_H

#include <string>

#include "include/cef_browser.h"

void SendRendererIpc(CefRefPtr<CefBrowser> browser, int operation, double request_id, const std::string &channel,
                     const std::string &payload);

#endif
