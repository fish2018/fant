#include "node_bindings.h"

#include <cstdlib>

#include <unistd.h>

CefRefPtr<CefV8Value> CreateNodeEnvironmentBindings() {
  CefRefPtr<CefV8Value> environment = CefV8Value::CreateObject(nullptr, nullptr);
  char cwd[4096] = {0};
  const char *home = getenv("HOME");
  const char *temporary = getenv("TMPDIR");
  environment->SetValue("cwd", CefV8Value::CreateString(getcwd(cwd, sizeof(cwd)) ? cwd : "/"),
                        V8_PROPERTY_ATTRIBUTE_READONLY);
  environment->SetValue("home", CefV8Value::CreateString(home ? home : ""), V8_PROPERTY_ATTRIBUTE_READONLY);
  environment->SetValue("tmp", CefV8Value::CreateString(temporary ? temporary : "/tmp"),
                        V8_PROPERTY_ATTRIBUTE_READONLY);
  environment->SetValue("platform", CefV8Value::CreateString("darwin"), V8_PROPERTY_ATTRIBUTE_READONLY);
#if defined(__aarch64__) || defined(__arm64__)
  environment->SetValue("arch", CefV8Value::CreateString("arm64"), V8_PROPERTY_ATTRIBUTE_READONLY);
#else
  environment->SetValue("arch", CefV8Value::CreateString("x64"), V8_PROPERTY_ATTRIBUTE_READONLY);
#endif
  return environment;
}
