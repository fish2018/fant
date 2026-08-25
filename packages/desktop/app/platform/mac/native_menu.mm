#include "native_menu.h"

NSMutableArray *g_menu_targets;

@interface AntMenuItemTarget : NSObject
@property(nonatomic, assign) ant_t *js;
@property(nonatomic, assign) ant_value_t callback;
- (void)invoke:(id)sender;
@end

@implementation AntMenuItemTarget
- (void)invoke:(id)sender {
  ant_value_t result = ant_desktop_call_function(self.js, self.callback, js_mkundef(), NULL, 0);
  if (is_err(result)) { fprintf(stderr, "menu click handler failed: %s\n", js_str(self.js, result)); }
}
@end

NSEventModifierFlags AcceleratorModifiers(NSString *accelerator, NSString **key) {
  NSEventModifierFlags modifiers = 0;
  NSArray<NSString *> *parts = [accelerator componentsSeparatedByString:@"+"];
  *key = parts.lastObject.lowercaseString;
  for (NSString *part in parts) {
    NSString *value = part.lowercaseString;
    if ([value isEqualToString:@"cmd"] || [value isEqualToString:@"command"] || [value isEqualToString:@"cmdorctrl"] ||
        [value isEqualToString:@"commandorcontrol"]) {
      modifiers |= NSEventModifierFlagCommand;
    } else if ([value isEqualToString:@"ctrl"] || [value isEqualToString:@"control"]) {
      modifiers |= NSEventModifierFlagControl;
    } else if ([value isEqualToString:@"shift"]) {
      modifiers |= NSEventModifierFlagShift;
    } else if ([value isEqualToString:@"alt"] || [value isEqualToString:@"option"]) {
      modifiers |= NSEventModifierFlagOption;
    }
  }
  if ([*key isEqualToString:@"comma"]) *key = @",";
  if ([*key isEqualToString:@"plus"]) *key = @"+";
  if ([*key isEqualToString:@"space"]) *key = @" ";
  return modifiers;
}

SEL RoleAction(NSString *role, id *target) {
  *target = nil;
  if ([role isEqualToString:@"about"]) {
    *target = NSApp;
    return @selector(orderFrontStandardAboutPanel:);
  }
  if ([role isEqualToString:@"quit"]) {
    *target = NSApp;
    return @selector(terminate:);
  }
  if ([role isEqualToString:@"hide"]) {
    *target = NSApp;
    return @selector(hide:);
  }
  if ([role isEqualToString:@"hideothers"]) {
    *target = NSApp;
    return @selector(hideOtherApplications:);
  }
  if ([role isEqualToString:@"unhide"]) {
    *target = NSApp;
    return @selector(unhideAllApplications:);
  }
  if ([role isEqualToString:@"close"]) return @selector(performClose:);
  if ([role isEqualToString:@"minimize"]) return @selector(performMiniaturize:);
  if ([role isEqualToString:@"zoom"]) return @selector(performZoom:);
  if ([role isEqualToString:@"togglefullscreen"]) return @selector(toggleFullScreen:);
  if ([role isEqualToString:@"undo"]) return @selector(undo:);
  if ([role isEqualToString:@"redo"]) return @selector(redo:);
  if ([role isEqualToString:@"cut"]) return @selector(cut:);
  if ([role isEqualToString:@"copy"]) return @selector(copy:);
  if ([role isEqualToString:@"paste"]) return @selector(paste:);
  if ([role isEqualToString:@"selectall"]) return @selector(selectAll:);
  return nil;
}

NSMenu *BuildNativeMenu(ant_t *js, ant_value_t template_value, NSString *title) {
  NSMenu *menu = [[NSMenu alloc] initWithTitle:title ?: @""];
  if (!is_array_value(template_value)) return menu;
  ant_offset_t count = js_arr_len(js, template_value);
  for (ant_offset_t index = 0; index < count; index++) {
    ant_value_t definition = js_arr_get(js, template_value, index);
    if (!is_object_type(definition)) continue;
    NSString *type = OptionString(js, definition, "type");
    if ([type isEqualToString:@"separator"]) {
      [menu addItem:NSMenuItem.separatorItem];
      continue;
    }
    NSString *role = OptionString(js, definition, "role").lowercaseString;
    NSString *label = OptionString(js, definition, "label") ?: role ?: @"";
    id target = nil;
    SEL action = RoleAction(role, &target);
    ant_value_t callback = js_get(js, definition, "click");
    AntMenuItemTarget *callback_target = nil;
    if (is_callable(callback)) {
      callback_target = [AntMenuItemTarget new];
      callback_target.js = js;
      callback_target.callback = callback;
      [g_menu_targets addObject:callback_target];
      target = callback_target;
      action = @selector(invoke:);
    }
    NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:label action:action keyEquivalent:@""];
    item.target = target;
    item.enabled = OptionBool(js, definition, "enabled", YES);
    item.hidden = !OptionBool(js, definition, "visible", YES);
    item.state = OptionBool(js, definition, "checked", NO) ? NSControlStateValueOn : NSControlStateValueOff;
    NSString *accelerator = OptionString(js, definition, "accelerator");
    if (accelerator.length) {
      NSString *key = nil;
      item.keyEquivalentModifierMask = AcceleratorModifiers(accelerator, &key);
      item.keyEquivalent = key ?: @"";
    }
    ant_value_t submenu = js_get(js, definition, "submenu");
    if (is_object_type(submenu) && !is_array_value(submenu)) { submenu = js_get(js, submenu, "_template"); }
    if (is_array_value(submenu)) { item.submenu = BuildNativeMenu(js, submenu, label); }
    [menu addItem:item];
  }
  return menu;
}
