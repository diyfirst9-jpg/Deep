#include "ncnn_cpu_policy.hpp"

#include <algorithm>
#include <cpu.h>

namespace lsfg_android {

void ncnnCpuSetAllCores() {
    // CPU-only inference: explicitly disable ncnn Vulkan compute.
    // No affinity/pinning is applied; Android's scheduler may use any
    // CPU/core available to the process.
    ncnn::set_cpu_powersave(0);
    ncnn::set_omp_num_threads(std::max(1, ncnn::get_cpu_count()));
}

int ncnnCpuThreadCount() {
    return std::max(1, ncnn::get_cpu_count());
}

} // namespace lsfg_android
