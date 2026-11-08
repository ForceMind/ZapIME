# PR100 最终CI与候选API36首个验收失败

仅记录PR100固定head `4c98059dc95c56bd46cf568ec9189b1bdbfae591`，tree `71972650824144dcb3c59952e541ff2ea26f6b9c`。实际PR checkout `c50e375966c302598dcc23dc2cc032e2ae07cb5c` 的Git tree完全相同；父为 d38e6ce + 4c98059。PR100已按独立只读预览切片范围合并，但这不使无关输入门禁变成通过。全部为attempt1，未重试。

`workflow-final-states.json`中的8条workflow均已最终结束：7条SUCCESS，候选`42515687138` FAILURE。完整Android`42515688482`已6/6 SUCCESS，`full-android-jobs.json`保留最终元数据；完整报告复播另记，不能把其成功代填候选专项。

| 候选专项作业 | 实际结果 |
| --- | --- |
| build128838082701 / host128838084431 | SUCCESS |
| API28 128839612976 / API35 128839612958 | SUCCESS |
| API36 128839612956 | FAILURE：14项方法阶段成功；真实系统IME输入阶段失败；整个候选workflow不通过。 |

## 第一验收异常与现场

本次 **不是** 旧PR96 API35的SDK镜像安装失败。API36已真正执行方法与系统IME：`candidate-suite-summary`给method exit0/7.942s，system-ime exit1/18.388s。原报告失败为 `touch_pinyin_1 / key_committed_unexpected_text`，第10次真实键时独立Editable长度7、已提交6、仍有组字标记，focused/served均真；合成上下文种子 `你好`已实际提交。

失败时Service原件：starts3/restart1、decision=`selection_not_owned`、submitted1/scored0。host原日志仍是command connection1；66276ms记录finish，66443ms记录新的compose。原framework runtime在 `19:45:42.636`记录 `RESET_NEW_CONFIGURATION`。这些是该源码的配置重启相关线索；该main侧源码没有新固定枚举trace环，不能借9b或其它运行的trace填补，也不能据此声称全部历史失败唯一根因已经证明。submitted1/scored0是观测计数，不据它推断底层模型从未开始计算。

源报告的production_model/production_lexicon为false是本次未达到完成证明，不能改成true；方法阶段的真实模型成功也不代填实际IME当前排序。事后系统IME恢复、产品服务退出、设置恢复、离线规则和数据清理全部通过，但输入仍FAILURE。

## 原件与检查

- `api36-original.zip`为完整原artifact13630769427，118,125B/SHA `98708089005ccb03c2aa3f199df6cb0b4099e52af9b25542704001e3f0fbef5d`。
- `build-original.zip`为完整原artifact13630908456，722B/SHA `c6f9acc22a160312bb029bc197d24b7f86e0323571fdd973c69f9366a5d7bcdd`，固定source与产品/harness指纹没有沿用其它PR。
- `api36-job.log.gz`保存GitHub返回的完整decoded日志UTF8原字节（mtime0），不是删节或改写后的日志。
- `archive-members.json`逐成员记录原字节SHA。原ZIP已用原source-only检查器检查，不含APK、模型、PCM、数据库或秘密材料；大inputs只作为远端元数据，不下载入Git。
- `strict-failure-summary.json`记录同源方法验证器实际接受14项、系统IME严格验证器实际拒绝原失败，以及清理与失败范围。原始报告、失败时Service和host生命周期保真复制，未调整原断言或语料。

本目录不把旧PR96的基础设施失败拿来解释新PR100，也不把全量其它API成功当此API36通过；未涉及手机、ARM、自然语音或正式签名发行。
