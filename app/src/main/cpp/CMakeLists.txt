cmake_minimum_required(VERSION 3.22)
project(llama-jni C CXX)

# llama.cpp is cloned into ./llama.cpp by the GitHub workflow (see README to do it by hand).
# Always build it optimized: a debug build is far too slow to be usable.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Core library only, static, no extras that need network libraries.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The static llama/mtmd libraries end up inside one shared library, so they must be position independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
# Picture support: llama.cpp's multimodal library (tools/mtmd), built on its own. No video.
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(llama-jni SHARED llama-jni.cpp)
target_link_libraries(llama-jni PRIVATE llama mtmd log)
# Android 15+ devices use 16 KB memory pages.
target_link_options(llama-jni PRIVATE -Wl,-z,max-page-size=16384)
