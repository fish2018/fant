#if defined(__ANDROID__)

#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

#include <uv.h>

#include "inspector.h"
#include "readline.h"
#include "crash.h"
#include "gc/modules.h"
#include "modules/child_process.h"
#include "modules/readline.h"
#include "sandbox/policy.h"
#include "watch.h"

/*
 * Android embeds Ant through an application-owned API.  These desktop
 * facilities still appear in shared module code, so keep their symbols
 * available without pulling the desktop implementations into libant.
 */

void
ant_crash_suppress_reporting(void)
{
}

bool
ant_inspector_start(ant_t *js, const ant_inspector_options_t *options)
{
  (void)js;
  (void)options;
  return false;
}

void
ant_inspector_register_script_source(
  const char *path, const char *source, size_t len, bool is_module
)
{
  (void)path;
  (void)source;
  (void)len;
  (void)is_module;
}

void
ant_inspector_register_script_file(const char *path, bool is_module)
{
  (void)path;
  (void)is_module;
}

void
ant_inspector_console_api_called(
  ant_t *js, const char *level, ant_value_t *args, int nargs
)
{
  (void)js;
  (void)level;
  (void)args;
  (void)nargs;
}

uint64_t
ant_inspector_network_request(
  const char *method,
  const char *url,
  const char *type,
  const char *initiator,
  bool has_post_data,
  const ant_http_header_t *headers
)
{
  (void)method;
  (void)url;
  (void)type;
  (void)initiator;
  (void)has_post_data;
  (void)headers;
  return 0;
}

void
ant_inspector_network_response(
  uint64_t request_id,
  const char *url,
  int status,
  const char *status_text,
  const char *mime_type,
  const char *type,
  const ant_http_header_t *headers
)
{
  (void)request_id;
  (void)url;
  (void)status;
  (void)status_text;
  (void)mime_type;
  (void)type;
  (void)headers;
}

void
ant_inspector_network_finish(uint64_t request_id, size_t encoded_data_length)
{
  (void)request_id;
  (void)encoded_data_length;
}

void
ant_inspector_network_fail(
  uint64_t request_id, const char *error_text, bool canceled, const char *type
)
{
  (void)request_id;
  (void)error_text;
  (void)canceled;
  (void)type;
}

void
ant_inspector_network_set_request_body(
  uint64_t request_id, const uint8_t *data, size_t len
)
{
  (void)request_id;
  (void)data;
  (void)len;
}

void
ant_inspector_network_append_response_body(
  uint64_t request_id, const uint8_t *data, size_t len
)
{
  (void)request_id;
  (void)data;
  (void)len;
}

void
ant_inspector_websocket_request(
  uint64_t request_id, const ant_http_header_t *headers
)
{
  (void)request_id;
  (void)headers;
}

void
ant_inspector_websocket_response(
  uint64_t request_id,
  int status,
  const char *status_text,
  const ant_http_header_t *headers
)
{
  (void)request_id;
  (void)status;
  (void)status_text;
  (void)headers;
}

void
ant_inspector_websocket_frame_sent(
  uint64_t request_id, const uint8_t *data, size_t len, bool binary
)
{
  (void)request_id;
  (void)data;
  (void)len;
  (void)binary;
}

void
ant_inspector_websocket_frame_received(
  uint64_t request_id, const uint8_t *data, size_t len, bool binary
)
{
  (void)request_id;
  (void)data;
  (void)len;
  (void)binary;
}

void
ant_inspector_websocket_error(uint64_t request_id, const char *message)
{
  (void)request_id;
  (void)message;
}

void
ant_inspector_stop(void) {}
void
ant_inspector_wait_for_session(void) {}

uint64_t
ant_inspector_websocket_created(const char *url)
{
  (void)url;
  return 0;
}

void
ant_inspector_websocket_closed(uint64_t request_id)
{
  (void)request_id;
}

