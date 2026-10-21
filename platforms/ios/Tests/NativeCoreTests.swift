// SPDX-License-Identifier: GPL-3.0-only
import XCTest
@testable import ZapIME

final class NativeCoreTests: XCTestCase {
    func testBundledOfflinePinyinAndPrivateBoundary() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let resources = try XCTUnwrap(Bundle.main.url(forResource: "EngineResources", withExtension: nil))
        let core = NativeCore(resources: resources, root: root)
        var result: [String: Any] = [:]
        for letter in "nihao" { result = core.request(["op": "key", "text": String(letter)]) }
        let rows = try XCTUnwrap(result["candidates"] as? [String])
        let index = try XCTUnwrap(rows.firstIndex(of: "你好"))
        let commit = core.request(["op": "select", "index": index, "generation": try XCTUnwrap(result["generation"])])
        XCTAssertEqual(commit["commit"] as? String, "你好")
        XCTAssertEqual(commit["preedit"] as? String, "")
        XCTAssertEqual(core.request(["op": "personal_export"])["error"] as? String, "private_session")
        _ = core.request(["op": "key", "text": "a"])
        XCTAssertEqual(core.request(["op": "command", "name": "CommitRaw"])["commit"] as? String, "a")
        _ = core.request(["op": "reset"])
        XCTAssertEqual(core.request(["op": "select", "index": index, "generation": try XCTUnwrap(result["generation"])])["error"] as? String, "stale_generation")
    }
    func testWorkerKeepsNativeSessionOnOneThread() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let resources = try XCTUnwrap(Bundle.main.url(forResource: "EngineResources", withExtension: nil))
        let worker = CoreWorker(resources: resources, root: root)
        let committed = expectation(description: "serialized keyboard input")
        for letter in "nihao" {
            worker.submit(["op": "key", "text": String(letter)]) { result in
                XCTAssertTrue(Thread.isMainThread)
                XCTAssertNil(result["error"])
            }
        }
        worker.submit(["op": "command", "name": "CommitCandidate"]) { result in
            XCTAssertEqual(result["commit"] as? String, "你好")
            committed.fulfill()
        }
        wait(for: [committed], timeout: 30)
        withExtendedLifetime(worker) {}
    }
}
