#pragma once

namespace lsfg_android {

// CPU-only ncnn policy. All online CPUs are available, with Android's
// scheduler free to place threads on the highest-capacity cores first.
// This is deliberately not LITTLE-only: CPU work is used to keep Vulkan/GPU
// compute reserved for the dedicated LSFG frame-generation pipeline.
void ncnnCpuSetAllCores();
int ncnnCpuThreadCount();

} // namespace lsfg_android
