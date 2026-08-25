#include "ncnn_cpu_policy.hpp"

#include <algorithm>
#include <cpu.h>

namespace lsfg_android {

void ncnnCpuSetLittleOnly() {
    const int little = ncnn::get_cpu_count() - ncnn::get_big_cpu_count();
    if (little > 0) {
        // ncnn Android powersave mode 1 = LITTLE cluster.
        ncnn::set_cpu_powersave(1);
        ncnn::set_omp_num_threads(std::max(1, little));
    } else {
        ncnn::set_cpu_powersave(0);
        ncnn::set_omp_num_threads(1);
    }
}

int ncnnCpuLittleThreadCount() {
    return std::max(1, ncnn::get_cpu_count() - ncnn::get_big_cpu_count());
}


} // namespace lsfg_android
