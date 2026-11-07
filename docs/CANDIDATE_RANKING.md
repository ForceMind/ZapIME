# 离线候选增强

实现切片：`feat/offline-candidate-ranking`，基于 `feat/android-manual-sync` 的 `f727d2031f438881fbfaf99aa474517cde66cd29`。用户明确要求接入成熟引擎并默认开启，当前落地宿主为 Android。共享核心已支持安全应用重排；iOS、macOS、Windows 的模型宿主尚未接入，不能把跨平台 Rust 编译当作四端推理完成。

## 采用的方案

| 层 | 实际组件 | 职责 |
|---|---|---|
| 拼音解码 | 固定 MSIME `97d652a49cabb0887355b84186af2aa9032d3814` | 全键/九键、切分、词典候选、部分上屏与原有学习 |
| 候选重排 | chinese-ime-lm `rerank-v2` 6×256，约 8M 参数 | 按至多 64 个码点的左侧上下文给已有完整候选打分 |
| 推理 | APK 已有 ONNX Runtime 1.28.2 CPU | 真实执行 `lm_step.ort`，独立候选 JNI，复用运行库 |
| 应用与提交 | ZapIME Rust 核心、Android 原生宿主 | 核验快照/身份，保留个人规则，由原引擎上屏 |

这是一套本机小型语言模型增强。没有逐键调用云端大模型，也没有让聊天模型任意补写文字。当前不做未输入拼音的续写、自由生成、整段改写或翻译。

