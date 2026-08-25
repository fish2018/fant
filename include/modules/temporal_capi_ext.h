#ifndef ANT_TEMPORAL_CAPI_EXT_H
#define ANT_TEMPORAL_CAPI_EXT_H

#ifndef _WIN32
#include "Provider.d.h"

/* Keep the public amalgamated header self-contained when the generated
 * temporal bindings are not present (for example, temporal-disabled builds). */
#ifndef TEMPORAL_RS_PROVIDER_TYPE_DEFINED
typedef struct Provider Provider;
#define TEMPORAL_RS_PROVIDER_TYPE_DEFINED 1
#endif

#ifdef __cplusplus
extern "C" {
#endif

Provider *temporal_rs_Provider_new_fs(void);

#ifdef __cplusplus
}
#endif
#endif

#endif
