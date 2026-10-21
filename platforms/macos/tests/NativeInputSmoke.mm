// SPDX-License-Identifier: GPL-3.0-only
// Ephemeral CI only: real TIS/InputMethodKit routing into our own Cocoa editor.
#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#include <vector>

static void Pump(NSTimeInterval seconds) {
    NSDate *end = [NSDate dateWithTimeIntervalSinceNow:seconds];
    while (end.timeIntervalSinceNow > 0) {
        NSEvent *event = [NSApp nextEventMatchingMask:NSEventMaskAny
            untilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]
            inMode:NSDefaultRunLoopMode dequeue:YES];
        if (event) [NSApp sendEvent:event];
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
}

static void Key(NSWindow *window, unsigned short code, NSString *text,
                NSEventModifierFlags modifiers = 0) {
    NSEvent *event = [NSEvent keyEventWithType:NSEventTypeKeyDown location:NSZeroPoint
        modifierFlags:modifiers timestamp:NSProcessInfo.processInfo.systemUptime
        windowNumber:window.windowNumber context:nil characters:text
        charactersIgnoringModifiers:text isARepeat:NO keyCode:code];
    // No global event injection or Accessibility permission: dispatch to this app only.
    [NSApp postEvent:event atStart:NO];
    Pump(0.3);
}

static BOOL Expect(NSTextView *editor, NSString *expected, const char *phase) {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (![editor.string isEqualToString:expected] && deadline.timeIntervalSinceNow > 0) Pump(0.1);
    BOOL ok = [editor.string isEqualToString:expected] && !editor.hasMarkedText;
    fprintf(stderr, "%s phase=%s length=%lu marked=%d\n", ok ? "PASS" : "FAIL",
        phase, (unsigned long)editor.string.length, editor.hasMarkedText);
    return ok;
}

struct SourceScope {
    TISInputSourceRef previous = TISCopyCurrentKeyboardInputSource();
    TISInputSourceRef source = nullptr;
    std::vector<TISInputSourceRef> enabled;
    ~SourceScope() {
        if (previous) { TISSelectInputSource(previous); CFRelease(previous); }
        for (auto it = enabled.rbegin(); it != enabled.rend(); ++it) {
            TISDisableInputSource(*it); CFRelease(*it);
        }
        if (source) CFRelease(source);
    }
};

