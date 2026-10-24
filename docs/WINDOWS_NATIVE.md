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

## 已证实阻塞与独立进程诊断（#41）

ee32f52 的 Windows CI 40038009396 实际编译成功，但重新创建的 COM 管理器在注册后返回 GetProfile E_FAIL；注销清理通过，整个系统探针失败。不能把注册 S_OK 算成持久化或激活成功。

新增同一 EXE 的 --query-only 子进程，在任何测试修改前仍执行 GitHub-hosted/run/attempt 守卫；该模式只创建系统查询接口，读取本产品固定 GUID 的 HKCU/HKLM COM/TIP 项存在性和 profile/category，不载入产品 DLL、不激活、不更改布局或文件。父进程用自己的绝对 EXE 路径创建子进程，继承日志句柄，等待最多15秒；超时终止只读子进程，父进程仍必须注销并验证清理。子进程缺少正确身份、enabled 或键盘类别时同样失败，不替代既有父进程门禁。常规本机 build/ctest 仍不运行系统探针。

仅记录 HRESULT、产品 GUID、存在性与数值会话/窗口站状态，不枚举用户/注册表、输出账户或路径值。开始前也拒绝已有本产品 HKLM COM 及 HKCU/HKLM TIP，避免接管已有全局注册。此候选未在本地 Windows 编译，结果以新 CI 为准。上游同样使用 RegisterProfile；COM作用域和调用顺序只是待验证假设，不擅自写 HKLM 或改默认输入法。

90cbbdc 的 Windows 40039850997 已实际编译及执行：session=2、visible_station=1、remote_session=0；子进程看到 HKCU COM、HKLM TIP 存在及键盘类别成员，但 GetProfile E_FAIL；新建父管理器同样失败，注销清理通过。整体仍失败，不能认定 headless 或无管理员权限是原因。这里也证实“COM 只写 HKCU”并不代表 TSF API 不写 HKLM：既有真实注册调用后本产品 HKLM TIP 项存在；新增诊断本身没有写入。

下一候选继续只读本产品完整 language/profile 子项、合并的 HKCR COM 存在性，以及旧接口的描述存在性、启用状态和可用语言中是否包含0804；不输出描述、注册路径值或其它用户文本。接口结果不代替原 GetProfile/category/激活门禁，生产注册仍未改，结果待新 CI。

## 中文用户语言的隔离CI夹具

dc457ed /40040289549 实际发现：本产品完整HKLM语言profile已存在，旧接口能读到描述与enabled，类别存在；但系统 GetLanguageList 只有0409，不含0804，新接口 GetProfile E_FAIL。这是实际环境差异，不提前认定全部根因。

新增 scripts/windows_profile_language_fixture.ps1 只在临时GitHub-hosted Windows且数字run/attempt通过时执行，守卫前不读取用户语言。复制原列表并在末尾追加zh-CN，保持原语言顺序；运行原EXE后finally恢复并逐项比对原语言/输入法/拼写/手写元数据。Python另用self-hosted环境实际验证PS脚本拒绝；总等待120秒，恢复不一致/超时/EXE失败均失败。只是当前用户语言列表，不安装语言包，不主动写HKLM COM或其它产品键、不修改生产注册或省略原身份/类别/激活门禁。原C++层仍只FORPROCESS激活并恢复原布局/profile，已有资料保护不变。

微软接口依据：
- https://learn.microsoft.com/en-us/powershell/module/international/set-winuserlanguagelist
- https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofiles-getlanguagelist

本次脚本尚无本地Windows执行证据；即使CI成功，仍不是正式安装器、语言包配置或记事本/浏览器上屏验收。

夹具接受请求的zh-CN、完整zh-Hans-CN及New-WinUserLanguageList返回的规范标签，避免在实际语言已存在时因别名字符串差异误报；仍要求恰好一个匹配项，原列表恢复逐项精确比较不放宽。此调整是代码复核发现的潜在别名边界，新CI验证，不虚构7099a24的运行结论。

