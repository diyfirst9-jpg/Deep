#include "ncnn_cpu_policy.hpp"

#include <algorithm>
#include <cpu.h>
#include "cpu_core_policy.hpp"

namespace lsfg_android {

void ncnnCpuSetAllCores() {
    // CPU-only inference: explicitly disable ncnn Vulkan compute. powersave=0
    // lets ncnn use the full CPU topology rather than pinning work to LITTLE.
    // Inference threads are started on the performance cluster. When more
    // parallelism is needed the caller can widen affinity to all CPUs.
    // Do not intentionally start heavy inference on LITTLE cores.
    CpuCorePolicy policy;
    policy.usePerformanceCores();
    ncnn::set_cpu_powersave(0);
    ncnn::set_omp_num_threads(std::max(1, ncnn::get_cpu_count()));
}

int ncnnCpuThreadCount() {
    return std::max(1, ncnn::get_cpu_count());
}

} // namespace lsfg_android
