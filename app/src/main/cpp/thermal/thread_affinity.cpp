#include "thread_affinity.h"
#include <pthread.h>
#include <sched.h>
#include <unistd.h>
#include <android/log.h>
#include <cstdio>
#include <algorithm>

#define LOG_TAG "ThreadAffinity"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static int GetPrimeCoreIndex() {
    int numCores = sysconf(_SC_NPROCESSORS_CONF);
    if (numCores <= 0) return 7; // Fallback

    int primeCore = 7;
    int maxFreq = 0;

    for (int i = 0; i < numCores; ++i) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE* fp = fopen(path, "r");
        if (fp) {
            int freq = 0;
            if (fscanf(fp, "%d", &freq) == 1) {
                if (freq > maxFreq) {
                    maxFreq = freq;
                    primeCore = i;
                }
            }
            fclose(fp);
        }
    }
    return primeCore;
}

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
            // Stereo Matching için yoğun CPU gücü (Örn: Çekirdek 2, 3, 4)
            CPU_SET(2, &cpuset);
            CPU_SET(3, &cpuset);
            CPU_SET(4, &cpuset);
            LOGI("Thread pinned to Cortex-A720 Performance Cores (2, 3, 4)");
            break;
        case ThreadRole::POISSON_RECON: {
            // Dinamik olarak Prime Core'u (Cortex-X4) tespit et
            int primeCore = GetPrimeCoreIndex();
            CPU_SET(primeCore, &cpuset);
            LOGI("Thread pinned to dynamically detected Prime Core (%d)", primeCore);
            break;
        }
        case ThreadRole::ALL_CORES: {
            int numCores = sysconf(_SC_NPROCESSORS_CONF);
            if (numCores <= 0) numCores = 8;
            for (int i = 0; i < numCores; ++i) {
                CPU_SET(i, &cpuset);
            }
            LOGI("Thread affinity reset to all cores (total %d)", numCores);
            break;
        }
    }

    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
}

