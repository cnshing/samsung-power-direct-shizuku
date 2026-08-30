# The ADB pairing handshake, and nothing else.
#
# Everything else in this app is Kotlin or the Rust daemon; this exists only
# because Android's wireless-debugging pairing is SPAKE2, which the platform
# does not expose to apps. BoringSSL has it, so a hundred lines of JNI is
# cheaper than any alternative.

cmake_minimum_required(VERSION 3.22.1)
project("powerdirect")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti -fvisibility=hidden")

find_library(log-lib log)
find_package(boringssl REQUIRED CONFIG)

add_library(adbpairing SHARED pairing.cpp)
target_link_libraries(adbpairing ${log-lib} boringssl::crypto_static)
