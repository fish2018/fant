#ifndef ANT_DESKTOP_WINDOW_STATE_H
#define ANT_DESKTOP_WINDOW_STATE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <ant.h>

typedef int64_t ant_desktop_window_id_t;
struct ant_desktop_state;

typedef struct ant_desktop_window_state {
  ant_desktop_window_id_t identifier;
  ant_t *js;
  struct ant_desktop_state *desktop;
  ant_value_t load_promise;
  bool load_pending;
  bool devtools_open;
  bool show_when_ready;
  bool transparent_browser;
  bool sandbox;
  bool node_integration;
  bool context_isolation;
  char *preload_path;
  char *capability_manifest;
  size_t capability_manifest_length;
  void *platform_data;
  struct ant_desktop_window_state *next;
} ant_desktop_window_state_t;

ant_desktop_window_state_t *ant_desktop_window_create(ant_t *js, struct ant_desktop_state *desktop,
                                                      const char *capability_manifest,
                                                      size_t capability_manifest_length, bool transparent_browser);
void ant_desktop_window_destroy(ant_desktop_window_state_t *window);
ant_desktop_window_state_t *ant_desktop_window_find(ant_desktop_window_id_t identifier);
ant_desktop_window_state_t *ant_desktop_window_from_value(ant_t *js, ant_value_t value);
size_t ant_desktop_window_count(void);
ant_desktop_window_state_t *ant_desktop_window_first(void);

void ant_desktop_window_key(ant_desktop_window_id_t identifier, char key[32]);
ant_value_t ant_desktop_call_function(ant_t *js, ant_value_t function, ant_value_t this_value, ant_value_t *args,
                                      int nargs);
ant_value_t ant_desktop_emit_window_event(ant_desktop_window_state_t *window, const char *type, const char *detail,
                                          size_t detail_length, int64_t code);
void ant_desktop_dispatch_renderer_ipc(ant_desktop_window_state_t *window, int operation, uint64_t request_id,
                                       const char *channel, size_t channel_length, const char *payload,
                                       size_t payload_length);
void ant_desktop_reject_load(ant_desktop_window_state_t *window, const char *message);
void ant_desktop_resolve_load(ant_desktop_window_state_t *window);
bool ant_desktop_has_capability(const ant_desktop_window_state_t *window, const char *access, const char *channel,
                                size_t channel_length);
void ant_desktop_release_window_object(ant_desktop_window_state_t *window);

#endif
