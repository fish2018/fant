#ifndef ANT_DESKTOP_MAC_EVENT_LOOP_H
#define ANT_DESKTOP_MAC_EVENT_LOOP_H

#import <Foundation/Foundation.h>
#include <ant.h>

@interface AntRuntimePump : NSObject
- (instancetype)initWithRuntime:(ant_t *)js;
- (void)pump;
@end

#endif
