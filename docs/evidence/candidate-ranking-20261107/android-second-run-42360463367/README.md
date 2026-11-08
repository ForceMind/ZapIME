# 同运行库数值通过与恢复设置失败

运行 [42360463367](https://github.com/ForceMind/ZapIME/actions/runs/42360463367)，产品实现仍为 `3a11177b7d0b2ef054183f5297a282dcb650a14f`，测试修订为 `a73eea01a4ed0b561dbf2540e837565e99a8c04f`。实际 checkout `697155198b205dbb3cada79468b8000667471dea` 与 a73 源树相同，完整身份见 `source-identity.json`。

## API35 原始结果

`api-35/original.zip` 是 artifact `13569192986` 的完整下载原件，104,386 字节，SHA256 为 `35f386920882070d1a9dc0523197b5b0170c17f9d10d1cb976e7c31f8cf933fe`。旁边 JSON 是其中原字节的便于阅读副本。所有文件摘要见 `provenance.json`。

| 门禁 | 实际结果 |
|---|---|
| 独立模型/JNI/MSIME/Editable 方法 | 14 项全部通过，实际生产分数与同运行库独立参考最大误差 0，原门槛 0.001 不变 |
| 独立 oracle | 实际 ORT 1.28.2，固定输入 SHA 正确，2 次图调用、7→10 节点，session 释放 |
| 真实系统键盘输入链路 | 默认开启，生产词库与模型，6 次 `shijie` 触屏输入，发布计数 0→3，当前与已排序 revision 均 15 |
| 选词与提交 | 真实点击所见“世界”，宿主 Editable 精确为“世界”，无组字 span |
| 清理 | 系统键盘恢复、输入法服务退出、双包合成资料清理、IPv4/IPv6 规则恢复均通过；原设置值恢复失败 |
| suite 总结果 | **FAIL**，method exit 0，system-ime exit 1；没有因输入链路成功而忽略清理失败 |

原 `enabled_input_methods` 含 `LatinIME;1594443099:…`。`adb shell` 会再次经过远端 shell 解析；测试驱动将带分号值作为未转义参数，导致后半段被当成命令，exit 127。修复只针对 test-only runner：用 `shlex.join` 把完整设置命令编码为一个远端 shell 字符串，保留缺值删除语义和原恢复后逐项读回断言。本机 `/bin/sh` 合成测试覆盖真实分号形式及其他 shell 元字符，不运行 adb。修复后的设备清理须由新提交的新 CI 证明。

原截图也保持在 ZIP 中：`candidate-ime-committed.png` 抓取紧跟 Editable 检查，画面仍可能早于下一帧绘制。它不能单独作为“画面已显示提交文本”的证明；输入证据来自原报告的实际 Editable 和真实事件路径，截图不得事后改写。

## 包与主机结果

四份 CI APK 的原尺寸/SHA 见 `build-report.json`；下载后再次通过签名、16 KiB 对齐、模型/原生库/许可证检查，见 `apk-local-verification.json`。两个产品 APK 的逐成员内容与首次实现构建相比，仅开发签名证书文件不同；产品字节载荷没有改变。开发签名不是正式发行签名，不能据此保证覆盖安装旧开发包。

`host/` 为该运行实际下载的 15 组 native、36 项 JNI、独立上游张量主机报告。主机证据不代替 Android 或真机。

## 同轮 API28 / API36 后续取回结果

两份原 ZIP 均已下载，逐项比对 GitHub artifact 尺寸与摘要，并独立执行方法报告及系统输入报告验证器，保存于各自子目录。三端分母保持各自 14 项方法检查，不与系统 IME 混为一组。

| API | Artifact | 方法 | 真实系统输入 | 设置恢复 | suite |
|---|---|---|---|---|---|
| 28 | 13569731854 | 14 项通过，误差 0 | “世界”精确 Editable，当前/已排 revision 17/17 | 通过，所有清理均通过 | PASS |
| 35 | 13569192986 | 14 项通过，误差 0 | “世界”精确 Editable，当前/已排 revision 15/15 | 分号值恢复失败 | FAIL |
| 36 | 13570091175 | 14 项通过，误差 0 | “世界”精确 Editable，当前/已排 revision 15/15 | 同一分号值恢复失败，其余清理通过 | FAIL |

后续修正运行按新的固定源码另存；本目录 API35/36 的总失败状态不因后续成功而改写。
