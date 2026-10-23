# Android 键盘交互参考核查

核查时间：2026-10-23。用户提供的 QQ 日间/夜间截图是布局参考；未把用户截图、头像或 QQ 素材分发到源码/安装包。ZapIME 沿用固定 MSIME 引擎，本次没有替换解码器。

| 项目 | 官方来源可确认的能力 | 对本项目的意义 | 许可边界 |
| --- | --- | --- | --- |
| [语燕 YuyanIme](https://github.com/gurecn/YuyanIme) | 基于 Rime；九键、全拼/双拼、手写、单手/悬浮、数字行；展示深色主题 | 与中文九键、熟悉的移动端布局最接近，优先研究其操作流程 | [根 LICENSE](https://github.com/gurecn/YuyanIme/blob/master/LICENSE) 为 BSD 三条款，需保留版权/条款/免责声明及不背书要求；存在 yuyansdk 子模块，不能把根许可扩展为全部组件许可 |
| [同文 Trime](https://github.com/osfans/trime) | Rime Android 前端，音码、形码及可配置方案 | 方案与扩展交互参考，不假设默认安装就有与 QQ 一样的九键 | GPL；若引入实现需锁定提交并保留对应许可/源码义务 |
| [小企鹅 Fcitx5 Android](https://github.com/fcitx5-android/fcitx5-android) | 中文/英文及多种引擎、候选展开、主题、符号/表情、剪贴板 | 键盘基础体验和管理面板参考 | 根标 LGPL-2.1；引擎和依赖逐项核查 |
| [HeliBoard](https://github.com/HeliBorg/HeliBoard) | [官方偏好文档](https://github.com/HeliBorg/HeliBoard/wiki/3.-Preferences) 有触感、声音、长按提示及剪贴板保留设置 | 反馈与设置可借鉴；不把英文键盘的完整功能推断为中文拼音已完成 | GPL-3.0；依赖/可选组件需单独核查 |

小企鹅的 [Google Play 官方页](https://play.google.com/store/apps/details?id=org.fcitx.fcitx5.android&hl=zh-CN) 本次抓取显示 4.2 分，页面说明明确不支持九键拼音。评分受时间/地区/设备影响；无法据此确定用户记忆中的具体输入法，GitHub stars 也不等于商店评分。

本次只研究公开功能、文档、许可和布局，没有复制上述项目实现、词库、图片或皮肤；没有新增这四项运行时依赖。后续需要源码迁移时必须新增固定提交、路径及通知审计，不预先宣称可无条件复制。

触感遵循 [Android 官方 View 反馈指引](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)：优先语义化系统常量，默认尊重设备设置；不申请额外震动权限、不绕过全局关闭、不以固定时长的强震动冒充精细手感。真实马达仍须手机验收。
