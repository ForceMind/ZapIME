# VoiceKey 技术吸收与 ZapIME 多引擎语音方案

状态：架构决策与实施任务；**未表示 VoiceKey 代码已经集成或测试通过**。

## 目标
保持 ZapIME 原生输入法与默认本地离线语音独立可用；借鉴 J3n5en/voicekey 的多渠道识别、候选对比、实时出字、会话生命周期与离线引擎实现，建立可插拔适配器。参考：https://github.com/J3n5en/voicekey 。

## 已核对基线
- ZapIME Android 已实现 sherpa-onnx 1.13.8 + X-ASR-zh-en INT8 的模型导入、录音、识别预览、确认插入，模拟器验收通过；真人/三星真机尚未验证。见 docs/VOICE_INPUT.md。
- VoiceKey README 说明桌面端多渠道并行、实时/定稿候选、微信离线（macOS/Windows/Android）及 Apple Silicon Mac 豆包离线；iOS 为云端语音键盘；Android 为不替换输入法的辅助输入工具。
- VoiceKey Rust workspace 包含 core/platform/wtlocal/hanbao 等；本次根目录 LICENSE 请求返回 404，不能据此确认授权状态。

## 集成原则
1. **默认离线**：现有 Native 引擎作为必备基线，未选择云端时禁止上传音频、识别文字或上下文。
2. **可插拔识别引擎**：定义 VoiceRecognizer 接口（capabilities、prepare、start、push_pcm、partial、final、cancel、close），识别会话必须有唯一 session_id、输入框 epoch、取消令牌与明确超时。
3. **统一音频前端**：设备音频采集与重采样只做一次；按引擎支持的 PCM 格式分发有界帧，网络引擎需明确授权、连接状态和失败原因。
4. **统一结果仲裁**：partial 仅供预览；final 按渠道、时间、稳定性显示；多渠道模式允许显式选择。不得将一个渠道的 partial 当成另一个渠道的 final。
5. **上屏安全**：延续 ZapIME 当前确认插入、焦点/选区代次检查、隐私字段拒绝、最多一次 commitText；未来边说边上屏必须实现可撤销的组合文本事务，不能直接复用不受控的粘贴逻辑。
6. **许可隔离**：未经 VoiceKey 作者明确授权及第三方依赖/模型/接口许可审核，不复制其实现、二进制、提取出的模型或非公开协议/凭据。先基于公开行为与自有代码实现适配接口。VoiceKey 自身声明部分云接口通过分析第三方客户端协议实现，可能变更或失效，不能作为产品默认能力。
7. **平台策略**：Android 首先实现 Native 与可插拔适配器；桌面端复用 Rust 会话抽象；iOS 键盘扩展不能直接录音，须由主 App 录音并在平台允许的生命周期内接力，不承诺后台录音始终可用。

## 阶段任务与验收
### P0：Android Alpha（最先交付）
- 固定当前可下载 APK/模型与 SHA，三星 S25 实测安装、九键/全键、权限、冷/热启动、自然中文、长句、停顿、后台切换和焦点变化。
- 收集自愿提供的非敏感测试句：逐句 CER、首字/定稿延迟、峰值内存、耗电、崩溃；合成样本不得冒充真人成绩。
- UI 保留默认离线语音；确保完全断网可用。

### P1：VoiceKey 兼容式适配层
- 定义引擎 capability：offline/streaming/languages/network_required/model_size/platforms；统一事件协议与故障隔离。
- 实现 MockRecognizer、NativeRecognizer、并行仲裁器及自动化测试（取消、迟到结果、焦点切换、静音、网络失败、空结果、顺序错乱）。
- 在设置页提供“本地优先 / 多渠道对比（实验性）”；未实际集成并验证的渠道不得显示为可用。

### P2：经许可审查的可选引擎
- 向 VoiceKey 作者确认项目许可证、可复用模块、模型及第三方服务使用与再分发权限。
- 若授权允许，再以独立 feature/module 评估 wtlocal 的性能、ABI、模型来源和许可；所有下载必须显式同意并展示来源。
- 云端渠道仅在合法稳定的 API、用户授权、隐私告知及可撤销开关满足后接入；不硬编码逆向接口或提取凭据。

### P3：跨平台
- macOS/Windows 独立宿主和输入焦点验收；iOS 主 App + 键盘接力的真实设备验证。
- 对比 VoiceKey 体验，但不得直接宣称准确率、速度或稳定性等同。

## 完成定义
至少一个 Android 真机完整安装、自然语音识别并确认插入；断网、焦点/权限/取消测试通过；多引擎适配器的自动化测试通过；第三方模块仅在许可和安全审计通过后进入可分发包。

## 不在本次提交范围
本次只提交技术吸收与执行文档，不包含 VoiceKey 源码、二进制、模型、第三方协议实现，也不代表已完成适配器、真机测试或发布。

## 源码级核查补充（2026-11-05）
本轮已直接读取 VoiceKey 仓库下列源码文件（只读研究，没有复制到 ZapIME）：
- `Cargo.toml`：Rust workspace 1.9.0，包含 `crates/core`、`crates/platform`、`crates/hanbao`、`crates/wtlocal`、`app/src-tauri`。
- `crates/core/src/lib.rs`：`Engine` trait 定义 `run(Audio, Partial) -> Result<String>` 和可选 `prewarm()`；`Audio` 为无界 `tokio::sync::mpsc::UnboundedReceiver<Vec<i16>>`，20ms / 16kHz / 单声道 Int16（每帧320样本）。
- `crates/wtlocal/src/lib.rs`：`LocalEngine` 使用 `spawn_blocking`，缓存 `Arc<Model>`，按流追加音频、每30个特征帧且不早于上次计算耗时3倍时发 partial，结束时 `finish()`。
- `crates/wtlocal/Cargo.toml`：依赖 `memmap2`、`rayon`、`tokio` 等。
- 仓库根目录 `LICENSE` 查询返回 404；朋友口头允许“使用”是合作信号，但商业分发、修改、再授权以及模型和第三方接口许可仍需分别明确。

**具体整合建议**：
1. 先在 ZapIME 自有代码中实现有界音频队列的 `RecognizerAdapter`，不要照搬 VoiceKey 的无界队列；按 Android 既有 session/ticket/epoch 处理取消、结果过期和焦点变化。
2. 在测试夹具中加入 VoiceKey 式 `prewarm` 与 partial 自适应节流，测量首字延迟、CPU 峰值、内存与耗电；不能仅以离线模型大小判断速度。
3. 许可明确后，优先试验 `wtlocal` 作为独立可选引擎；模型文件必须由用户合法获取、显式导入，不默认随 APK 分发。
4. 将 VoiceKey 多渠道候选作为交互设计参考；云渠道须采用有授权的稳定接口，不直接依赖第三方非公开协议。
5. 对照测试同一批真人自愿录音的 CER、首字延迟、定稿延迟和峰值资源，三星 S25 必测；对比结果决定默认引擎，不预设 VoiceKey 一定更快。
