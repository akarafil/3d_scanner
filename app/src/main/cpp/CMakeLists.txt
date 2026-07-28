cmake_minimum_required(VERSION 3.22.1)
project("magic3d_engine")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(VULKAN_LIB vulkan)
find_library(EGL_LIB EGL)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/
    ${CMAKE_CURRENT_SOURCE_DIR}/hardware
    ${CMAKE_CURRENT_SOURCE_DIR}/calibration
    ${CMAKE_CURRENT_SOURCE_DIR}/npu
    ${CMAKE_CURRENT_SOURCE_DIR}/fusion
    ${CMAKE_CURRENT_SOURCE_DIR}/thermal
    ${CMAKE_CURRENT_SOURCE_DIR}/mesh
    ${CMAKE_CURRENT_SOURCE_DIR}/vulkan
)

# OpenCV Yapılandırması (Varsa include edilir)
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv/sdk/native/jni)
    set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv/sdk/native/jni)
    find_package(OpenCV REQUIRED COMPONENTS core imgproc calib3d features2d)
    include_directories(${OPENCV_DIR}/include)
    add_compile_definitions(HAVE_OPENCV=1)
endif()


add_library(magic3d_engine SHARED
    native_bridge.cpp
    hardware/zerocopy_buffer.cpp
    calibration/online_calibrator.cpp
    npu/qnn_depth_pipeline.cpp
    npu/npu_denoise_engine.cpp
    fusion/adaptive_depth_fusion.cpp
    thermal/thread_affinity.cpp
    mesh/poisson_deferred.cpp
    vulkan/vulkan_compute_engine.cpp
)

target_link_libraries(magic3d_engine
    ${LOG_LIB}
    ${ANDROID_LIB}
    ${VULKAN_LIB}
    ${EGL_LIB}
    -fopenmp
    -static-openmp
)

if(TARGET OpenCV::opencv_core)
    target_link_libraries(magic3d_engine ${OpenCV_LIBS})
endif()
