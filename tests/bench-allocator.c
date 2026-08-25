#include <errno.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__APPLE__)
#include <sys/resource.h>
#include <unistd.h>
#else
#include <sys/resource.h>
#include <unistd.h>
#endif

typedef struct {
  uint32_t tag;
  uint32_t flags;
  uint64_t hash;
  void *next;
  char bytes[48];
} bench_small_object_t;

typedef struct {
  void *ptr;
  size_t size;
} bench_block_t;

typedef struct {
  uint64_t seed;
  size_t iterations;
  size_t retain_slots;
  size_t handoff_cap;
  bench_block_t *handoff;
  size_t handoff_len;
  uint64_t allocs;
  uint64_t frees;
  uint64_t bytes_allocated;
  uint64_t bytes_freed;
  uint64_t checksum;
} bench_worker_t;

typedef struct {
  size_t iterations;
  int threads;
  uint64_t seed;
  size_t retain_slots;
  size_t handoff_cap;
  int idle_ms;
  bool csv_header;
} bench_options_t;

static uint64_t rng_next(uint64_t *state) {
  uint64_t x = *state;
  x ^= x >> 12;
  x ^= x << 25;
  x ^= x >> 27;
  *state = x;
  return x * UINT64_C(2685821657736338717);
}

static size_t choose_size(uint64_t r) {
  switch (r & 15u) {
    case 0: return 8 + ((r >> 8) & 31u);
    case 1: return 24 + ((r >> 8) & 63u);
    case 2: return 64 + ((r >> 8) & 127u);
    case 3: return 160 + ((r >> 8) & 255u);
    case 4: return 512 + ((r >> 8) & 511u);
    case 5: return 1024 + ((r >> 8) & 1023u);
    case 6: return 4096 + ((r >> 8) & 4095u);
    case 7: return 16384 + ((r >> 8) & 8191u);
    case 8: return 65536 + ((r >> 8) & 65535u);
    case 9: return 262144 + ((r >> 8) & 131071u);
    case 10: return 1048576 + ((r >> 8) & 262143u);
    default: return 32 + ((r >> 8) & 511u);
  }
}

static void *xmalloc(size_t size) {
  void *p = malloc(size ? size : 1);
  if (!p) {
    fprintf(stderr, "bench-allocator: malloc(%zu) failed\n", size);
    exit(2);
  }
  return p;
}

static void *xcalloc(size_t count, size_t size) {
  void *p = calloc(count ? count : 1, size ? size : 1);
  if (!p) {
    fprintf(stderr, "bench-allocator: calloc(%zu, %zu) failed\n", count, size);
    exit(2);
  }
  return p;
}

static void *xrealloc(void *ptr, size_t size) {
  void *p = realloc(ptr, size ? size : 1);
  if (!p) {
    fprintf(stderr, "bench-allocator: realloc(%zu) failed\n", size);
    exit(2);
  }
  return p;
}

static void touch_bytes(uint8_t *p, size_t size, uint64_t salt) {
  if (size == 0) return;
  p[0] = (uint8_t)salt;
  p[size - 1] = (uint8_t)(salt >> 8);
  for (size_t i = 64; i < size; i += 4096) p[i] ^= (uint8_t)(salt >> (i & 31u));
}

static void worker_alloc(bench_worker_t *w, size_t size) {
  w->allocs++;
  w->bytes_allocated += size;
}

static void worker_free(bench_worker_t *w, size_t size) {
  w->frees++;
  w->bytes_freed += size;
}

static void free_slot(bench_worker_t *w, bench_block_t *slot) {
  if (!slot->ptr) return;
  free(slot->ptr);
  worker_free(w, slot->size);
  slot->ptr = NULL;
  slot->size = 0;
}

static bool handoff_block(bench_worker_t *w, void *ptr, size_t size) {
  if (w->handoff_len >= w->handoff_cap) return false;
  w->handoff[w->handoff_len++] = (bench_block_t){ptr, size};
  return true;
}

static void retain_or_free(bench_worker_t *w, bench_block_t *slots, void *ptr, size_t size, uint64_t r) {
  if ((r & 255u) == 0 && handoff_block(w, ptr, size)) return;

  if ((r & 7u) == 0) {
    size_t idx = (size_t)((r >> 16) % w->retain_slots);
    free_slot(w, &slots[idx]);
    slots[idx] = (bench_block_t){ptr, size};
    return;
  }

  free(ptr);
  worker_free(w, size);
}

