cmake_minimum_required(VERSION 3.22.1)
project(packsanity_android LANGUAGES C)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "PackSanity publishes arm64-v8a Android binaries")
endif()

get_filename_component(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native" ABSOLUTE)
set(LUA "${NATIVE}/vendor/lua-5.4.9")
file(GLOB LUA_SOURCES CONFIGURE_DEPENDS "${LUA}/*.c")
list(FILTER LUA_SOURCES EXCLUDE REGEX "/(lua|luac|liolib|loslib|ldblib|linit)\\.c$")

add_library(packsanity_lua_jni SHARED
    "${NATIVE}/src/lua_jni.c" "${NATIVE}/src/lua_host.c" ${LUA_SOURCES})
target_include_directories(packsanity_lua_jni PRIVATE "${NATIVE}/include" "${LUA}")
target_compile_definitions(packsanity_lua_jni PRIVATE LUA_USE_C89)
target_link_libraries(packsanity_lua_jni PRIVATE m)

add_library(packsanity_python SHARED python_jni.c "${NATIVE}/src/python_host.c")
target_include_directories(packsanity_python PRIVATE
    "${NATIVE}/include" "${PYTHON_PREFIX}/include/python3.13")
target_link_libraries(packsanity_python PRIVATE "${PYTHON_PREFIX}/lib/libpython3.13.so")

foreach(target packsanity_lua_jni packsanity_python)
    target_compile_options(${target} PRIVATE -Wall -Wextra -Werror=return-type)
    target_link_options(${target} PRIVATE -Wl,--build-id -Wl,-z,max-page-size=16384)
endforeach()
