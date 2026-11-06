# API36 输入视图未显示：测试环境核对

实际模型、单次FINGER选择、14主/23Editable/watchdog均已通过；失败在后续Smoke最初launch等待键盘。原始服务配置带qwerty硬键盘，mInputStarted=true而mViewsCreated/mInputViewStarted/mShowInputRequested=false，系统记录PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE失败。没有删除原失败或反射创建系统输入视图。

仓库原有scripts/android_smoke.py第28行先设置secure show_ime_with_hard_keyboard=1；独立VoiceKey-only路径没有执行该脚本。AOSP [InputMethodService](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/inputmethodservice/InputMethodService.java)明确内部枚举UNKNOWN=0、FALSE=1、TRUE=2，因此原dump的1不能被当作secure setting=1。原artifact没有直接settings get记录，具体secure原值由下一轮先读取，不补造历史。

修补只作用于有dedicated_ci保护的临时模拟器：读取/校验原值，设1并读回，实际显示/录音/退出断言保持；finally恢复并再读回。产品系统策略保持。最后以新CI为准，文档不代替API36成功。