static void op_small_string(bench_worker_t *w, bench_block_t *slots, uint64_t *rng) {
  uint64_t r = rng_next(rng);
  size_t len = 12 + (size_t)(r % 244u);
  char *p = (char *)xmalloc(len + 1);
  worker_alloc(w, len + 1);

  size_t prefix = snprintf(p, len + 1, "/ant/pkg/%" PRIu64 "/node_modules/pkg-%" PRIu64 "?q=%" PRIu64, r & 4095u, (r >> 12) & 8191u, r);
  for (size_t i = prefix; i < len; i++) p[i] = (char)('a' + ((r + i) % 26u));
  p[len] = '\0';
  w->checksum ^= (uint64_t)(unsigned char)p[len / 2] + len;

  retain_or_free(w, slots, p, len + 1, r);
}

static void op_realloc_builder(bench_worker_t *w, bench_block_t *slots, uint64_t *rng) {
  uint64_t r = rng_next(rng);
  size_t cap = 32 + (size_t)(r & 31u);
  char *buf = (char *)xmalloc(cap);
  worker_alloc(w, cap);

  size_t len = 0;
  int rounds = 3 + (int)((r >> 8) & 7u);
  for (int i = 0; i < rounds; i++) {
    size_t add = 17 + (size_t)((rng_next(rng) >> 8) & 511u);
    if (len + add + 1 > cap) {
      size_t old = cap;
      while (len + add + 1 > cap) cap *= 2;
      buf = (char *)xrealloc(buf, cap);
      worker_alloc(w, cap - old);
    }
    memset(buf + len, 'A' + (i % 26), add);
    len += add;
    buf[len] = '\0';
  }

  w->checksum += (uint64_t)(unsigned char)buf[len ? len - 1 : 0] + len;
  retain_or_free(w, slots, buf, cap, r >> 3);
}

static void op_calloc_object(bench_worker_t *w, bench_block_t *slots, uint64_t *rng) {
  uint64_t r = rng_next(rng);
  size_t extra = (size_t)(r & 127u);
  size_t size = sizeof(bench_small_object_t) + extra;
  bench_small_object_t *obj = (bench_small_object_t *)xcalloc(1, size);
  worker_alloc(w, size);

  obj->tag = (uint32_t)r;
  obj->flags = (uint32_t)(r >> 32);
  obj->hash = rng_next(rng);
  touch_bytes((uint8_t *)obj->bytes, sizeof(obj->bytes), obj->hash);
  w->checksum ^= obj->hash + obj->tag + extra;

  retain_or_free(w, slots, obj, size, r >> 5);
}

static void op_buffer(bench_worker_t *w, bench_block_t *slots, uint64_t *rng) {
  uint64_t r = rng_next(rng);
  size_t size = choose_size(r);
  uint8_t *buf = (uint8_t *)xmalloc(size);
  worker_alloc(w, size);

  touch_bytes(buf, size, r);
  w->checksum += buf[0] + buf[size - 1] + size;

  retain_or_free(w, slots, buf, size, r >> 7);
}

static void op_header_list(bench_worker_t *w, bench_block_t *slots, uint64_t *rng) {
  uint64_t r = rng_next(rng);
  size_t pairs = 2 + (size_t)(r & 7u);
  size_t total = sizeof(void *) * pairs * 2u;
  char **parts = (char **)xcalloc(pairs * 2u, sizeof(char *));
  worker_alloc(w, total);

  for (size_t i = 0; i < pairs * 2u; i++) {
    size_t len = 8 + (size_t)((rng_next(rng) >> 9) & 63u);
    parts[i] = (char *)xmalloc(len + 1);
    worker_alloc(w, len + 1);
    memset(parts[i], (i & 1u) ? 'v' : 'k', len);
    parts[i][len] = '\0';
    w->checksum += (uint64_t)parts[i][0] + len;
  }

  for (size_t i = 0; i < pairs * 2u; i++) {
    size_t len = strlen(parts[i]) + 1;
    free(parts[i]);
    worker_free(w, len);
  }

  retain_or_free(w, slots, parts, total, r >> 11);
}

