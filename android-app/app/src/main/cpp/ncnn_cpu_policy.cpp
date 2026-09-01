#include "ncnn_cpu_policy.hpp"

#include <algorithm>
#include <cpu.h>

namespace lsfg_android {

void ncnnCpuSetAllCores() {
    // CPU-only inference: explicitly disable ncnn Vulkan compute. powersave=0
    // lets ncnn use the full CPU topology rather than pinning work to LITTLE.
    // The kernel still chooses the fastest available cores first according to
    // its capacity scheduler; OpenMP then fills the remaining online CPUs.
    ncnn::set_cpu_powersave(0);
    ncnn::set_omp_num_threads(std::max(1, ncnn::get_cpu_count()));
}

int ncnnCpuThreadCount() {
    return std::max(1, ncnn::get_cpu_count());
}

} // namespace lsfg_android
