cmake_minimum_required(VERSION 3.22.1)
project(borescope_uvc C CXX)

# Static libs get linked into a shared .so.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Stock upstream sources, pinned as git submodules. No forks, no patches.
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(LIBUSB_DIR ${THIRD_PARTY}/libusb)
set(LIBUVC_DIR ${THIRD_PARTY}/libuvc)

if(NOT EXISTS ${LIBUSB_DIR}/libusb/core.c OR NOT EXISTS ${LIBUVC_DIR}/src/init.c)
    message(FATAL_ERROR "Submodules missing. Run: git submodule update --init")
endif()

# ---- libusb (source list mirrors libusb/android/jni/libusb.mk) -------------
add_library(usb1 STATIC
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c)
target_include_directories(usb1
    PUBLIC  ${LIBUSB_DIR}/libusb
    PRIVATE ${LIBUSB_DIR}/android ${LIBUSB_DIR}/libusb/os)
target_compile_options(usb1 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb1 PRIVATE log)

# ---- libuvc (no libjpeg: MJPEG frames are handed to Kotlin as JPEG bytes) --
add_library(uvc STATIC
    ${LIBUVC_DIR}/src/ctrl.c
    ${LIBUVC_DIR}/src/ctrl-gen.c
    ${LIBUVC_DIR}/src/device.c
    ${LIBUVC_DIR}/src/diag.c
    ${LIBUVC_DIR}/src/frame.c
    ${LIBUVC_DIR}/src/init.c
    ${LIBUVC_DIR}/src/misc.c
    ${LIBUVC_DIR}/src/stream.c)
target_include_directories(uvc PUBLIC
    ${LIBUVC_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/config)   # our hand-written libuvc_config.h
target_link_libraries(uvc PUBLIC usb1)

# ---- JNI bridge -------------------------------------------------------------
add_library(uvcjni SHARED uvc_jni.cpp)
target_compile_features(uvcjni PRIVATE cxx_std_17)
target_link_libraries(uvcjni PRIVATE uvc log)
# Explicit 16 KB alignment in case an older NDK is used.
target_link_options(uvcjni PRIVATE "-Wl,-z,max-page-size=16384")