## 实际运行布局的窗口前置条件

7099a24 /40040986359 两层守卫和语言恢复通过，但在标签检查失败，未进入C++；f38d6ce /40041268992 规范标签检查通过、C++已执行，用户列表有中文而TSF GetLanguageList仍只有英文，GetProfile继续E_FAIL，注册注销及语言恢复通过。两轮整体仍FAIL，精确选段均保留。

微软 LoadKeyboardLayout 文档明确Windows8+要求调用进程拥有键盘焦点窗口，且加载影响系统运行时布局，不能把它笼统称为只改当前线程。新增只属于本进程的测试窗口/空EDIT，普通SetForegroundWindow/SetFocus后严格核对实际焦点，最多2秒消息泵；系统拒绝焦点即失败，不AttachThreadInput、不强制前台或注入按键。把已有thread manager/原profile保存/中文布局加载与当前线程0804校验移到注册和新进程查询之前。

清理仍恢复原布局/profile并关闭自有窗口、恢复先前仍存在的前台窗口；只卸载本轮真正新加载的HKL。修正LoadKeyboardLayout回退为已有默认布局时不能标成新加载并卸载原布局的边界。不新增语言包下载或更改生产DLL，门禁仍须真实新进程查询/COM解析/FORPROCESS激活；结果待CI。

接口依据：https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-loadkeyboardlayoutw 。

## 独立进程的TSF运行时初始化（候选）

5b073ff /40041883760 实际已通过自有窗口焦点、thread manager启动、线程中文布局；注册后父进程GetProfile已S_OK。但原只读子进程没有初始化TSF线程，仍在英文且GetProfile E_FAIL，整体失败；旧profile/线程/注册/窗口和原用户语言清理均通过。不能删独立进程门禁或据此宣称激活/上屏。

保留--query-only原始纯查询基线，父进程的新子进程模式为--query-runtime：创建并Activate系统thread manager，记录原语言与HKL，只选择已经加载的0804 HKL，调用ChangeCurrentLanguage并精确确认当前语言后执行原身份/enabled/category查询。结束须还原原语言/HKL、Deactivate并释放所有接口；不注册、不加载新HKL、不改用户语言列表、不显式Activate本产品profile。Windows可能自动装载默认文本服务，因此不再把该新模式称为绝不加载产品DLL的纯只读进程。

父进程在起始已证明ZapIME数据目录不存在后即接管本轮临时目录的清理责任，覆盖系统隐式默认服务激活或后续失败；不会接管起始已有用户目录。仍仅受GitHub-hosted保护的临时runner使用，不影响常规build/ctest。新进程查询必须成功且清理通过才继续父进程原COM与FORPROCESS门禁。此候选尚待实际CI，不先判根因已解决。

接口依据：https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofiles-changecurrentlanguage （该接口要求调用线程已创建thread manager）。

## 系统COM解析与权限元数据（候选）

1546673 /40042421336 实际编译/守卫/窗口/中文HKL通过；独立子进程系统thread manager激活S_OK，但ChangeCurrentLanguage(0804)返回E_FAIL，profile查询仍失败；子进程及父进程旧语言/HKL/profile/注册/窗口、PowerShell语言列表恢复全部通过。整体仍失败，未实际显式激活产品或输入。

新增在注册后立即记录系统CoCreateInstance解析本产品的HRESULT，随后仍在原COM门禁要求S_OK，避免前面的子进程失败遮住关键解析证据。只多读当前进程TokenElevation布尔值以及固定系统策略EnableLUA的DWORD；不输出Token、SID、用户或凭据，不修改权限、UAC或HKLM COM。两种子进程模式和主流程仍先通过临时GitHub-hosted守卫才读取这些元数据。

