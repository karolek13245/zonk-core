cmake_minimum_required(VERSION 3.22)
project(llama-jni CXX)

# Setup (one time):
#   git clone --depth 1 https://github.com/ggml-org/llama.cpp llama.cpp
#
# The JNI wrapper uses the modern llama.cpp API (llama_model_load_from_file,
# llama_init_from_model, llama_memory_clear, llama_vocab_*). If you pin an older
# release, adjust the function names to match it.

# Build only the core library, as a static lib, with no network/CURL needs
# (otherwise the Android build fails looking for CURL).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(llama-jni SHARED llama-jni.cpp)
target_link_libraries(llama-jni PRIVATE llama log)
