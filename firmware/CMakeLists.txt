# The logger's host library and its tests. WALKTHROUGH.md explains the four steps the library is built in.
cmake_minimum_required(VERSION 3.25)
project(cch_logger LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(CCH_BUILD_TESTS "Build the host tests (GoogleTest, fetched at configure time)" ON)
option(CCH_SANITIZE "AddressSanitizer and UndefinedBehaviorSanitizer (clang or gcc on Linux or WSL)" OFF)
option(CCH_FUZZ "libFuzzer targets (clang on Linux or WSL only)" OFF)

if(CCH_SANITIZE)
    add_compile_options(-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()

# ECDSA P-256 with RFC 6979 nonces (step 3) comes from mbedTLS, the long-term-support 3.6 line, as a board build
# would use. Pinned by the SHA-256 in its release notes and GitHub's asset digest (docs/agent/DECISIONS.md).
include(FetchContent)
FetchContent_Declare(mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.8/mbedtls-3.6.8.tar.bz2
    URL_HASH SHA256=bc54b818e87f49c0b3281dd85d24adaede0e63b4e822e3fbd49c005a38b9595b
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mbedtls)

add_library(cch_logger
    src/record.cpp
    src/hashing.cpp
    src/record_log.cpp
    src/signing.cpp
    src/history.cpp
)
target_include_directories(cch_logger PUBLIC include)
target_link_libraries(cch_logger PRIVATE mbedcrypto)
target_compile_options(cch_logger PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Wshadow>
)

if(CCH_FUZZ)
    target_compile_options(cch_logger PRIVATE -fsanitize=fuzzer-no-link,address,undefined)
    foreach(target IN ITEMS record_decode history_request)
        add_executable(fuzz_${target} fuzz/fuzz_${target}.cpp)
        target_link_libraries(fuzz_${target} PRIVATE cch_logger)
        target_compile_options(fuzz_${target} PRIVATE -fsanitize=fuzzer,address,undefined)
        target_link_options(fuzz_${target} PRIVATE -fsanitize=fuzzer,address,undefined)
    endforeach()
endif()

if(CCH_BUILD_TESTS)
    # Versions and hashes checked against GitHub's published SHA-256 digests on 6 Nov 2026 (docs/agent/DECISIONS.md).
    FetchContent_Declare(googletest
        URL https://github.com/google/googletest/releases/download/v1.18.0/googletest-1.18.0.tar.gz
        URL_HASH SHA256=6e3191c1455468b3fc35a417fb565c1c5071aee1b7e7f85e30cf48a98d37d8b5
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )
    FetchContent_Declare(json
        URL https://github.com/nlohmann/json/releases/download/v3.12.0/json.tar.xz
        URL_HASH SHA256=42f6e95cad6ec532fd372391373363b62a14af6d771056dbfc86160e6dfff7aa
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    set(JSON_Install OFF CACHE INTERNAL "")
    FetchContent_MakeAvailable(googletest json)

    enable_testing()
    add_executable(cch_logger_tests
        tests/vectors.cpp
        tests/test_step1_encoding.cpp
        tests/test_step2_record_log.cpp
        tests/test_step3_signing.cpp
        tests/test_step4_history.cpp
    )
    target_link_libraries(cch_logger_tests PRIVATE cch_logger GTest::gtest_main nlohmann_json::nlohmann_json)
    target_compile_definitions(cch_logger_tests PRIVATE
        CCH_VECTORS_PATH="${CMAKE_CURRENT_SOURCE_DIR}/../docs/protocol/logger_vectors.json")
    if(MINGW)
        # Lets the test program run without MinGW's runtime DLLs on PATH.
        target_link_options(cch_logger_tests PRIVATE -static)
    endif()

    include(GoogleTest)
    gtest_discover_tests(cch_logger_tests DISCOVERY_MODE PRE_TEST)
endif()