微软HKCR文档明确管理员且UAC关闭时COM运行时忽略HKCU配置；这只是待核对条件，不提前认定当前故障根因，尤其HKCR合并项存在不等于系统COM可实际解析。依据：https://learn.microsoft.com/en-us/windows/win32/sysinfo/hkey-classes-root-key 。新候选仍待CI。

## 前台窗口与独立进程消息循环（候选）

3597ecf /40042896277 已实际验证系统CoCreateInstance解析本产品S_OK，TokenElevation=true且EnableLUA=1；微软“管理员且UAC关闭会忽略HKCU”的前提不成立，不能据此扩写HKLM COM。父进程profile查询S_OK，后台子进程ChangeCurrentLanguage仍E_FAIL，整体失败，旧状态清理全部通过。

本候选复用同一个OwnedFocusWindow实现，让独立子进程同样创建自己的空EDIT并核对真实前台/键盘焦点；普通焦点请求失败即拒绝，不强制前台、AttachThreadInput或发送输入。父进程等待子进程时以有界消息循环处理自身窗口消息，防止跨窗口焦点通知被阻塞；15秒期限不延长，超时/WM_QUIT仍失败。子进程关闭自己的窗口、还原父进程前台及原语言/HKL，父进程之后保留全部独立查询/COM/FORPROCESS/清理门禁。

这验证真实前台TSF上下文，不再把--query-runtime称作无窗口纯只读进程；--query-only仍保留原纯查询基线。产品注册源码未改，不安装语言组件或修改安全策略；结果待真实CI。

## 独立COM作用域对照（仅诊断，不能让原失败变绿）

9d54d2a /40043429539 已实际确认子进程自有窗口焦点和线程管理器启动，ChangeCurrentLanguage及恢复语言调用均E_FAIL，child_runtime_cleanup=FAIL；父进程窗口/注册/profile及PowerShell用户语言恢复通过。整体失败，不能把父清理PASS当成子清理也成功。

新增受原三层临时CI守卫保护的反事实对照：只有正常独立进程查询先失败，才在已确认本产品HKLM COM根不存在时创建自己的同CLSID项，写入同一已加载测试DLL路径/Apartment，再运行同一独立进程查询。RegCreateKeyEx必须返回新建根，才取得清理所有权；遇到原项或并发已有项拒绝接管。异常/结束删除本轮创建的HKLM根并严格确认不存在；清理失败仍失败。

原childVisible布尔值不被对照结果覆盖，原require继续执行；即使机器级对照成功，本产品当前HKCU注册失败的工作流也必须FAIL。不修改生产注册、不改UAC/默认输入法、不当成安装器或发布成功，只用于判断TSF完整安装识别是否与COM作用域有关。对照S_OK也不能直接证明生产安装安全或支持真实应用输入，后续若要改安装范围必须有受保护目录、升级回滚与真实系统验收。新结果待CI。

## 作用域对照结论与非破坏性环境清单

c230978 /40043998136 的临时HKLM COM创建S_OK，但独立子进程依然ChangeCurrentLanguage/GetProfile E_FAIL，原产品和对照child_visible都为0；试验HKLM根删除并确认不存在。单独机器COM范围没有解决现象，不能据此修改生产安装。子语言恢复API仍失败、child_cleanup FAIL，父注册/窗口/用户语言清理PASS，整体FAIL，精确选段已保存。

最新版移除自动机器COM对照，保留c230978提交与日志作为可重现证据，默认流程恢复为只写本产品HKCU COM及既有TSF API。新增只读中文Basic组件State和当前会话ctfmon进程数，不安装组件、不启动/终止服务；清单不可读明确known=0，不推测已安装或不存在。用户语言偏好设置成功不代表语言组件安装成功。

子进程清理改为先GetCurrentLanguage核对原LANGID；已经精确相同就记录实际无变化并省去多余切换请求，否则仍必须切回成功且再次读回精确原值。原GetProfile/身份/enabled/category/FORPROCESS门禁不放宽；这不能让失败产品路径变绿，也不回写旧失败为成功。新结果待CI。
