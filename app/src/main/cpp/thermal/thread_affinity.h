#ifndef THREAD_AFFINITY_H
#define THREAD_AFFINITY_H

enum class ThreadRole {
    CAMERA_CAPTURE,    // Cortex-A520 (Efficiency)
    STEREO_MATCHING,   // Cortex-A720 (Performance)
    POISSON_RECON,     // Cortex-X4 (Prime Core - Sadece Tarama Sonrası)
    ALL_CORES          // Reset affinity to all cores
};

void BindThreadToCores(ThreadRole role);

#endif // THREAD_AFFINITY_H
