# Windows 原生验收检查点

更新时间：2026-10-24T00:28:28.296Z。当前结论：独立进程系统 profile 门禁未通过，Windows 尚未达到可安装并实际输入的验收标准。候选源码在 [PR #40](https://github.com/ForceMind/ZapIME/pull/40)，阻塞跟踪 [#41](https://github.com/ForceMind/ZapIME/issues/41)。本检查点只合入报告和证据，不合入失败候选。

## 最新实际运行

代码 c11b95d48c08be69239fd7fb6c55af2b8943e0df；Windows [40044595394](https://github.com/ForceMind/ZapIME/actions/runs/40044595394)，job120723288110，整体FAIL。生产 `platforms/windows/ZapIMETsf.cpp` 未在这组候选中修改。DLL/COM/打包词库原基线与新的原生探针均实际编译，Python、EXE、PowerShell 的受保护临时CI守卫和真实self-hosted拒绝反例已执行。

| 检查 | 实际结果 |
| --- | --- |
| 中文Basic组件 | 查询成功，NotPresent；没有调用语言包安装器 |
| 当前会话ctfmon | 1个进程；没有启停服务 |
| 用户语言列表 | 起始1项且无中文，临时添加成功，finally逐项恢复并验证PASS |
| 窗口/运行时 | 父子自有空EDIT实际焦点、thread manager启动；父线程0804布局准备PASS |
| 注册及系统COM | DllRegisterServer S_OK；系统CoCreateInstance解析S_OK；elevated=1且EnableLUA=1 |
| 持久数据 | 本产品HKCU COM及HKLM完整语言profile存在；旧接口描述非空、enabled=true、键盘category成员存在 |
| 独立子进程 | ChangeCurrentLanguage(0804) E_FAIL；GetCurrentLanguage为0；GetLanguageList仅1项且无0804；GetProfile E_FAIL |
| 父进程 | 新COM管理器GetProfile S_OK；这不替代独立进程门禁 |
| 子清理 | 读回LANGID已与原值相同后不重复切换；原HKL/thread manager/自有窗口恢复PASS |
| 父清理 | 旧布局/profile/thread manager、产品注册/类别/COM、自有窗口及临时资料清理PASS |
| 产品FORPROCESS激活 | 未进入 |
| 独立编辑器、记事本/浏览器上屏 | 未运行 |

精确任务选段：[最新日志](evidence/windows-profile-prerequisites-failure-40044595394.log)。完整原始产物仍在对应Actions，仓库保存带来源SHA/job/run的精确选段，不冒充已下载所有完整ZIP。

## 已做的对照

- 早期固定上游同样使用RegisterProfile/RegisterCategory。GetProfile.catid为空不能单独等同类别不存在，随后已直接验证真实category成员；没有为了通过而声明未实现的secure/comless/immersive能力。
- 新建管理器、独立进程、实际前台窗口、线程语言初始化和父进程消息循环均已真实验证；没有强制前台、AttachThreadInput、输入注入或伪造解码结果。
- c230978 / [40043998136](https://github.com/ForceMind/ZapIME/actions/runs/40043998136) 临时创建本产品HKLM COM对照成功，但原产品与对照独立查询均失败；试验根删除并确认不存在。原失败布尔值始终保留，即使对照成功也不能覆盖原产品失败。最新版已移除自动对照；该提交与[对照日志](evidence/windows-profile-machine-scope-counterfactual-40043998136.log)保留可追溯重现。
- 9d54d2a等旧轮次的子语言恢复API及child_cleanup确实FAIL，不能回写为成功。最新清理先读回真实原状态再决定是否切换，改善的是清理准确性；独立profile仍FAIL。
- 当前UAC开启且实际COM解析S_OK，不支持直接以“管理员/UAC关闭忽略HKCU”解释此次失败。单独机器COM镜像也没有解决问题，不据此扩写生产注册。

上述证据没有确认唯一根因。Basic未安装、用户语言偏好和实际TSF语言列表的差异是已观察环境事实，不能直接宣称安装Basic就必定修复，也不能认定所有平台问题都由CI造成。

## 接续验收

1. 在具备明确隔离边界、已配置中文语言组件与新会话的Windows桌面复验同一接口，比较独立进程LANGID/语言列表/profile。保持现有临时CI脚本守卫，不在个人机器伪装GitHub-hosted；手工只读查询与破坏性注册/清理入口应分开。
2. 新进程身份/enabled/category、精确COM路径、仅FORPROCESS激活及全部还原须真正通过，再测试真实TSF编辑锁、组字、提交、失焦及独立App正文。
3. 任何正式安装范围变化必须单独评估受保护安装目录、升级/回滚及已装资料，不能把诊断对照当成发布安装器。

本地执行环境在本轮中断，后续源码通过GitHub保存并由真实CI编译执行；没有新的本地MSVC、真机、签名或用户服务器部署结论。Android已合并结果独立于上述Windows失败，见[项目状态](STATUS.md)。
