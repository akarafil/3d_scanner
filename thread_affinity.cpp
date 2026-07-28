#include <pthread.h>
#include <sched.h>
#include <unistd.h>
#include <android/log.h>

enum class ThreadRole {
    CAMERA_CAPTURE,    // Cortex-A520 (Efficiency)
    STEREO_MATCHING,   // Cortex-A720 (Performance)
    POISSON_RECON      // Cortex-X4 (Prime Core - Yalnızca Tarama Sonrası)
};

void BindThreadToCores(ThreadRole role) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    switch (role) {
        case ThreadRole::CAMERA_CAPTURE:
            // Çekirdek 0, 1 (Cortex-A520 Efficiency Cores)
            CPU_SET(0, &cpuset);
            CPU_SET(1, &cpuset);
            break;
        case ThreadRole::STEREO_MATCHING:
            // Çekirdek 2, 3, 4, 5, 6 (Cortex-A720 Performance Cores)
            CPU_SET(2, &cpuset);
            CPU_SET(3, &cpuset);
            CPU_SET(4, &cpuset);
            break;
        case ThreadRole::POISSON_RECON:
            // Çekirdek 7 (Cortex-X4 Prime Core) - Yalnızca ertelemeli arka plan işleminde
            CPU_SET(7, &cpuset);
            break;
    }

    pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset);
}