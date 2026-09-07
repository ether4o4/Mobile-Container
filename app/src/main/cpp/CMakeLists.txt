cmake_minimum_required(VERSION 3.22.1)

# Map Gradle ABI to the prebuilt llama.cpp library directory.
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/arm64-v8a)
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/x86_64)
else()
    set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
endif()

set(LLAMA_SRC_DIR $ENV{LLAMA_SRC_DIR})
if(NOT LLAMA_SRC_DIR)
    set(LLAMA_SRC_DIR /data/home/skywork/tools/llama.cpp)
endif()

# ---- libmcpty.so : pty terminal bridge ----
add_library(mcpty SHARED pty.cpp)
target_link_libraries(mcpty log)

# ---- libmcllama.so : llama.cpp inference bridge ----
add_library(mcllama SHARED llama_bridge.cpp)

target_include_directories(mcllama PRIVATE
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/ggml/include
    ${LLAMA_SRC_DIR}/ggml
    ${LLAMA_SRC_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Imported prebuilt shared libs (built separately with the NDK).
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libllama.so)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libggml.so)

target_link_libraries(mcllama
    llama
    ggml
    log
    android
)
