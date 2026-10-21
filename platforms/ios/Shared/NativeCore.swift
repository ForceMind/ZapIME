// SPDX-License-Identifier: GPL-3.0-only
import Foundation

// 实例必须在同一线程创建、请求、销毁；跨线程 UI 通过下面的专用工作线程调用。
final class NativeCore {
    private var handle: UInt64 = 0
    init(resources: URL, root: URL) {
        let config: [String: Any] = ["resources": resources.path,
            "data": root.appendingPathComponent("data").path,
            "cache": root.appendingPathComponent("cache").path, "private": true]
        if zapime_abi_version() == 1, let bytes = try? JSONSerialization.data(withJSONObject: config) {
            handle = bytes.withUnsafeBytes { zapime_open($0.bindMemory(to: UInt8.self).baseAddress, bytes.count) }
        }
    }
    deinit { zapime_close(handle) }
    func request(_ object: [String: Any]) -> [String: Any] {
        guard handle != 0, let bytes = try? JSONSerialization.data(withJSONObject: object) else {
            return ["error": "session_unavailable"]
        }
        let response = bytes.withUnsafeBytes {
            zapime_request(handle, $0.bindMemory(to: UInt8.self).baseAddress, bytes.count)
        }
        defer { zapime_buffer_free(response) }
        guard let data = response.data else { return ["error": "empty_response"] }
        return (try? JSONSerialization.jsonObject(with: Data(bytes: data, count: response.len))) as? [String: Any] ?? ["error": "invalid_response"]
    }
}

final class CoreWorker {
    private final class State {
        let condition = NSCondition()
        var jobs: [([String: Any], ([String: Any]) -> Void)] = []
        var stopped = false
    }
    private let state: State
    private let thread: Thread
    init(resources: URL, root: URL) {
        let state = State()
        self.state = state
        thread = Thread {
            let core = NativeCore(resources: resources, root: root)
            while true {
                state.condition.lock()
                while state.jobs.isEmpty && !state.stopped { state.condition.wait() }
                if state.stopped { state.condition.unlock(); break }
                let (request, completion) = state.jobs.removeFirst()
                state.condition.unlock()
                let result = core.request(request)
                DispatchQueue.main.async { completion(result) }
            }
        }
        thread.name = "org.zapime.ios.input"
        thread.start()
    }
    func submit(_ object: [String: Any], completion: @escaping ([String: Any]) -> Void) {
        state.condition.lock()
        guard !state.stopped, state.jobs.count < 256 else {
            state.condition.unlock()
            DispatchQueue.main.async { completion(["error": "input_queue_full"]) }
            return
        }
        state.jobs.append((object, completion))
        state.condition.signal()
        state.condition.unlock()
    }
    deinit {
        state.condition.lock()
        state.stopped = true
        state.jobs.removeAll()
        state.condition.signal()
        state.condition.unlock()
    }
}
