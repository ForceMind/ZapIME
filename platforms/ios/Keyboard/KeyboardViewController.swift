// SPDX-License-Identifier: GPL-3.0-only
import UIKit

class KeyboardViewController: UIInputViewController {
    private var worker: CoreWorker?
    private var epoch: UInt64 = 0
    private var english = false
    private var applyingOwnEdit = false
    private var snapshot: [String: Any] = [:]
    private let status = UILabel()
    private let candidateRow = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .secondarySystemBackground
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("org.zapime.ios.keyboard")
        if let resources = Bundle.main.url(forResource: "EngineResources", withExtension: nil) {
            worker = CoreWorker(resources: resources, root: root)
        }
        let layout = UIStackView()
        layout.axis = .vertical; layout.spacing = 5
        layout.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(layout)
        NSLayoutConstraint.activate([
            layout.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 6),
            layout.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            layout.topAnchor.constraint(equalTo: view.topAnchor, constant: 5),
            layout.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -5)
        ])
        let height = view.heightAnchor.constraint(equalToConstant: 280)
        height.priority = .defaultHigh; height.isActive = true
        status.text = "闪电输入法 · 离线 · 不学习"; status.font = .systemFont(ofSize: 12)
        status.accessibilityIdentifier = "zapime.status"
        layout.addArrangedSubview(status)
        candidateRow.axis = .horizontal; candidateRow.spacing = 6
        let scroll = UIScrollView(); scroll.addSubview(candidateRow)
        candidateRow.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            candidateRow.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            candidateRow.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            candidateRow.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            candidateRow.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            candidateRow.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
            scroll.heightAnchor.constraint(equalToConstant: 40)
        ])
        layout.addArrangedSubview(scroll)
        for letters in ["qwertyuiop", "asdfghjkl", "zxcvbnm"] {
            let row = UIStackView(); row.distribution = .fillEqually; row.spacing = 4
            for c in letters { row.addArrangedSubview(button(String(c), action: #selector(letter(_:)))) }
            layout.addArrangedSubview(row)
        }
        let actions = UIStackView(); actions.distribution = .fillEqually; actions.spacing = 4
        for (title, selector) in [("🌐", #selector(nextKeyboard)), ("中/英", #selector(toggleMode)),
                                   ("，", #selector(letter(_:))), ("空格", #selector(space)),
                                   ("⌫", #selector(backspace)), ("换行", #selector(enter))] {
            actions.addArrangedSubview(button(title, action: selector))
        }
        layout.addArrangedSubview(actions)
    }
    private func button(_ title: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal); button.titleLabel?.font = .systemFont(ofSize: 20)
        button.backgroundColor = .tertiarySystemBackground; button.layer.cornerRadius = 6
        button.addTarget(self, action: action, for: .touchUpInside)
        button.accessibilityIdentifier = "zapime.key.\(title)"
        return button
    }
    private func send(_ object: [String: Any], fallback: String? = nil, literal: String? = nil) {
        let current = epoch
        let document = textDocumentProxy.documentIdentifier
        guard let worker else { status.text = "离线词库不可用，请重新安装开发包"; return }
        worker.submit(object) { [weak self] result in
            guard let self, current == self.epoch else { return }
            guard self.textDocumentProxy.documentIdentifier == document else { self.invalidate(); return }
            if result["error"] != nil { self.status.text = "输入核心不可用，可切换到系统键盘"; return }
            self.applyingOwnEdit = true
            defer { self.applyingOwnEdit = false }
            if let commit = result["commit"] as? String { self.textDocumentProxy.insertText(commit) }
            if let literal { self.textDocumentProxy.insertText(literal) }
            if let fallback, result["handled"] as? Bool == false { self.textDocumentProxy.insertText(fallback) }
            guard self.textDocumentProxy.documentIdentifier == document else { self.invalidate(); return }
            self.render(result)
        }
    }
    private func render(_ result: [String: Any]) {
        snapshot = result
        status.text = (result["preedit"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (english ? "English · 离线" : "闪电输入法 · 离线 · 不学习")
        candidateRow.arrangedSubviews.forEach { candidateRow.removeArrangedSubview($0); $0.removeFromSuperview() }
        for (index, text) in ((result["candidates"] as? [String]) ?? []).prefix(20).enumerated() {
            let item = button(text, action: #selector(selectCandidate(_:)))
            item.tag = index; candidateRow.addArrangedSubview(item)
        }
    }
    @objc private func letter(_ sender: UIButton) {
        let text = sender.currentTitle ?? ""
        if english {
            send(["op": "command", "name": "CommitRaw"], literal: text == "，" ? "," : text)
        } else { send(["op": "key", "text": text == "，" ? "," : text]) }
    }
    @objc private func space() { send(["op": "command", "name": "CommitCandidate"], fallback: " ") }
    @objc private func enter() { send(["op": "command", "name": "CommitRaw"], fallback: "\n") }
    @objc private func backspace() {
        let current = epoch
        let document = textDocumentProxy.documentIdentifier
        worker?.submit(["op": "command", "name": "Backspace"]) { [weak self] result in
            guard let self, self.epoch == current, result["error"] == nil else { return }
            guard self.textDocumentProxy.documentIdentifier == document else { self.invalidate(); return }
            self.applyingOwnEdit = true
            defer { self.applyingOwnEdit = false }
            if result["handled"] as? Bool == false { self.textDocumentProxy.deleteBackward() }
            guard self.textDocumentProxy.documentIdentifier == document else { self.invalidate(); return }
            self.render(result)
        }
    }
    @objc private func toggleMode() {
        english.toggle(); send(["op": "command", "name": "CommitRaw"])
    }
    @objc private func nextKeyboard() { invalidate(); advanceToNextInputMode() }
    @objc private func selectCandidate(_ sender: UIButton) {
        guard let generation = snapshot["generation"] else { return }
        send(["op": "select", "index": sender.tag, "generation": generation])
    }
    private func invalidate() {
        epoch &+= 1; snapshot = [:]
        worker?.submit(["op": "reset"]) { _ in }
        candidateRow.arrangedSubviews.forEach { candidateRow.removeArrangedSubview($0); $0.removeFromSuperview() }
    }
    override func textWillChange(_ textInput: UITextInput?) {
        super.textWillChange(textInput)
        // 自己上屏/退格引发的同步通知不能丢掉后续已经排队的按键。
        if !applyingOwnEdit { invalidate() }
    }
    override func viewWillDisappear(_ animated: Bool) { invalidate(); super.viewWillDisappear(animated) }
}
