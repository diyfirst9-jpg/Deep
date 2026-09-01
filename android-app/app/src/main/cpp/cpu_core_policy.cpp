#include "cpu_core_policy.hpp"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <unistd.h>

namespace lsfg_android {

namespace {

bool readUintFile(const char *path, uint64_t &value) {
    std::ifstream in(path);
    if (!in) return false;
    return static_cast<bool>(in >> value);
}

bool cpuOnline(int cpu) {
    if (cpu == 0) return true;
    char path[128];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/online", cpu);
    uint64_t online = 1;
    return !readUintFile(path, online) || online != 0;
}

} // namespace

CpuCorePolicy::CpuCorePolicy() {
    const long configured = sysconf(_SC_NPROCESSORS_CONF);
    const int count = configured > 0 ? static_cast<int>(configured) : 1;

    uint64_t maxMetric = 0;
    int maxMetricCpu = -1;
    for (int cpu = 0; cpu < count; ++cpu) {
        if (!cpuOnline(cpu)) continue;
        allCpus_.push_back(cpu);

        uint64_t metric = 0;
        if (readCpuMetric(cpu, metric) && metric > maxMetric) {
            maxMetric = metric;
            maxMetricCpu = cpu;
        }
    }
    if (allCpus_.empty()) return;

    // Android exposes heterogeneous CPUs as clusters with different
    // capacity/frequency. Treat CPUs within 90% of the fastest CPU as the
    // performance/big cluster. The rest are efficiency/little CPUs.
    //
    // IMPORTANT: sched_setaffinity() is a mask, not an execution order.
    // "Big first" therefore means that latency-sensitive work starts with a
    // performance-core-only mask and can explicitly widen to all CPUs later.
    if (maxMetric > 0) {
        for (int cpu : allCpus_) {
            uint64_t metric = 0;
            if (readCpuMetric(cpu, metric) && metric * 100 >= maxMetric * 90) {
                performanceCpus_.push_back(cpu);
            } else {
                littleCpus_.push_back(cpu);
            }
        }
    }

    // If topology metrics are unavailable, prefer every CPU rather than
    // incorrectly forcing a workload onto an arbitrary LITTLE core.
    if (performanceCpus_.empty()) {
        if (maxMetricCpu >= 0) performanceCpus_.push_back(maxMetricCpu);
        else performanceCpus_ = allCpus_;
    }
    if (littleCpus_.empty()) {
        // Homogeneous CPUs: all cores are effectively performance cores.
        // Keep littleCpus_ empty so callers can distinguish this case.
    }
}

bool CpuCorePolicy::readCpuMetric(int cpu, uint64_t &metric) {
    char path[160];

    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
    if (readUintFile(path, metric)) return true;

    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    if (readUintFile(path, metric)) return true;

    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", cpu);
    return readUintFile(path, metric);
}

void CpuCorePolicy::appendCpuSet(cpu_set_t &set, const std::vector<int> &cpus) {
    CPU_ZERO(&set);
    for (int cpu : cpus) {
        if (cpu >= 0 && cpu < CPU_SETSIZE) CPU_SET(cpu, &set);
    }
}

bool CpuCorePolicy::apply(const std::vector<int> &cpus) {
    if (cpus.empty()) return false;
    cpu_set_t set;
    appendCpuSet(set, cpus);
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

bool CpuCorePolicy::usePerformanceCores() {
    return apply(performanceCpus_);
}

bool CpuCorePolicy::useAllCores() {
    return apply(allCpus_);
}

} // namespace lsfg_android
