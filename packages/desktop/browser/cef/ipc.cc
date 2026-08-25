#include "ipc.h"

#include "include/cef_process_message.h"

void SendRendererIpc(CefRefPtr<CefBrowser> browser, int operation, double request_id, const std::string &channel,
                     const std::string &payload) {
  if (!browser || !browser->GetMainFrame()) return;
  const char *name = operation == 2 ? "ant.ipc.event" : "ant.ipc.reply";
  CefRefPtr<CefProcessMessage> message = CefProcessMessage::Create(name);
  CefRefPtr<CefListValue> values = message->GetArgumentList();
  values->SetInt(0, operation);
  values->SetDouble(1, request_id);
  values->SetString(2, channel);
  values->SetString(3, payload);
  browser->GetMainFrame()->SendProcessMessage(PID_RENDERER, message);
}
