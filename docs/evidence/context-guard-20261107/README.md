# 空上下文回退验收接续（2026-11-07）

固定产品/测试源码8df2793，CI checkout073511a；build-report.json记录原开发包身份。run42388891657的首轮原artifact与日志保留于本目录，不能用后续重试覆盖。

API28/36各14项模型/JNI方法门禁及schema2系统IME均通过，已下载原ZIP、核SHA并用严格validator独立重验：真实nihao触屏无提交请求（0→0），可见候选触摸产生你好上下文，再输入shijie，当前revision排序与所见世界精确成为你好世界。11次拼音按键、1次种子提交，无组字标记；系统IME/设置/服务退出、两包合成资料与两族精确UID网络规则清理均核验。不是ARM/S25或准确率/延迟证据。

API35首轮方法门禁在adb root返回非零前失败，尚未运行JNI；独立system gate在第8次按键报key_committed_unexpected_text：空上下文0请求和种子你好已通过，输入shi期间宿主记录组字finish，现场正文出现你好sh。没有产品fatal或ANR记录，不能将它统称为ADB启动问题，也未确证唯一根因。原报告/生命周期/图片均在api35/original.zip；清理成功不等于输入通过。

host首轮真实native15组、JNI36项、独立oracle与7项评测单测通过，随后cargo自动安装rust-toolchain.toml组件时冲突于bin/cargo-clippy，评测未执行。改CI构建为cargo +1.97.1以显式选择已经安装的固定编译器，不弱化其他workflow的fmt/Clippy；该改动待新CI。另补失败现场的固定生命周期和长度/状态诊断，不修改通过断言或产品输入逻辑。

父PR80固定7ff77a9的六条workflow已全部SUCCESS，包括完整Android和候选；它不替代产品有改变的PR81专项。PR81的完整Android、VoiceKey、共享核心、Windows、同步服务和离线语音六条workflow均成功，候选workflow仍失败。已触发同源码API35复验；最终修订CI结果另行更新。原失败不删除，当前仍为草稿开发包。
