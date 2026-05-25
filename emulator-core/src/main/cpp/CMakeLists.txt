cmake_minimum_required(VERSION 3.22.1)

project("emulator-core")

find_package(oboe REQUIRED CONFIG)

add_library(
    "emulator-core"
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

target_link_libraries(
    "emulator-core"
    oboe::oboe
    ${log-lib}
    ${android-lib}
)
