// SPDX-License-Identifier: GPL-3.0-only
// IMK 生命周期和标识约束参照固定 MSIME 宿主；离线最小适配不载入上游云端业务。
#import <AppKit/AppKit.h>
#import <InputMethodKit/InputMethodKit.h>
#include "../common/zapime.h"

static NSDictionary *ZapRequest(uint64_t handle, NSDictionary *request) {
    NSData *bytes = [NSJSONSerialization dataWithJSONObject:request options:0 error:nil];
    ZapBuffer response = zapime_request(handle, (const uint8_t *)bytes.bytes, bytes.length);
    NSData *data = [NSData dataWithBytes:response.data length:response.len];
    zapime_buffer_free(response);
    id result = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [result isKindOfClass:NSDictionary.class] ? result : @{};
}

@interface ZapInputController : IMKInputController
@property uint64_t handle;
@property BOOL english;
@property(nonatomic, strong) NSDictionary *view;
@property(nonatomic, strong) NSPanel *panel;
@property(nonatomic, strong) NSTextField *label;
@property(nonatomic, strong) id activeClient;
@end

@implementation ZapInputController
- (void)activateServer:(id)sender {
    [super activateServer:sender];
    if (self.activeClient && self.activeClient != sender) [self.panel orderOut:nil];
    self.activeClient = sender;
    if (!self.handle) {
        NSString *root = [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/org.zapime.inputmethod"];
        NSDictionary *config = @{@"resources":[NSBundle.mainBundle.resourcePath stringByAppendingPathComponent:@"EngineResources"],
                                 @"data":[root stringByAppendingPathComponent:@"data"],
                                 @"cache":[root stringByAppendingPathComponent:@"cache"], @"private":@YES};
        NSData *bytes = [NSJSONSerialization dataWithJSONObject:config options:0 error:nil];
        self.handle = zapime_open((const uint8_t *)bytes.bytes, bytes.length);
    }
    self.view = ZapRequest(self.handle, @{@"op":@"reset"});
}
- (void)deactivateServer:(id)sender {
    // 迟到的失焦事件不得清理已经切换到的新客户端。
    if (sender != self.activeClient) return;
    ZapRequest(self.handle, @{@"op":@"reset"});
    [sender setMarkedText:@"" selectionRange:NSMakeRange(0,0) replacementRange:NSMakeRange(NSNotFound,NSNotFound)];
    [self.panel orderOut:nil];
    self.activeClient = nil;
    [super deactivateServer:sender];
}
- (NSUInteger)recognizedEvents:(id)sender { (void)sender; return NSEventMaskKeyDown; }
- (void)applyView:(NSDictionary *)view client:(id)sender {
    if (view[@"error"] || sender != self.activeClient) return;
    self.view = view;
    NSString *commit = [view[@"commit"] isKindOfClass:NSString.class] ? view[@"commit"] : nil;
    if (commit.length) [sender insertText:commit replacementRange:NSMakeRange(NSNotFound,NSNotFound)];
    NSString *preedit = [view[@"preedit"] isKindOfClass:NSString.class] ? view[@"preedit"] : @"";
    [sender setMarkedText:preedit selectionRange:NSMakeRange(preedit.length,0) replacementRange:NSMakeRange(NSNotFound,NSNotFound)];
    NSArray *rows = view[@"candidates"];
    if (!preedit.length || !rows.count) { [self.panel orderOut:nil]; return; }
    if (!self.panel) {
        self.panel = [[NSPanel alloc] initWithContentRect:NSMakeRect(0,0,560,48)
            styleMask:NSWindowStyleMaskBorderless | NSWindowStyleMaskNonactivatingPanel backing:NSBackingStoreBuffered defer:NO];
        self.panel.level = NSPopUpMenuWindowLevel;
        self.panel.hidesOnDeactivate = NO;
        self.panel.ignoresMouseEvents = YES;
        self.label = [NSTextField labelWithString:@""];
        self.label.frame = NSMakeRect(10,10,540,28);
        self.label.font = [NSFont systemFontOfSize:18];
        [self.panel.contentView addSubview:self.label];
    }
    NSMutableArray *labels = [NSMutableArray array];
    for (NSUInteger i=0; i<MIN(rows.count,9UL); ++i) [labels addObject:[NSString stringWithFormat:@"%lu %@",(unsigned long)i+1,rows[i]]];
    self.label.stringValue = [labels componentsJoinedByString:@"  "];
    NSRect caret = NSZeroRect;
    [sender attributesForCharacterIndex:0 lineHeightRectangle:&caret];
    NSScreen *screen = NSScreen.mainScreen;
    NSRect visible = screen.visibleFrame;
    CGFloat x = MAX(NSMinX(visible),MIN(caret.origin.x,NSMaxX(visible)-560));
    CGFloat y = MAX(NSMinY(visible),MIN(caret.origin.y-52,NSMaxY(visible)-48));
    [self.panel setFrameOrigin:NSMakePoint(x,y)];
    [self.panel orderFront:nil];
}
- (BOOL)handleEvent:(NSEvent *)event client:(id)sender {
    if (event.type != NSEventTypeKeyDown || sender != self.activeClient || !self.handle) return NO;
    NSEventModifierFlags modifiers = event.modifierFlags;
    if (modifiers & (NSEventModifierFlagCommand | NSEventModifierFlagControl | NSEventModifierFlagOption)) return NO;
    if (event.keyCode == 49 && (modifiers & NSEventModifierFlagShift)) {
        [self applyView:ZapRequest(self.handle,@{@"op":@"command",@"name":@"CommitRaw"}) client:sender];
        self.english = !self.english;
        return YES;
    }
    if (self.english) return NO;
    BOOL composing = [self.view[@"code"] length] > 0;
    NSString *text = event.characters;
    NSDictionary *request;
    if (composing && event.keyCode == 51) request = @{@"op":@"command",@"name":@"Backspace"};
    else if (composing && event.keyCode == 53) request = @{@"op":@"reset"};
    else if (composing && event.keyCode == 49) request = @{@"op":@"command",@"name":@"CommitCandidate"};
    else if (composing && event.keyCode == 36) request = @{@"op":@"command",@"name":@"CommitRaw"};
    else if (composing && text.length == 1 && [text characterAtIndex:0] >= '1' && [text characterAtIndex:0] <= '9') {
        NSUInteger index = [text characterAtIndex:0]-'1';
        if (index >= [self.view[@"candidates"] count]) return YES;
        request = @{@"op":@"select",@"index":@(index),@"generation":self.view[@"generation"]};
    } else if (text.length == 1 && [text characterAtIndex:0] >= 32 && [text characterAtIndex:0] < 127) {
        if (!composing && ![[NSCharacterSet letterCharacterSet] characterIsMember:[text characterAtIndex:0]]) return NO;
        request = @{@"op":@"key",@"text":text};
    } else {
        // 光标导航前先提交原编码，避免 marked text 留在错误的光标位置。
        if (composing) [self applyView:ZapRequest(self.handle,@{@"op":@"command",@"name":@"CommitRaw"}) client:sender];
        return NO;
    }
    NSDictionary *view = ZapRequest(self.handle,request);
    [self applyView:view client:sender];
    return [view[@"handled"] boolValue];
}
- (void)dealloc { zapime_close(self.handle); [self.panel orderOut:nil]; }
@end

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        NSString *name = NSBundle.mainBundle.infoDictionary[@"InputMethodConnectionName"];
        __attribute__((objc_precise_lifetime)) IMKServer *server = [[IMKServer alloc] initWithName:name bundleIdentifier:NSBundle.mainBundle.bundleIdentifier];
        if (!server || zapime_abi_version() != 1) return 1;
        [NSApp run];
    }
    return 0;
}
