# Windows TSF 开发宿主

当前为 Windows 10+ x64 的最小原生 TSF 开发宿主，使用现有 Rust 输入核心；不是上游完整 Windows 功能的迁移结果。注册、焦点和编辑锁接口参考固定 MSIME，未载入其官方账号、社区、语音或云端进程。只注册独立 ZapIME 标识，不复用上游 CLSID、命名管道和资料目录。

## 构建与自动验证

需要 Windows x64、Visual Studio 2022+ C++ 桌面工具及 SDK、CMake 3.25+、Python 3、Rust 1.97.1 MSVC。在 PowerShell 执行：

```powershell
./scripts/build_windows.ps1
```

输出 `target/windows-native/Release/ZapIMETsf.dll` 和完整相邻资源/许可。CMake 下载固定 nlohmann/json v3.12.0 归档并核验 SHA-256，MIT 文本随包保留；Rust 和词库沿用来源锁。不要只复制 DLL 而遗漏离线词库和许可。

自动测试实际装载 TSF DLL、验证 COM 工厂、接口查询、无效入参、引用计数、服务器锁与未知 CLSID 隔离；随后调用 C ABI 验证打包词库 nihao→你好 和私密模式边界。测试不注册输入源、不操作真实用户注册表；**通过不表示已在记事本/浏览器中完成系统输入验收**。

## 手动开发安装

只有在 Windows CI 成功且准备进行本机验收时使用。把完整产物目录放到长期保留的本机目录；从当前用户的终端调用系统 64 位工具：

```powershell
regsvr32.exe "C:\完整路径\ZapIMETsf.dll"
```

按系统设置的语言和键盘列表添加闪电输入法。必要时退出登录再进入。该注册流程仅写本产品当前用户 COM 项及 TSF profile/category，不自动激活、不替换其他输入法；若系统拒绝 profile 注册，将报错并回滚，不得把注册工具退出当成已经可输入。

开发包未签名，未提供安装器，不是 ARM64/x86 支持承诺。使用相同 DLL 执行 `regsvr32.exe /u "C:\完整路径\ZapIMETsf.dll"` 注销；系统仍加载 DLL 时需要先切换其他键盘并退出登录才能移走文件。用户资料在 `%LOCALAPPDATA%\ZapIME`，卸载不删除资料。

## 当前行为与未验收边界

全拼字母、空格首候选、数字 1–9 选词、回车原编码、退格、Esc 取消、Shift+Space 中英切换。候选是最小非激活原生浮窗，暂无鼠标选择、翻页、设置和剪贴板 UI。默认不学习，不联网，不宣称支持安全桌面、游戏 UILess、触摸键盘集成或所有 TSF 客户端。

编辑操作只在 TSF 同步读写 edit session 内执行；未取得编辑锁时不吞键。焦点变化重置原生会话并清理候选，迟到编辑不能向新 context 提交；系统拒绝旧 context 清理时需要后续真实应用验收进一步校准。尚未完成真机输入、失焦组合、应用重启与安装回滚验收，不能据此将 M1 标为完成。

独立 CLSID `{D72EF286-C9D0-4AAE-B13A-18E84F59B765}`，Profile `{042DCDBD-B1DC-4A0D-931C-567221A172E8}`。注册只声明 keyboard category，不提前宣称尚未验证的 secure/comless/immersive/UIElement 能力。
