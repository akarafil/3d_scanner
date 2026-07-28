#include "thread_affinity.h"
#include <pthread.h>
#include <sched.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "ThreadAffinity"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

void BindThreadToCores(ThreadRole role) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    switch (role) {
        case ThreadRole::CAMERA_CAPTURE:
            // Çekirdek 0, 1 (Cortex-A520 Efficiency Cores)
            CPU_SET(0, &cpuset);
            CPU_SET(1, &cpuset);
            LOGI("Thread pinned to Cortex-A520 Efficiency Cores (0, 1)");
            break;
        case ThreadRole::STEREO_MATCHING:
            // Çekirdek 2, 3, 4, 5, 6 (Cortex-A720 Performance Cores)
            CPU_SET(2, &cpuset);
            CPU_SET(3, &cpuset);
            CPU_SET(4, &cpuset);
            LOGI("Thread pinned to Cortex-A720 Performance Cores (2, 3, 4)");
            break;
        case ThreadRole::POISSON_RECON:
            // Çekirdek 7 (Cortex-X4 Prime Core) - Yalnızca arka plan tarama sonrası işlerinde
            CPU_SET(7, &cpuset);
            LOGI("Thread pinned to Cortex-X4 Prime Core (7)");
            break;
    }

    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
}

