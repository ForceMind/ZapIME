// SPDX-License-Identifier: GPL-3.0-only
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        let layout = UIStackView(); layout.axis = .vertical; layout.spacing = 20
        layout.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(layout)
        NSLayoutConstraint.activate([
            layout.leadingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            layout.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            layout.topAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.topAnchor, constant: 30)
        ])
        let title = UILabel(); title.text = "ZapIME · 闪电输入法"; title.font = .boldSystemFont(ofSize: 26)
        let help = UILabel(); help.numberOfLines = 0
        help.text = "开发预览\n\n在系统设置 → 通用 → 键盘 → 键盘 → 添加新键盘中启用闪电输入法。\n\n本版本离线输入，不要求完全访问，不联网、不学习。个人资料管理和同步尚未接入。安全输入框由系统键盘处理。"
        let input = UITextView(); input.font = .systemFont(ofSize: 22)
        input.backgroundColor = .secondarySystemBackground; input.accessibilityIdentifier = "zapime.test-input"
        input.heightAnchor.constraint(equalToConstant: 180).isActive = true
        layout.addArrangedSubview(title); layout.addArrangedSubview(help); layout.addArrangedSubview(input)
        window = UIWindow(frame: UIScreen.main.bounds); window?.rootViewController = controller; window?.makeKeyAndVisible()
        return true
    }
}
