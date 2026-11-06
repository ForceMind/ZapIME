# ZapIME 项目执行状态

更新时间：2026-11-06 04:42 PST（America/Los_Angeles）。本轮已经实际修改产品、运行测试、推送提交和下载核验APK；累计汇报次数/此前间隔没有可靠连续记录，不补造。

## 当前交付结论

源码现在可以交本地 Codex 接续；**Android 双离线引擎 Alpha 尚未通过专项验收**。X-ASR 默认，VoiceKey 可选。先完成同一键盘双模型独立、离线录音预览/停止/取消/重录/确认的闭环，再决定推荐引擎。四端、个人数据、独立E2EE/R2及英语伴学长期目标保留。

PUBLIC 仓库 ForceMind/ZapIME；分支 `feat/voicekey-engine-import`，复用 PR #71 / Issue #72。main最新核验 `5621ab0e81780ca58c6cf74e97c93928a10f981a`。未强推、未合并、未改可见性、未发布稳定版，未操作用户电脑、手机、服务器或DNS。

## 最新专项事实（程序源码08e8498）

focused run42221304313 的API28首次完整SUCCESS：实际下载报告校验14主案例、23条Editable、真实30秒Android Handler/原生空闲worker退出、5条JNI参数边界、独立producer及实际系统IME全通过。生产与接受均125861样本，首非空partial1401ms，producer EOF到final1656ms；仅该合成fixture/模拟器，不是S25或自然人声。

API35/36仍在SAF provider选择阶段失败，尚未进入该轮识别；不是新的背压失败。新测试驱动显式构造FINGER pointer（原simple MotionEvent默认UNKNOWN），保持单次选择/稳定几何/provider真正返回/固定模型全部断言。依据AOSP来源提出的兼容性解释仍待新的现代CI实证，不修改程序源码。

08热修产品ARM/x86实际下载、源码/32许可/8JNI/16KiB/签名/DEX参数契约全部核验；full run42221304627的artifact13503554301，具体APK SHA与开发证书见full-08e84980证据。当前测试修改不改变程序源码，最终试用说明待现代专项及同源完整回归结果补全。

## 实际保存的实现

- `20f68dcc`：独立官方模型来源审计。
- `5bbfe310`：按所选引擎独立检查模型；Rust真实阻塞worker/取消/缓存/PCM/partial；固定SHA模型导入；锁依赖/许可/JNI/16KiB硬门禁。
- `c7b98ebb`：结果回调、确认点击、异步核对结束三处权限复查；23条真实Editable事务用例（3条权限为ContextWrapper模拟，非OS撤权）；固定Rust组件安装。
- `767ead79`：真实失败原件与精确JNI/样本/partial诊断；SAF可见文件真实选择。
- `45d9057b`：独立VoiceKey-only专项CI；专用测试APK只带固定合成PCM，连X-ASR模型也不带。完整Android回归继续单独运行。

固定上游：VoiceKey `3ba017826eef7fac405198510b03859eb5b6c8c4`；MSIME `97d652a49cabb0887355b84186af2aa9032d3814`。未修改vendor；不编译非公开云协议或第三方凭据。

## 已真实取得的运行与产物

| 范围 | 已核验结果与限制 |
| --- | --- |
| 原生ARM/x86适配器 | run42211989370两端各20测试、数值极值/尾维度对照、严格fmt/Clippy/locked及微内核诊断PASS。不是手机/端到端性能 |
| 真实固定模型CPU | run42213679417原生ARM/x86、生产Java导入、断网Rust推理、空/零/±1及取消回收PASS；实际下载metadata尺寸/SHA匹配，无权重/音频回传 |
| 固定合成音质量 | 同一125490样本PCM、4线程，两架构六轮输出一致；speech-v1 CER=15/22≈68.18%，全部错误保留。重复不算新增语料，不外推自然人声准确率，不据此推荐VoiceKey |
| 官方模型信任锚 | run42209579465官方来源审计、原包102920639B/SHA256 b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863及成员白名单固定。不是模型再分发许可 |
| 原有完整Android回归 | 5bbfe run42211989441、c7 run42213679453分别API28/35/36原输入/X-ASR/资料/隐私/备份/退出PASS；两工作流的VoiceKey专项失败仍保留，不能把整体称全绿 |
| c7真实Editable/JNI报告 | 实际提取JSON：23条事务、3条模拟权限状态、5条无模型VoiceKey JNI边界PASS。实际已解码文字来自X-ASR，不是第二引擎 |
| c7双ABI产品包 | artifact13500587615实际ZIP234980271B/SHA256 e891aab11d24f659309d591bb2bf23bb3a16e0deb89a5480cc5848c6f5325695；两APK源码锁、32包独立许可、8JNI、全部5库16KiB、manifest、实际签名/zipalign复核PASS。尚未包含下面背压修补 |
| 45专项输入包 | run42215806243实际四输入ZIP/三APK同签/模型排除核验PASS；harness PCM与同包WAV帧完全一致。仅证明构建输入，不证明专项通过 |

