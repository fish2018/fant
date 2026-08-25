#include "../platform/platform.h"
#include "desktop_core.h"
#include "renderer_bridge.h"

#include <stdlib.h>
#include <string.h>

#ifndef ANT_DESKTOP_VERSION
#define ANT_DESKTOP_VERSION "unknown"
#endif
#ifndef ANT_DESKTOP_CHROMIUM_VERSION
#define ANT_DESKTOP_CHROMIUM_VERSION "unknown"
#endif

static void SetVersion(ant_t *js, ant_value_t versions, const char *name, const char *value) {
  js_set(js, versions, name, js_mkstr(js, value, strlen(value)));
}

static void DesktopStateFinalize(ant_t *js, ant_object_t *object) {
  ant_value_t value = js_obj_from_ptr(object);
  ant_desktop_state_t *state = ant_desktop_state_from(value);
  js_clear_native(value, ANT_DESKTOP_STATE_TAG);
  free(state);
}

ant_value_t DesktopLibrary(ant_t *js) {
  ant_desktop_state_t *state = calloc(1, sizeof(*state));
  if (!state) return js_mkerr(js, "failed to allocate desktop state");
  state->js = js;
  state->ready = true;
  state->window_objects = js_mkobj(js);
  state->application_menu = js_mkundef();

  ant_value_t app = js_mkobj(js);
  state->app = app;
  ant_desktop_state_attach(app, state);

  js_set(js, app, "ready", js_mkfun(DesktopAppReady));
  js_set(js, app, "quit", js_mkfun(DesktopAppQuit));
  js_set(js, app, "getPath", js_mkfun(DesktopAppGetPath));
  js_set(js, app, "setApplicationMenu", js_mkfun(DesktopSetApplicationMenu));
  js_set(js, app, "getApplicationMenu", js_mkfun(DesktopGetApplicationMenu));
  const char *resources_path = ant_desktop_platform_resources_path();
  js_set(js, app, "resourcesPath", js_mkstr(js, resources_path, strlen(resources_path)));

  ant_value_t versions = js_mkobj(js);
  SetVersion(js, versions, "ant", ANT_VERSION);
  SetVersion(js, versions, "desktop", ANT_DESKTOP_VERSION);
  SetVersion(js, versions, "chrome", ANT_DESKTOP_CHROMIUM_VERSION);
  js_set(js, app, "versions", versions);

  ant_value_t process = js_get(js, js_glob(js), "process");
  ant_value_t process_versions = js_get(js, process, "versions");
  if (is_object_type(process_versions)) {
    SetVersion(js, process_versions, "ant-desktop", ANT_DESKTOP_VERSION);
    SetVersion(js, process_versions, "chrome", ANT_DESKTOP_CHROMIUM_VERSION);
  }

  state->ipc_handlers = js_mkobj(js);
  state->ipc_listeners = js_mkobj(js);

  ant_value_t bridge_factory =
    js_eval_bytecode_eval(js, (const char *)ant_desktop_ipc_bridge_source, ant_desktop_ipc_bridge_source_len);
  if (is_err(bridge_factory)) {
    return js_mkerr(js, "desktop IPC bridge compile failed: %s", js_str(js, bridge_factory));
  }
  ant_value_t bindings = js_mkobj(js);
  ant_desktop_state_attach(bindings, state);
  js_set(js, bindings, "windows", state->window_objects);
  js_set(js, bindings, "handlers", state->ipc_handlers);
  js_set(js, bindings, "listeners", state->ipc_listeners);
  js_set(js, bindings, "reply", js_mkfun(DesktopNativeIpcReply));
  ant_value_t bridge = ant_desktop_call_function(js, bridge_factory, js_mkundef(), &bindings, 1);
  if (is_err(bridge)) { return js_mkerr(js, "desktop IPC bridge initialization failed: %s", js_str(js, bridge)); }
  state->encode = js_get(js, bridge, "encode");
  state->dispatch = js_get(js, bridge, "dispatch");
  if (!is_callable(state->encode) || !is_callable(state->dispatch)) {
    return js_mkerr(js, "desktop IPC bridge did not initialize");
  }

  ant_value_t ipc_main = js_mkobj(js);
  ant_desktop_state_attach(ipc_main, state);
  js_set_slot_wb(js, ipc_main, SLOT_DEFAULT, bridge);
  js_set(js, ipc_main, "handle", js_mkfun(DesktopIpcMainHandle));
  js_set(js, ipc_main, "removeHandler", js_mkfun(DesktopIpcMainRemoveHandler));
  js_set(js, ipc_main, "on", js_mkfun(DesktopIpcMainOn));

  state->browser_window_proto = js_mkobj(js);
  ant_desktop_state_attach(state->browser_window_proto, state);
  js_set(js, state->browser_window_proto, "loadURL", js_mkfun(DesktopBrowserWindowLoadURL));
  js_set(js, state->browser_window_proto, "loadFile", js_mkfun(DesktopBrowserWindowLoadFile));
  js_set(js, state->browser_window_proto, "close", js_mkfun(DesktopBrowserWindowClose));
  js_set(js, state->browser_window_proto, "on", js_mkfun(DesktopBrowserWindowOn));
  js_set(js, state->browser_window_proto, "getBounds", js_mkfun(DesktopBrowserWindowGetBounds));
  js_set(js, state->browser_window_proto, "show", js_mkfun(DesktopBrowserWindowShow));
  js_set(js, state->browser_window_proto, "hide", js_mkfun(DesktopBrowserWindowHide));
  js_set(js, state->browser_window_proto, "minimize", js_mkfun(DesktopBrowserWindowMinimize));
  js_set(js, state->browser_window_proto, "restore", js_mkfun(DesktopBrowserWindowRestore));
  js_set(js, state->browser_window_proto, "maximize", js_mkfun(DesktopBrowserWindowMaximize));
  js_set(js, state->browser_window_proto, "setAlwaysOnTop", js_mkfun(DesktopBrowserWindowSetAlwaysOnTop));
  js_set(js, state->browser_window_proto, "setTitle", js_mkfun(DesktopBrowserWindowSetTitle));
  js_set(js, state->browser_window_proto, "setFullScreen", js_mkfun(DesktopBrowserWindowSetFullScreen));
  state->browser_window_ctor =
    js_make_ctor(js, DesktopBrowserWindowCtor, state->browser_window_proto, "BrowserWindow", 13);
  ant_desktop_state_attach(state->browser_window_ctor, state);

  ant_value_t menu = js_mkobj(js);
  ant_desktop_state_attach(menu, state);
  js_set(js, menu, "buildFromTemplate", js_mkfun(DesktopMenuBuildFromTemplate));
  js_set(js, menu, "setApplicationMenu", js_mkfun(DesktopSetApplicationMenu));
  js_set(js, menu, "getApplicationMenu", js_mkfun(DesktopGetApplicationMenu));
  ant_value_t menu_item_proto = js_mkobj(js);
  ant_value_t menu_item = js_make_ctor(js, DesktopMenuItemCtor, menu_item_proto, "MenuItem", 8);
  ant_value_t exports = js_mkobj(js);
  js_set(js, exports, "app", app);
  ant_desktop_state_attach(exports, state);
  js_set_finalizer(exports, DesktopStateFinalize);
  js_set(js, exports, "BrowserWindow", state->browser_window_ctor);
  js_set(js, exports, "ipcMain", ipc_main);
  js_set(js, exports, "Menu", menu);
  js_set(js, exports, "MenuItem", menu_item);
  js_set(js, exports, "versions", versions);
  return exports;
}