static void op_burst(bench_worker_t *w, bench_block_t *slots, uint64_t *rng) {
  uint64_t r = rng_next(rng);
  size_t count = 4 + (size_t)(r & 15u);
  bench_block_t local[20];

  for (size_t i = 0; i < count; i++) {
    size_t size = 24 + (size_t)((rng_next(rng) >> 8) & 4095u);
    local[i].ptr = xmalloc(size);
    local[i].size = size;
    worker_alloc(w, size);
    touch_bytes((uint8_t *)local[i].ptr, size, r + i);
  }

  for (size_t i = 0; i < count; i++) {
    if ((i & 3u) == 0) {
      retain_or_free(w, slots, local[i].ptr, local[i].size, r + i);
    } else {
      free(local[i].ptr);
      worker_free(w, local[i].size);
    }
  }
}

static void *worker_main(void *arg) {
  bench_worker_t *w = (bench_worker_t *)arg;
  uint64_t rng = w->seed ? w->seed : UINT64_C(0x123456789abcdef);
  bench_block_t *slots = (bench_block_t *)xcalloc(w->retain_slots, sizeof(*slots));

  for (size_t i = 0; i < w->iterations; i++) {
    switch (rng_next(&rng) % 12u) {
      case 0:
      case 1:
      case 2:
      case 3: op_small_string(w, slots, &rng); break;
      case 4:
      case 5: op_realloc_builder(w, slots, &rng); break;
      case 6:
      case 7: op_calloc_object(w, slots, &rng); break;
      case 8: op_buffer(w, slots, &rng); break;
      case 9: op_header_list(w, slots, &rng); break;
      default: op_burst(w, slots, &rng); break;
    }
  }

  for (size_t i = 0; i < w->retain_slots; i++) free_slot(w, &slots[i]);
  free(slots);
  return NULL;
}

static double monotonic_seconds(void) {
#if defined(_WIN32)
  LARGE_INTEGER freq;
  LARGE_INTEGER counter;
  QueryPerformanceFrequency(&freq);
  QueryPerformanceCounter(&counter);
  return (double)counter.QuadPart / (double)freq.QuadPart;
#else
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (double)ts.tv_sec + (double)ts.tv_nsec / 1000000000.0;
#endif
}

static void sleep_ms(int ms) {
  if (ms <= 0) return;
#if defined(_WIN32)
  Sleep((DWORD)ms);
#else
  struct timespec ts;
  ts.tv_sec = ms / 1000;
  ts.tv_nsec = (long)(ms % 1000) * 1000000L;
  while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {}
#endif
}

static uint64_t peak_rss_bytes(void) {
#if defined(_WIN32)
  return 0;
#else
  struct rusage usage;
  if (getrusage(RUSAGE_SELF, &usage) != 0) return 0;
#if defined(__APPLE__)
  return (uint64_t)usage.ru_maxrss;
#else
  return (uint64_t)usage.ru_maxrss * 1024u;
#endif
#endif
}

static bool parse_size(const char *s, size_t *out) {
  if (!s || !*s) return false;
  char *end = NULL;
  errno = 0;
  unsigned long long value = strtoull(s, &end, 10);
  if (errno != 0 || end == s || *end != '\0') return false;
  *out = (size_t)value;
  return true;
}

static bool parse_int(const char *s, int *out) {
  size_t value = 0;
  if (!parse_size(s, &value) || value > INT32_MAX) return false;
  *out = (int)value;
  return true;
}

static bool parse_u64(const char *s, uint64_t *out) {
  if (!s || !*s) return false;
  char *end = NULL;
  errno = 0;
  unsigned long long value = strtoull(s, &end, 0);
  if (errno != 0 || end == s || *end != '\0') return false;
  *out = (uint64_t)value;
  return true;
}

static void usage(const char *argv0) {
  fprintf(stderr,
    "usage: %s [--iterations N] [--threads N] [--seed N] [--retain-slots N] [--handoff N] [--idle-ms N] [--no-header]\n"
    "\n"
    "Runs an Ant-shaped malloc benchmark: small string/path churn, realloc builders,\n"
    "calloc structs, buffer payloads, retained caches, bursts, and cross-thread frees.\n",
    argv0
  );
}