static int Run(NSString *appPath) {
    SourceScope selection;
    NSURL *url = [NSURL fileURLWithPath:appPath isDirectory:YES];
    OSStatus status = TISRegisterInputSource((__bridge CFURLRef)url);
    fprintf(stderr, "TIS register status=%d\n", (int)status);
    if (status != noErr) return 1;
    NSString *identifier = @"org.zapime.inputmethod.ZapIME.Hans";
    NSDictionary *filter = @{(__bridge NSString *)kTISPropertyInputSourceID:identifier};
    CFArrayRef sources = TISCreateInputSourceList((__bridge CFDictionaryRef)filter, true);
    if (sources && CFArrayGetCount(sources) > 0)
        selection.source = (TISInputSourceRef)CFRetain(CFArrayGetValueAtIndex(sources, 0));
    if (sources) CFRelease(sources);
    if (!selection.source) {
        fprintf(stderr, "BLOCKED: registered input source absent from TIS list\n");
        return 78;
    }
    // A keyboard mode also needs its containing input method enabled.
    NSDictionary *bundleFilter = @{(__bridge NSString *)kTISPropertyBundleID:@"org.zapime.inputmethod.ZapIME"};
    CFArrayRef related = TISCreateInputSourceList((__bridge CFDictionaryRef)bundleFilter, true);
    for (CFIndex i=0; related && i<CFArrayGetCount(related); ++i) {
        auto item = (TISInputSourceRef)CFArrayGetValueAtIndex(related,i);
        CFTypeRef name = TISGetInputSourceProperty(item,kTISPropertyInputSourceID);
        BOOL capable = TISGetInputSourceProperty(item,kTISPropertyInputSourceIsEnableCapable) == kCFBooleanTrue;
        BOOL enabled = TISGetInputSourceProperty(item,kTISPropertyInputSourceIsEnabled) == kCFBooleanTrue;
        BOOL selectable = TISGetInputSourceProperty(item,kTISPropertyInputSourceIsSelectCapable) == kCFBooleanTrue;
        fprintf(stderr,"TIS source=%s enableCapable=%d enabled=%d selectable=%d\n",
            [(__bridge NSString *)name UTF8String],capable,enabled,selectable);
        if (capable && !enabled) {
            status = TISEnableInputSource(item);
            fprintf(stderr,"TIS enable status=%d\n",(int)status);
            if (status == noErr) selection.enabled.push_back((TISInputSourceRef)CFRetain(item));
        }
    }
    if (related) CFRelease(related);
    NSWorkspaceOpenConfiguration *launch = [NSWorkspaceOpenConfiguration configuration];
    launch.activates = NO;
    __block BOOL launched = NO;
    __block BOOL launchFailed = NO;
    [NSWorkspace.sharedWorkspace openApplicationAtURL:url configuration:launch
        completionHandler:^(NSRunningApplication *app, NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                launchFailed = app == nil || error != nil;
                fprintf(stderr,"IMK launch success=%d error=%ld\n",!launchFailed,(long)error.code);
                launched = YES;
            });
        }];
    NSDate *launchDeadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (!launched && launchDeadline.timeIntervalSinceNow > 0) Pump(0.1);
    if (!launched || launchFailed) return 1;
    Pump(1);

    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(100,100,640,320)
        styleMask:NSWindowStyleMaskTitled backing:NSBackingStoreBuffered defer:NO];
    window.title = @"ZapIME synthetic input probe";
    window.releasedWhenClosed = NO;
    NSTextView *editor = [[NSTextView alloc] initWithFrame:window.contentView.bounds];
    editor.richText = NO;
    [window.contentView addSubview:editor];
    [window makeKeyAndOrderFront:nil];
    [window makeFirstResponder:editor];
    [NSApp activateIgnoringOtherApps:YES];
    Pump(1);
    if (!NSApp.isActive || !window.isKeyWindow || window.firstResponder != editor) {
        fprintf(stderr, "BLOCKED: runner has no active GUI editor session\n");
        return 78;
    }
    for (int attempt=0; attempt<5; ++attempt) {
        status = TISSelectInputSource(selection.source);
        fprintf(stderr,"TIS select attempt=%d status=%d\n",attempt+1,(int)status);
        if (status == noErr) break;
        Pump(1);
    }
    if (status != noErr) return 1;
    [editor.inputContext activate];
    editor.inputContext.selectedKeyboardInputSource = identifier;
    Pump(2);
    TISInputSourceRef current = TISCopyCurrentKeyboardInputSource();
    BOOL selected = current && CFEqual(TISGetInputSourceProperty(current, kTISPropertyInputSourceID),
                                       (__bridge CFStringRef)identifier);
    if (current) CFRelease(current);
    if (!selected) {
        fprintf(stderr, "BLOCKED: TIS did not retain ZapIME selection\n");
        return 78;
    }
    Key(window,45,@"n"); Key(window,34,@"i"); Key(window,4,@"h");
    Key(window,0,@"a"); Key(window,31,@"o"); Key(window,49,@" ");
    if (!Expect(editor,@"你好","pinyin-commit")) return 1;
    Key(window,49,@" ",NSEventModifierFlagShift);
    Key(window,12,@"q"); Key(window,13,@"w"); Key(window,14,@"e");
    if (!Expect(editor,@"你好qwe","english-passthrough")) return 1;
    Key(window,51,@"\x7f");
    if (!Expect(editor,@"你好qw","editor-backspace")) return 1;
    fprintf(stderr, "PASS real TIS/InputMethodKit Cocoa text insertion (synthetic events)\n");
    [window close];
    return 0;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 2 || ![NSProcessInfo.processInfo.environment[@"GITHUB_ACTIONS"] isEqualToString:@"true"])
            return 78;
        [NSApplication sharedApplication];
        [NSApp finishLaunching];
        return Run([NSString stringWithUTF8String:argv[1]]);
    }
}
