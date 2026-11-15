cmake_minimum_required(VERSION 3.22.1)
project(netswap_jni LANGUAGES CXX)
add_library(netswap_boringtun SHARED IMPORTED)
set_target_properties(netswap_boringtun PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnetswap_boringtun.so")
add_library(netswap_jni SHARED netswap_jni.cpp)
target_compile_features(netswap_jni PRIVATE cxx_std_17)
target_link_libraries(netswap_jni PRIVATE netswap_boringtun log)
