#pragma once

#include <cstdint>
#include <vector>
#include <sched.h>

namespace lsfg_android {

// Android CPU policy for latency-sensitive native work.
// Starts on the highest-capacity CPU cluster and can widen to all online CPUs
// when the measured CPU-side work misses its budget. This is per-thread affinity
// and does not require root.
class CpuCorePolicy {
public:
    CpuCorePolicy();

    bool valid() const { return !allCpus_.empty(); }
    int littleCpuCount() const { return static_cast<int>(littleCpus_.size()); }
    int allCpuCount() const { return static_cast<int>(allCpus_.size()); }

    bool useLittleCores();
    // Widens affinity back to every online CPU (including the
    // performance/big cluster). Used by the FPS-lock contention controller
    // to deliberately compete with a source app for CPU headroom; not used
    // by the default always-on little-core policy.
    bool useAllCores();

private:
    std::vector<int> allCpus_;
    std::vector<int> littleCpus_;

    static bool readCpuMetric(int cpu, uint64_t &metric);
    static bool apply(const std::vector<int> &cpus);
    static void appendCpuSet(cpu_set_t &set, const std::vector<int> &cpus);
};

} // namespace lsfg_android
