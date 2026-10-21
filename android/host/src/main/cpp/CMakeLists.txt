cmake_minimum_required(VERSION 3.22.1)
project(rustkvm_usbiso C)

add_library(rustkvm_usbiso SHARED usb_iso.c)
target_compile_options(rustkvm_usbiso PRIVATE -Wall -Wextra -Werror)
# Android 15 起 16KB 页大小的设备要求 .so 按 16KB 对齐
target_link_options(rustkvm_usbiso PRIVATE "-Wl,-z,max-page-size=16384")
