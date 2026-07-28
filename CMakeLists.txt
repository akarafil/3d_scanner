cmake_minimum_required(VERSION 3.22.1)
project("magic3d_engine")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 3. Parti Kütüphane Konumları
set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv/sdk/native/jni)
set(FILAMENT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/filament)
set(QNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/qnn)

find_package(OpenCV REQUIRED COMPONENTS core imgproc calib3d features2d)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(VULKAN_LIB vulkan)
find_library(EGL_LIB EGL)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${OPENCV_DIR}/include${FILAMENT_DIR}/include
    ${QNN_DIR}/include/QNN${CMAKE_CURRENT_SOURCE_DIR}/third_party/eigen3
)

# QNN Backend Bağlantısı
add_library(qnn_wrapper SHARED IMPORTED)
set_target_properties(qnn_wrapper PROPERTIES IMPORTED_LOCATION ${QNN_DIR}/lib/arm64-v8a/libQnnHtp.so)

add_library(magic3d_engine SHARED
    native_bridge.cpp
    hardware/zerocopy_buffer.cpp
    calibration/online_calibrator.cpp
    npu/qnn_depth_pipeline.cpp
    fusion/adaptive_depth_fusion.cpp
    thermal/thread_affinity.cpp
    mesh/poisson_deferred.cpp
)

target_link_libraries(magic3d_engine
    ${LOG_LIB}
    ${ANDROID_LIB}${VULKAN_LIB}
    ${EGL_LIB}${OpenCV_LIBS}
    qnn_wrapper
)