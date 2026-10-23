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

## 临时CI的系统profile探针（候选，结果待验收）

新增独立 `ZapIMEWindowsProfileProbe.exe` 与 `scripts/windows_profile_smoke.py`，常规本机build/ctest不执行它。只有临时GitHub-hosted Windows runner且run/attempt为数字时，Python及EXE才允许执行；CI先用self-hosted环境验证EXE拒绝，再做实际探针。已有ZapIME当前用户COM/profile或LocalAppData目录时拒绝接管，不在个人Windows或自托管runner测试。

探针调用真实DLL的注册导出，核对TSF profile身份/category/enabled及HKCU的精确DLL路径，并由系统COM注册解析创建processor。调用自身TSF thread manager，在当前线程匹配0804语言后，仅以FORPROCESS激活，要求S_OK以及GetActiveProfile精确返回本产品active状态；不使用FORSESSION、修改默认输入法或DONTCARECURRENTINPUTLANGUAGE的延迟激活来冒充成功。系统没有对应布局/语言或激活失败时报告原始HRESULT并明确失败，不自动下载语言包。

正常及异常路径尝试恢复先前线程布局/profile、停thread manager、注销本产品并复查profile/COM消失，卸载本次新加载的布局并只删除本次创建的资料目录。清理失败或90秒超时同样失败；超时不宣称清理已完成。原始结果保存到产物profile-probe.log，守卫反例为profile-probe-guard.log。

当前本地只有Linux：Python编译与实际拒绝执行通过；没有Windows SDK/MSVC，没有本地编译或注册/激活成功结果。真实Windows CI待跑，即使成功仍不是记事本/浏览器、真实TSF编辑锁与系统上屏验收。后续在独立原生编辑器完成输入闭环。

接口依据：
- https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofilemgr-activateprofile
- https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofilemgr-getprofile
- https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofilemgr-getactiveprofile
- https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofilemgr-deactivateprofile

首轮40033265710和字段诊断40035305480均实际编译/注册成功但整体失败。后者返回type1/lang0804/正确CLSID及profile/flags0x2，catid却为GUID_NULL；启用或身份错误不是该轮已证实根因。现在保留字段原文，通过ITfCategoryMgr.EnumItemsInCategory对键盘类别直接检查本产品GUID的成员资格：注册前不存在、注册后必须存在、注销后必须消失；不能仅凭未填catid判定不存在，也不会跳过真实类别断言。新方式的执行仍待CI，不能预记注册/激活全通过。接口依据：https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfcategorymgr-enumitemsincategory 。

后续a78ba70的专用EnumItemsInCategory也未找到本产品，不能据此前一轮catid为零就认定只是未填字段。新增候选在注册/注销后释放并重新创建profile/category COM管理器再执行相同检查，以区分对象寿命或缓存与实际持久化；这仍是待验收假设，不改生产注册或忽略成员断言。