## 已定位失败及本切片修补

原始失败在 `first-model-42211989441`、`second-model-42213679453`、`focused-model-42215806243` 完整保留，并记录下载ZIP/成员/作业日志SHA与源码。

1. API35/36精确JNI错误为 `voicekey_audio_backpressure`，分别在68800/62400样本处失败；API35已有非空partial（1907ms），所以不是缺模型或完全不出字。8帧内部队列遇到计算峰值，原JNI立即取消。
2. 本切片保持队列容量，只有Full时在专用voice worker有界等待最多5秒；以最多10ms的等待片段轮询取消（不代表硬实时调度），成功入队才计样本/非零证据，不静默丢帧。timeout仍明确失败/取消，物理线程退出前不释放解码槽。
3. 停止时间戳幂等，缓冲排空与final共享30秒预算，JNI finish接收剩余毫秒；main Handler独立watchdog取消，超时结果无法供确认。它是协作取消，不是物理强杀；每次accept仍有5秒独立上限，不宣称主Looper调度具备硬实时保证。
4. 专项改用生产VoicePcmQueue和独立单调时钟PCM producer（首帧100ms），要求125490+371样本全部生产/接受，记录生产时长/排空/final。不会靠同步accept阻塞拉慢生产者；仍不是自然麦克风输入。
5. API28原选择触摸已执行但provider未返回。新驱动等窗口文件几何稳定200ms、最后fresh观察后单次真实触摸，必须观察provider退出及真实导入完成；没有盲重试或制造URI。

本切片本地实际：Rust26测试/fmt/native及Android交叉Clippy通过；JVM引擎/预览/共同预算14例、模型存储17类、Android Python34例通过；产品与voice-tests用API36 Java source8/target8编译通过。根Rust87测试/fmt/Clippy此前通过。新JNI参数ABI、背压修补、独立producer及SAF稳定驱动仍待新CI实际执行，不能借旧包或CPU结果称PASS。

## 下一步与客观边界

继续读取新focused真实结果并修复，保留完整Android回归；实际下载最终双ABI APK、校验固定源码/签名/摘要后更新[Alpha试用说明](DUAL_VOICE_ALPHA_TRYOUT.md)。当前c7包与45测试输入都是中间证据，不能当含背压修补的最终Alpha。

三星S25/ARM手机、自然人声CER、同机端到端性能/CPU/能耗/温度、真实系统撤权场景：NOT RUN。集中操作见[S25验收单](S25_DUAL_VOICE_CHECKLIST.md)，不要求用户现在执行零散步骤。VoiceKey权重再分发许可未确立，不进入公开APK/测试产物/备份/R2；用户显式从固定官方来源取得。开发证书每个临时runner可能不同，升级前备份，不能承诺覆盖安装。

其他三端系统安装、完整四端E2EE配对/同步/恢复、用户服务器真实部署、正式签名与英语伴学实际翻译/UI仍未完成。

[开发计划](DEVELOPMENT_PLAN.md) · [技术整合](VOICEKEY_INTEGRATION.md) · [验收历史](VOICEKEY_ACCEPTANCE.md) · [原始本轮证据](evidence/voicekey-runtime-20261106/) · [接管指令](WORK_HANDOFF_PROMPT.md) · [此前状态原文](STATUS_HISTORY_20261106.md)。
