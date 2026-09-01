#pragma once

#include <cstdint>
#include <vector>
#include <sched.h>

namespace lsfg_android {

// Android CPU policy for latency-sensitive native work.
// Start CPU-heavy latency-sensitive work on the highest-capacity (big)
// cluster, then widen the affinity mask to all online CPUs when more CPU
// parallelism is needed. Affinity is per-thread and requires no root.
class CpuCorePolicy {
public:
    CpuCorePolicy();

    bool valid() const { return !allCpus_.empty(); }
    int performanceCpuCount() const { return static_cast<int>(performanceCpus_.size()); }
    int littleCpuCount() const { return static_cast<int>(littleCpus_.size()); }
    int allCpuCount() const { return static_cast<int>(allCpus_.size()); }

    bool usePerformanceCores();
    // Widens affinity back to every online CPU (including the
    // performance/big cluster). Used by the FPS-lock contention controller
    // to deliberately compete with a source app for CPU headroom; not used
    // by the default always-on little-core policy.
    bool useAllCores();

private:
    std::vector<int> allCpus_;
    std::vector<int> performanceCpus_;
    std::vector<int> littleCpus_;

    static bool readCpuMetric(int cpu, uint64_t &metric);
    static bool apply(const std::vector<int> &cpus);
    static void appendCpuSet(cpu_set_t &set, const std::vector<int> &cpus);
};

} // namespace lsfg_android
