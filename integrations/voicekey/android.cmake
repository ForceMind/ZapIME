set(ANDROID_ABI $ENV{ZAPIME_ANDROID_ABI})
set(ANDROID_PLATFORM android-28)
include("$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
