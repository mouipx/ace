cmake_minimum_required(VERSION 3.15)
project(rawaccel_bridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(rawaccel_bridge SHARED rawaccel_bridge.cpp)

target_compile_definitions(rawaccel_bridge PRIVATE RA_BRIDGE_EXPORTS)
target_include_directories(rawaccel_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Windows-only: link against kernel32 (CreateFileW / CloseHandle)
if(WIN32)
    target_link_libraries(rawaccel_bridge PRIVATE kernel32)
endif()