模型固定为 `msime-keystroke-8m-rerank-v2`。权重 8,220,336 字节；六个随包文件合计 8,323,716 字节，不代表 APK 净增量或运行内存。固定清单见 [`candidate-model-lock.json`](../third_party/candidate-model-lock.json)，官方发布源为 [chinese-ime-lm rerank-v2](https://github.com/metasequoiaime/chinese-ime-lm/releases/tag/rerank-v2)。没有更新 vendor 子模块。

## 默认行为和故障回退

设置 → 中文候选 → **离线候选增强**，缺省开启；曾明确关闭的偏好仍保留。它是设备本地设置，不写入个人资料同步。密码、禁止个性化学习、`NO_SUGGESTIONS`、宿主搜索动作和键盘内部资料搜索排除增强；英文、转换和非普通拼音模式也不参与。

原候选先显示。80 ms 的停顿用于合并连续输入，模型有初始 400 ms 的结果有效期。模型准备、哈希校验、加载和推理都在独立工作线程，按键线程不等待模型、不下载资源、不读取远端编辑器上下文。数字只是初始工程预算，尚不是 S25 的 P95、功耗或耗时保证。

缺文件、校验失败、运行库不可用、非有限分数、过期、失焦或取消时均保留基础候选。一次加载故障不会随每次按键重复磁盘准备；隐藏重开或开关重开允许重新验证。隐藏、退出和内存紧张请求清理缓存/释放模型；真正释放由原 worker 在物理执行结束后完成。

## 候选身份与并发协议

模型最多评分 32 项，只来自原引擎前 40 行中**引擎明确标记消费完整输入、且词典完整读音与本次按键精确匹配**的汉字候选。还要先完成个人规则、去重和 40 行显示截断，候选必须实际可见；重排只交换这些最终可见槽，不让屏外候选挤掉已显示身份。固定位置、个人显式规则、UserDatabase、个人注入项、英文项、部分候选和长词补全不进入该池。

`candidate_answers_key` 表示消费输入，本身不足以排除补全。全键进一步核对 `canonical_pinyin` 与实际输入的完整字母序列；九键核对完整读音到数字及已锁字母的映射。没有按“汉字数等于音节数”猜完整性。首版对简拼、半拼、模糊/纠错来源保守沿用原引擎排序；这些输入方式仍可正常使用。真实生产库曾暴露“天气→天气预报”“世界→世界各地”的过度补全，已经以此精确匹配规则修复，并保留前后验证记录。

1. 核心 `view` 给出 `generation`、`composition_revision`、与展示行对齐的 `candidate_ids`，以及 `ranking.candidates` 的 ID/文本。
2. 独立 native scorer 返回与请求顺序相同的有限均值字符 logP，宿主只计算 ID 排列。相同分数保留原序。
3. `candidate_rank` 只能在原核心所属线程应用，并携带相同 generation、固定模型 ID 和完整无重复候选 ID 集合。外来 ID、缺项、多项、新文字及迟到代次都拒绝。
4. 纯重排改变展示 generation，但保留 composition_revision；普通输入、读音选择或个人数据修改都会推进 composition_revision。
5. 点击与长按置顶提交显示时的 ID 和 composition_revision；空格附带显示时首选 ID。纯重排期间仍选择原来所见的词；实际输入已推进时，旧点击拒绝，快速空格仍按当前有效输入执行，避免吞空格。
6. 九键读音选择绑定 composition_revision，因为重排不改变读音列表。手指按下时不发布新的候选布局。纯排序回包不调用 `setComposingText` 或 `commitText`。

保留原引擎的选择/部分消费/学习路径，没有另建第二套解码器。模型不直接触发个人学习；原引擎已有的选词学习行为保持原契约，不将 `commitText` 返回 true 夸大为宿主最终持久写入确认。

## 上下文、资源与线程

上下文最多 64 个 Unicode 码点，只在内存存在。API30 及以上允许时读取已交付的 EditorInfo 初始快照；后续只追加本 IME 的打字提交且 `commitText` 返回成功。不在每次按键调用 `getTextBeforeCursor`。保存短语、语音、粘贴、发送/换行、光标变动、不确定选区回声、编辑器变更、隐藏和结束会话会清空上下文。没有记录输入正文、上传 R2 或请求外部服务。

只有一个物理推理任务及一个最新 pending，旧任务未退出前不再启动第二个推理。请求同时绑定编辑器 epoch、输入 revision、候选 generation、上下文 version 和 worker serial；JNI 的 open/score/clear/close 由同一线程所有，cancel 是唯一跨线程操作。模型输出只可成为候选排列，不能解释为命令。

native 推理每批最多 128 个新前缀节点，至多保留 512 个候选前缀及 65 个上下文节点。字符长度、候选数、UTF-8/UTF-16、模型 shape、native handle 数及路径均有显式边界。JNI 通过固定库路径加载已有 ORT；没有第二份 ORT 或动态下载执行代码。

模型准备采用固定 HTTPS 下载、长度/SHA256 校验、暂存发布与失败保留；APK 只允许固定六成员及唯一的候选 `.ort`。这些是完整性校验，不是发布者数字签名。运行时只从 APK assets 安装到应用私有版本目录，再逐项验证；普通输入零下载。

## 授权与分发记录

上游 `rerank-v2` 权重声明为 Apache-2.0，项目保留完整 LICENSE、NOTICE、训练数据/蒸馏来源说明；本地推理适配源码继续服从本项目 GPL-3.0-only。C++ 的前缀拓扑/feeds 参考固定上游，具体归属和修改记录在 `integrations/candidate`。

上游关于训练数据 LCCC 的说明与 LCCC 原仓库说明有差异，按原事实保留在 `chinese-ime-lm-rerank-v2-SOURCE_REVIEW.txt`。这是待澄清来源记录，不宣称已获得额外授权、已解决商业分发问题或具备第三方担保。工程接入和开发包验证继续；正式发行结论必须与技术测试分开。

## 验证与复现

```bash
cargo fmt --all --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
python3 -m unittest discover -s scripts -p 'test_prepare_candidate_model.py' -v
python3 -m unittest discover -s scripts -p 'test_android_*.py' -v
bash scripts/test_android_candidates.sh
bash scripts/build_android.sh arm64-v8a
bash scripts/build_android.sh x86_64
bash scripts/build_android_voice_tests.sh --candidate-only
```

专项核心回归使用真实 MSIME 和合成词典，验证默认开关、完整/部分候选、个人规则、九键、ID、迟到回包、点击/空格/标点/置顶竞态。给定排列的核心回归不是模型准确率测试。独立 native 测试用真实固定 8M 文件对照上游 Python ORT feeds，记录分批量化数值差异、缓存上限、线程、取消及 Unicode；主机库结果不代填 Android。

`candidate-ranking.yml` 保留独立 Android API28/35/36 门禁：实际产品 APK 模型 → JNI 分数 → 真实 MSIME 排列 → ID 选词 → 生产提交方法 → Android EditText/Editable。探针使用独立 UUID 词库、固定合成输入和偏好包装，不修改真实用户词库；产品 UID 在隔离 CI 中 IPv4/IPv6 均拒绝联网并验证恢复。

严格报告须有完整用例集合、模型/运行库身份、参考分数和清理证明；缺失不能算通过。参考由 test-only 独立 C API runner 执行固定上游 Python feed builders 生成的张量，在同一 APK 运行库上运行；不复用生产 tokenizer/trie/cache，不内置 Android 观测分数。逐项绝对误差门槛仍为 0.001，独立 runner 必须执行两次图、核验张量 SHA 并先释放 session。原 Linux 1.24.4 参考及首轮跨版本失败保留为诊断，不用于替代 Android 1.28.2 独立参考。该探针没有激活系统 IME，不能当作系统键盘全流程或手机验收。既有 Android 输入/语音/备份/同步门禁继续保留。

同一专项 workflow 另有独立系统键盘步骤：在一次性模拟器启用真实产品 IME，用触屏事件输入固定 `shijie`，冷加载未完成时最多再试固定 `tianqi`。生产诊断必须显示默认开启、成功发布计数增加，且 `appliedRevision == inputRevision`，避免误拿较早拼音前缀的结果当作当前词已重排。随后点击实时可见候选，并在独立应用的真实 Editable 中核对完整提交。测试不替换产品词库/模型，不直接调用产品 JNI，也不写开关偏好；结束恢复经系统包核验的键盘、验证原服务退出，并清理本轮合成测试资料。该系统门禁与前述 14 项方法探针分别记录，均不能代替手机、真人触摸和耗电验收。

`android_candidate_suite.py` 保持这两项独立门禁各执行一次。方法探针失败、异常或超时仍执行系统 IME 门禁，分别保存原输出和退出码；两项都实际返回 0 且无超时/异常才判总成功。不存在自动重试、跳过失败或改写旧证据的路径。独立数值 oracle 仅装入 x86_64 测试 APK，产品 APK 不包含它，也不增加第二份运行库。

当前实际执行结果见 [STATUS](STATUS.md)。仍需分别完成真机长时间输入、同机 8M/25M 对比、真实语料首选率与误排序率、耗电/内存/尾延迟、其他三端宿主及正式签名。没有引用上游固定候选集成绩冒充本产品端到端准确率。
