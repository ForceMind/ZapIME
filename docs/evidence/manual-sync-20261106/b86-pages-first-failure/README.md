# Android 手动同步页面首轮：保留真实失败

固定PR75 head `b86e4f27fcce2c5d0bd394b139463bb52ea5fbf9`，实际CI checkout `e38889af93144f1bd9076fd2723fd0c1d762f457`；Git API独立核两者tree均 `7617e97ec42399c1d0f9f01db953a2b352375912`。Focused [run42353573084](https://github.com/ForceMind/ZapIME/actions/runs/42353573084) 的三端本机准备作业均为真实FAIL，不称整轮通过。

| API | Job | Artifact | ZIP bytes | 实算ZIP SHA256 | 原47项 | 新页面组 |
| --- | --- | --- | ---: | --- | --- | --- |
| 28 | 128347475743 | 13566567813 | 14229 | `383e9b8074af1fc43d94bb1f0e499a8d3b82b3dc4d0a886cc40f7ea2c737110f` | PASS | FAIL |
| 35 | 128347475686 | 13566722796 | 23183 | `4662c332272171051a1b67458fc28bfd334aeaf0724166300c574af2f7815d8a` | PASS | FAIL |
| 36 | 128347475690 | 13566283141 | 34346 | `7e6b84e5b99bbb8cabe6c018e1573e5d3ad8dd70c51bc384a72039e3150bce55` | PASS | FAIL |

原ZIP均实际下载、核对GitHub大小/digest/run/head；完整job日志与每个原报告/提供程序PNG按原字节保存。固定head的报告validator和本地依赖从git show冻结后执行：各端原14项key、12项真实JNI transfer、11项准备、10项真实SAF共47项通过。新10项页面组严格校验均拒绝，不拿局部成功代填整组。

三端新组实际均完成 `fresh_main_prepared_fixture`、`key_page_secure_local_selection`、`key_confirmation_negative_cancel`，在 `key_queued_cancel_before_io` 记录 `failure_type=Exception`。原报告没有更深的cause或子步骤，不补造。三端fixture cleanup=true，精确产品UID的IPv4/IPv6 REJECT与removed=true；清理通过不代表页面组通过。

源码审查发现：探针在同一个主线程任务中调用AlertDialog正按钮的performClick，随后立即读取生产cancellation/worker queue并finish；Android正常按钮listener经消息队列异步派发，此时可能尚未执行生产run。这是明确的源码时序推断，原件不足以独立证明它就是实际innercause。下一轮只拆开正常点击、等待主线程空闲、重新核验真实request与queue=1再finish；保留原取消、generation、barrier、数据与密文不变和清理断言，并在新源码失败报告记录固定子步骤。新源码必须独立CI复验。

新TLS API28是本run的独立作业，原件由另一份证据保存；其成功不能替本页面失败背书。本目录不含APK、模型、音频、恢复码、材料、访问令牌或用户正文，也不证明手机、自然语音、两台设备、配对、R2或生产部署。

ZIP与冻结源码仅存ignored target；本目录inventory覆盖可入库原件与校验摘要。job日志保存GitHub返回的已解码UTF8文字。
