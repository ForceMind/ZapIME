// SPDX-License-Identifier: GPL-3.0-only
import XCTest
import UIKit
@testable import ZapIME

private final class SyntheticProxy: NSObject, UITextDocumentProxy {
    var text = ""
    var documentIdentifier = UUID()
    var documentContextBeforeInput: String? { text }
    var documentContextAfterInput: String? { "" }
    var selectedText: String? { nil }
    var hasText: Bool { !text.isEmpty }
    var beforeEdit: (() -> Void)?
    var afterEdit: (() -> Void)?
    func insertText(_ value: String) { beforeEdit?(); text += value; afterEdit?() }
    func deleteBackward() { beforeEdit?(); if !text.isEmpty { text.removeLast() }; afterEdit?() }
    func adjustTextPosition(byCharacterOffset offset: Int) {}
    func setMarkedText(_ markedText: String, selectedRange: NSRange) {}
    func unmarkText() {}
}

private final class SyntheticKeyboard: KeyboardViewController {
    let proxy = SyntheticProxy()
    override var textDocumentProxy: UITextDocumentProxy { proxy }
}

final class KeyboardProxyTests: XCTestCase {
    @MainActor
    func testOwnDocumentNotificationsDoNotDropQueuedEnglishKeys() async throws {
        let keyboard = SyntheticKeyboard()
        keyboard.loadViewIfNeeded()
        keyboard.proxy.beforeEdit = { [weak keyboard] in keyboard?.textWillChange(nil) }
        let inserted = expectation(description: "both queued keys reached same document")
        keyboard.proxy.afterEdit = { [weak keyboard] in
            if keyboard?.proxy.text == "ab" { inserted.fulfill() }
        }
        func find(_ root: UIView, _ title: String) -> UIButton? {
            if let button = root as? UIButton, button.currentTitle == title { return button }
            for child in root.subviews { if let button = find(child, title) { return button } }
            return nil
        }
        for title in ["中/英", "a", "b"] {
            let button = try XCTUnwrap(find(keyboard.view, title))
            button.sendActions(for: .touchUpInside)
        }
        await fulfillment(of: [inserted], timeout: 30)
        XCTAssertEqual(keyboard.proxy.text, "ab")
        withExtendedLifetime(keyboard) {}
    }
}
