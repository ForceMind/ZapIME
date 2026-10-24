cmake_minimum_required(VERSION 3.25)
project(ZapIMEWindows LANGUAGES CXX)
if(NOT WIN32)
  message(FATAL_ERROR "Windows SDK and MSVC required")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include(FetchContent)
FetchContent_Declare(json
  URL https://github.com/nlohmann/json/releases/download/v3.12.0/json.tar.xz
  URL_HASH SHA256=42f6e95cad6ec532fd372391373363b62a14af6d771056dbfc86160e6dfff7aa
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(JSON_BuildTests OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(json)
add_library(zapime-core STATIC IMPORTED)
set_target_properties(zapime-core PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../../target/release/zapime_core.lib")
add_library(ZapIMETsf SHARED ZapIMETsf.cpp ZapIME.def)
target_compile_definitions(ZapIMETsf PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN _WIN32_WINNT=0x0A00)
target_compile_options(ZapIMETsf PRIVATE /W4 /utf-8 /EHsc)
target_link_libraries(ZapIMETsf PRIVATE zapime-core nlohmann_json::nlohmann_json
  ole32 oleaut32 uuid advapi32 shell32 user32 gdi32 bcrypt userenv ws2_32 ntdll secur32 crypt32)
add_executable(ZapIMEWindowsTests tests/NativeTests.cpp)
target_compile_options(ZapIMEWindowsTests PRIVATE /W4 /utf-8 /EHsc)
target_compile_definitions(ZapIMEWindowsTests PRIVATE UNICODE _UNICODE)
target_link_libraries(ZapIMEWindowsTests PRIVATE zapime-core nlohmann_json::nlohmann_json
  ole32 uuid advapi32 bcrypt userenv ws2_32 ntdll secur32 crypt32)
enable_testing()
add_test(NAME windows-native-com-and-engine COMMAND ZapIMEWindowsTests)
add_executable(ZapIMEWindowsProfileProbe tests/ProfileProbe.cpp)
target_compile_options(ZapIMEWindowsProfileProbe PRIVATE /W4 /utf-8 /EHsc)
target_compile_definitions(ZapIMEWindowsProfileProbe PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN _WIN32_WINNT=0x0A00)
target_link_libraries(ZapIMEWindowsProfileProbe PRIVATE ole32 oleaut32 uuid advapi32 shell32 user32)
add_custom_command(TARGET ZapIMETsf POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy "${json_SOURCE_DIR}/LICENSE.MIT" "$<TARGET_FILE_DIR:ZapIMETsf>/nlohmann-json-LICENSE.MIT")