int main(int argc, char **argv) {
  bench_options_t opt = {
    .iterations = 100000,
    .threads = 1,
    .seed = UINT64_C(0x5eed1234abcdef),
    .retain_slots = 4096,
    .handoff_cap = 4096,
    .idle_ms = 0,
    .csv_header = true,
  };

  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--iterations") == 0 && i + 1 < argc) {
      if (!parse_size(argv[++i], &opt.iterations)) { usage(argv[0]); return 1; }
    } else if (strcmp(argv[i], "--threads") == 0 && i + 1 < argc) {
      if (!parse_int(argv[++i], &opt.threads) || opt.threads <= 0) { usage(argv[0]); return 1; }
    } else if (strcmp(argv[i], "--seed") == 0 && i + 1 < argc) {
      if (!parse_u64(argv[++i], &opt.seed)) { usage(argv[0]); return 1; }
    } else if (strcmp(argv[i], "--retain-slots") == 0 && i + 1 < argc) {
      if (!parse_size(argv[++i], &opt.retain_slots) || opt.retain_slots == 0) { usage(argv[0]); return 1; }
    } else if (strcmp(argv[i], "--handoff") == 0 && i + 1 < argc) {
      if (!parse_size(argv[++i], &opt.handoff_cap)) { usage(argv[0]); return 1; }
    } else if (strcmp(argv[i], "--idle-ms") == 0 && i + 1 < argc) {
      if (!parse_int(argv[++i], &opt.idle_ms) || opt.idle_ms < 0) { usage(argv[0]); return 1; }
    } else if (strcmp(argv[i], "--no-header") == 0) {
      opt.csv_header = false;
    } else if (strcmp(argv[i], "--help") == 0) {
      usage(argv[0]);
      return 0;
    } else {
      usage(argv[0]);
      return 1;
    }
  }

  bench_worker_t *workers = (bench_worker_t *)xcalloc((size_t)opt.threads, sizeof(*workers));
  pthread_t *threads = (pthread_t *)xcalloc((size_t)opt.threads, sizeof(*threads));

  double start = monotonic_seconds();
  for (int i = 0; i < opt.threads; i++) {
    workers[i].seed = opt.seed + (uint64_t)i * UINT64_C(0x9e3779b97f4a7c15);
    workers[i].iterations = opt.iterations;
    workers[i].retain_slots = opt.retain_slots;
    workers[i].handoff_cap = opt.handoff_cap;
    workers[i].handoff = (bench_block_t *)xcalloc(opt.handoff_cap ? opt.handoff_cap : 1, sizeof(bench_block_t));

    if (pthread_create(&threads[i], NULL, worker_main, &workers[i]) != 0) {
      fprintf(stderr, "bench-allocator: failed to start worker %d\n", i);
      return 2;
    }
  }

  for (int i = 0; i < opt.threads; i++) {
    if (pthread_join(threads[i], NULL) != 0) {
      fprintf(stderr, "bench-allocator: failed to join worker %d\n", i);
      return 2;
    }
  }

  uint64_t cross_thread_frees = 0;
  uint64_t cross_thread_bytes = 0;
  uint64_t allocs = 0;
  uint64_t frees = 0;
  uint64_t bytes_allocated = 0;
  uint64_t bytes_freed = 0;
  uint64_t checksum = 0;

  for (int i = 0; i < opt.threads; i++) {
    for (size_t j = 0; j < workers[i].handoff_len; j++) {
      free(workers[i].handoff[j].ptr);
      cross_thread_frees++;
      cross_thread_bytes += workers[i].handoff[j].size;
    }
    free(workers[i].handoff);

    allocs += workers[i].allocs;
    frees += workers[i].frees;
    bytes_allocated += workers[i].bytes_allocated;
    bytes_freed += workers[i].bytes_freed;
    checksum ^= workers[i].checksum;
  }

  frees += cross_thread_frees;
  bytes_freed += cross_thread_bytes;

  sleep_ms(opt.idle_ms);
  double elapsed = monotonic_seconds() - start;
  uint64_t peak_rss = peak_rss_bytes();

  free(threads);
  free(workers);

  if (opt.csv_header) {
    puts("name,threads,iterations_per_thread,total_iterations,seconds,allocs,frees,bytes_allocated,bytes_freed,cross_thread_frees,cross_thread_bytes,peak_rss_bytes,checksum");
  }

  printf(
    "ant-shaped,%d,%zu,%zu,%.6f,%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 "\n",
    opt.threads,
    opt.iterations,
    opt.iterations * (size_t)opt.threads,
    elapsed,
    allocs,
    frees,
    bytes_allocated,
    bytes_freed,
    cross_thread_frees,
    cross_thread_bytes,
    peak_rss,
    checksum
  );

  return 0;
}
