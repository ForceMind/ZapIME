# 同步存量记录只读证据预览：固定本机验证

此切片只实现共享Rust的metadata分页，不完成Issue77的首次上传，不接JNI/UI/HTTP。`validation.json`固定base、实际测试源码/树、各文件SHA和未执行范围；全部输入为自写合成资料。

先前records-only恢复真实保留1条记录但pending=0的fixture先提交，再增加只读预览。最终根workspace143项（原134+新9）通过、0 failed/ignored，fmt和严格Clippy通过。data/sync包的较早通过记录单独保留，不冒充最后增加操作快照大小限制后的新计数。

真实SQLite回归使用307条含61墓碑的恢复资料按7条分页；前后检查记录、metadata、operations、queue、stage、versions、receipts、retained与schema，确认预览无写入。实际EXPLAIN测试确认ID keyset与pending查找用现有索引，其他历史使用覆盖索引扫描；可能触发200ms VM协作预算，不假称O(limit)或硬IO期限。慢view实际触发OperationInterrupted后，预览周围schema不变且连接恢复可重试。

首轮仅测试夹具的ALTER TABLE往返使SQLite schema SQL改用引号，导致跨夹具DDL的精确schema比较失败；原log保真gzip保留。夹具修正为严格比较预览前后schema、在夹具自有DDL前后比较全数据，未降低产品无写入或中断断言。

共享client另用真实AEAD两库下载/adopt验证无回声；ACK领先cursor、旧local snapshot与新remote record分开报告，错误完整材料拒绝。正常stage/冻结字节不变；untracked不是恢复来源、远端不存在、可重发墓碑或上传授权。

本机日志不是Android、服务HTTP、真机或首次发布验收。无需数据库服务，未声称Go/PostgreSQL事务测试执行；未操作个人设备或生产服务器。
