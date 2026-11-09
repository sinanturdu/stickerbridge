cmake_minimum_required(VERSION 3.22.1)
project(stickerwebp C)

# Vendored libwebp v1.5.0 subset: decoder + demux only (see third_party/libwebp/README.stickerbridge.md).
set(WEBP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libwebp)

file(GLOB WEBP_SOURCES
    ${WEBP_DIR}/src/dec/*.c
    ${WEBP_DIR}/src/demux/*.c
    ${WEBP_DIR}/src/utils/*.c
    ${WEBP_DIR}/src/dsp/*.c
)

add_library(webpdecode STATIC ${WEBP_SOURCES})
target_include_directories(webpdecode PUBLIC ${WEBP_DIR})
target_compile_options(webpdecode PRIVATE -O2 -Wno-unused-parameter)

# Our JNI bridge.
add_library(stickerwebp SHARED stickerwebp_jni.c)
target_link_libraries(stickerwebp PRIVATE webpdecode)
target_compile_options(stickerwebp PRIVATE -O2 -Wall)
target_link_options(stickerwebp PRIVATE -Wl,--gc-sections)
