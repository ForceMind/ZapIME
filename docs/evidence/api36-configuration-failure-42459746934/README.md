# PR87 / API36：配置重启导致组字边界丢失的原失败

run [42459746934](https://github.com/ForceMind/ZapIME/actions/runs/42459746934)，job128668487627。方法门禁通过，独立系统IME门禁失败；不改写原结果。head cbe55e286b017512212efc1d07e7db13c3112403，真实build checkout38a58fdab216ed4fa0d1abeb2da3a565a191a904。两份完整ZIP已实际下载，并逐项核对GitHub字节数/SHA256；身份及原成员摘要见[清单](manifest.json)。附件中的构建声明不是已独立下载APK验证。

## 新环直接确认的失败路径

真实触摸5键nihao并点可见候选得到“你好”后，目标shijie在第9次拼音触摸失败，原因key_committed_unexpected_text。failure-time报告：正文6个UTF16单位、已提交5、仍有composing，focused/served均true。失败前原服务快照decision=selection_not_owned，starts3/restarts1，scored/submitted0；模型尚未评分，不能把它归因为LM误排序。

| uptime ms | 真实服务事件或独立宿主命令 |
| --- | --- |
| 64649 / 64655 | Service APPLY_COMMIT；host连接1接受两字种子，span移除 |
| 64990 / 64992 | 新组字s，host长度3、span2:3 |
| 65436 / 65453 | 组字sh，host长度4、span2:4 |
| 65909 / 65922 | 第8键后组字shi，host长度5、span2:5 |
| 65944 | INPUT_RESTART(epoch3)随即INPUT_REBUILT(epoch4) |
| 65974 | 重建之后收到SELECTION_EXTERNAL；重启之前没有ABANDON_COMPOSITION事件 |
| 66105 / 66111 | 初始化空回复APPLY_FINISH_EMPTY；同一host连接1长度仍5，但span成为-1:-1 |
| 66305 / 66314 | 下一键新compose，host长度6、span5:6；此前拼音已成为提交正文 |

框架原日志07:56:12.374的IME进程3308记录RESET_NEW_CONFIGURATION，和该restart时序一致。由此可确认本次不是先abandon再restart：配置重启在先，原保留条件拒绝后重建，初始化空回复结束了原组字标记。仍未拿到拒绝时EditorInfo初始选区的数值，也未确定触发配置变化的设置或系统来源，不能宣称它解释了所有历史间歇失败。

这里使用的是Instrumentation.finish前生成的candidate-ime-service-failure.txt。ZIP还保留finish后外部input-method-state.txt，后者已切Launcher、decision变为new_editor；它不能覆盖失败时原件。两份状态的时间与用途分别保留。

## 门禁与接续

原run-summary的系统键盘、实际服务退出、系统设置、两包合成资料和两族UID规则清理全部通过；输入仍失败，production_lexicon/production_model仍为false。没有产品fatal或测试宿主ANR可以解释掉这次精确正文失败。

产品修复尚未实施。不得简单跳过EditorInfo快照或选区保护，也不得在输入线程添加远端正文读取。独立框架重启probe仍按其实际CI结果判断；它若观察到真实隐藏/结束输入，不能当作同编辑器组字失败复现。新证据应支持一个明确、安全、有确定性回归的修复，而不是把成功重跑替代原失败。