void
ant_readline_install_signal_handler(void) {}
void
ant_readline_shutdown(void) {}

bool
ant_readline_interrupt_pending(void)
{
  return false;
}

void
ant_readline_clear_interrupt(void) {}
void
ant_readline_drain_interrupt_wake(void) {}
void
ant_readline_shutdown_signal_bridge(void) {}

int
ant_readline_interrupt_fd(void)
{
  return -1;
}

void
ant_history_init(ant_history_t *hist, int capacity)
{
  if (!hist) return;
  hist->lines = NULL;
  hist->count = 0;
  hist->capacity = capacity > 0 ? capacity : 0;
  hist->current = 0;
}

void
ant_history_add(ant_history_t *hist, const char *line)
{
  (void)hist;
  (void)line;
}

void
ant_history_load(ant_history_t *hist)
{
  (void)hist;
}

void
ant_history_save(const ant_history_t *hist)
{
  (void)hist;
}

/* Android does not compile the desktop process/readline modules.  The
 * reactor and collector still call these module hooks unconditionally. */
int
has_pending_child_processes(void)
{
  return 0;
}

void
gc_mark_child_process(ant_t *js, gc_mark_fn mark)
{
  (void)js;
  (void)mark;
}

void
gc_mark_worker_threads(ant_t *js, gc_mark_fn mark)
{
  (void)js;
  (void)mark;
}

bool
has_active_readline_interfaces(void)
{
  return false;
}

void
gc_mark_readline(ant_t *js, gc_mark_fn mark)
{
  (void)js;
  (void)mark;
}

void
ant_history_free(ant_history_t *hist)
{
  if (!hist) return;
  free(hist->lines);
  hist->lines = NULL;
  hist->count = 0;
  hist->capacity = 0;
  hist->current = 0;
}

const char *
ant_history_prev(ant_history_t *hist)
{
  (void)hist;
  return NULL;
}

const char *
ant_history_next(ant_history_t *hist)
{
  (void)hist;
  return NULL;
}

ant_readline_result_t
ant_readline(
  ant_history_t *hist,
  const char *prompt,
  highlight_state line_state,
  char **out_line
)
{
  (void)hist;
  (void)prompt;
  (void)line_state;
  if (out_line) *out_line = NULL;
  return ANT_READLINE_EOF;
}

ant_readline_result_t
ant_readline_with_preview(
  ant_history_t *hist,
  const char *prompt,
  highlight_state line_state,
  ant_readline_preview_fn preview_fn,
  void *preview_ctx,
  char **out_line
)
{
  (void)preview_fn;
  (void)preview_ctx;
  return ant_readline(hist, prompt, line_state, out_line);
}

bool
ant_sandbox_policy_forward_restricted(void)
{
  return false;
}

bool
ant_sandbox_policy_port_forwarded(int port)
{
  (void)port;
  return true;
}

void
ant_sandbox_policy_set_forwards(const uint16_t *ports, uint32_t count)
{
  (void)ports;
  (void)count;
}

int
ant_watch_start(
  uv_loop_t *loop,
  uv_fs_event_t *event,
  const char *path,
  uv_fs_event_cb callback,
  void *data,
  unsigned int flags,
  char **resolved_path_out
)
{
  (void)loop;
  (void)event;
  (void)path;
  (void)callback;
  (void)data;
  (void)flags;
  if (resolved_path_out) *resolved_path_out = NULL;
  return UV_ENOSYS;
}

int
ant_watch_run(int argc, char **argv, const char *entry_file, bool no_clear_screen)
{
  (void)argc;
  (void)argv;
  (void)entry_file;
  (void)no_clear_screen;
  return UV_ENOSYS;
}

void
ant_watch_stop(uv_fs_event_t *event)
{
  (void)event;
}

char *
ant_watch_resolve_path(const char *path)
{
  if (!path) return NULL;
  return strdup(path);
}

#endif /* __ANDROID__ */
