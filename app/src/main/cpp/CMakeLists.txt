cmake_minimum_required(VERSION 3.22)
project(iaandroid_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Исходники llama.cpp клонируются рядом (см. workflow / scripts), версия закреплена в LLAMA_CPP_VERSION.txt
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp не найден в ${LLAMA_SRC}. Выполните scripts/fetch-llama.sh (или .ps1).")
endif()

if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
    endif()
    # CPU-бэкенды — динамические библиотеки, нужный вариант (dotprod/i8mm/...) выбирается на устройстве.
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
endif()

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(iaandroid_jni SHARED ia_jni.cpp)
target_include_directories(iaandroid_jni PRIVATE
    ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
if(ANDROID)
    target_link_libraries(iaandroid_jni llama ggml android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(iaandroid_jni PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(iaandroid_jni llama ggml)
endif()
