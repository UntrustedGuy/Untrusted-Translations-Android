cmake_minimum_required(VERSION 3.31.6)
project("untrusted-ai" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(ai-chat SHARED ai_chat.cpp)
target_include_directories(ai-chat PRIVATE
    ${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp
    ${LLAMA_SRC} ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include ${LLAMA_SRC}/ggml/src)
target_link_libraries(ai-chat llama llama-common android log)

add_library(vision-chat SHARED vision_chat.cpp)
target_include_directories(vision-chat PRIVATE
    ${LLAMA_SRC} ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(vision-chat llama llama-common mtmd android log)

foreach(target ai-chat vision-chat)
    target_compile_definitions(${target} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>)
endforeach()
