package com.firstt175.deepdrop.ui

import android.app.ActivityManager
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.session.NativeBridge
import com.firstt175.deepdrop.session.ShizukuDisplayPermission
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader
import com.firstt175.deepdrop.ui.components.StatusPill
import com.firstt175.deepdrop.ui.components.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------
// Device profile screen. Everything below (CPU load, temperature, RAM,
// current per-cluster clock speed) is only ever polled while this
// composable is actually on screen: the LaunchedEffect loop is cancelled
// the moment the user navigates away, so there is no background polling
// anywhere else in the app.
// ---------------------------------------------------------------------

private const val POLL_INTERVAL_MS = 1000L

private data class CpuCluster(
    val label: String,
    val coreIndices: List<Int>,
    val maxFreqMhz: Int,
)

/** Facts that cannot change while the app is running — gathered once. */
private data class StaticProfileInfo(
    val modelName: String,
    val androidVersion: String,
    val soc: String,
    val abis: String,
    val cpuCoreCount: Int,
    val cpuClusters: List<CpuCluster>,
    val gpuName: String,
    val gpuVendor: String,
    val gpuDeviceType: String,
    val gpuDriverVersion: String,
    val gpuVramMb: Long,
    val vulkanApiVersion: String,
)

/** Facts that change second to second — re-read on every poll tick. */
private data class LiveProfileInfo(
    val rootLabel: String,
    val rootTone: StatusTone,
    val shizukuLabel: String,
    val shizukuTone: StatusTone,
    val cpuLoadPercent: Float?,
    val cpuTempC: Float?,
    val clusterCurFreqMhz: Map<String, Int?>,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
)

@Composable
fun DeviceProfileScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val static = remember { captureStaticProfileInfo(context) }
    var live by remember { mutableStateOf<LiveProfileInfo?>(null) }

    LaunchedEffect(Unit) {
        // CpuLoadTracker needs two samples to produce a delta, so the very
        // first tick primes it and only the second one paints a real number.
        while (true) {
            live = withContext(Dispatchers.IO) { captureLiveProfileInfo(context, static.cpuClusters) }
            delay(POLL_INTERVAL_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.profile_title),
            onBack = { nav.popBackStack() },
        )

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_device))
            Spacer(Modifier.height(10.dp))
            ProfileRow(stringResource(R.string.profile_model), static.modelName)
            ProfileRow(stringResource(R.string.profile_android), static.androidVersion)
            ProfileRow(stringResource(R.string.profile_soc), static.soc)
            ProfileRow(stringResource(R.string.profile_abi), static.abis)
        }

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_access))
            Spacer(Modifier.height(10.dp))
            ProfileStatusRow(stringResource(R.string.profile_root), live?.rootLabel ?: "…", live?.rootTone ?: StatusTone.Neutral)
            Spacer(Modifier.height(6.dp))
            ProfileStatusRow(stringResource(R.string.profile_shizuku), live?.shizukuLabel ?: "…", live?.shizukuTone ?: StatusTone.Neutral)
        }

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_cpu))
            Spacer(Modifier.height(10.dp))
            ProfileRow(
                stringResource(R.string.profile_cpu_load),
                live?.cpuLoadPercent?.let { "%.0f%%".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_cpu_temp),
                live?.cpuTempC?.let { "%.1f°C".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(stringResource(R.string.profile_cpu_cores), "${static.cpuCoreCount}")
            if (static.cpuClusters.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                static.cpuClusters.forEach { cluster ->
                    val curFreq = live?.clusterCurFreqMhz?.get(cluster.label)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${cluster.label} ×${cluster.coreIndices.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (curFreq != null) {
                                stringResource(R.string.profile_cluster_freq_format, curFreq, cluster.maxFreqMhz)
                            } else {
                                stringResource(R.string.profile_cluster_freq_max_only_format, cluster.maxFreqMhz)
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_memory))
            Spacer(Modifier.height(10.dp))
            val used = live?.ramUsedMb
            val total = live?.ramTotalMb
            ProfileRow(
                stringResource(R.string.profile_ram_used),
                if (used != null) "%.1f GB".format(used / 1024f) else stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_ram_free),
                if (used != null && total != null) "%.1f GB".format((total - used) / 1024f) else stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_ram_total),
                if (total != null) "%.1f GB".format(total / 1024f) else stringResource(R.string.profile_value_na),
            )
        }

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_gpu))
            Spacer(Modifier.height(10.dp))
            ProfileRow(stringResource(R.string.profile_gpu_name), static.gpuName)
            ProfileRow(stringResource(R.string.profile_gpu_vendor), static.gpuVendor)
            ProfileRow(stringResource(R.string.profile_gpu_type), static.gpuDeviceType)
            ProfileRow(stringResource(R.string.profile_gpu_driver), static.gpuDriverVersion)
            ProfileRow(
                stringResource(R.string.profile_gpu_vram),
                if (static.gpuVramMb >= 0) "${static.gpuVramMb} MB" else stringResource(R.string.profile_value_na),
            )
            ProfileRow(stringResource(R.string.profile_vulkan_api), static.vulkanApiVersion)
        }

        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_display))
            Spacer(Modifier.height(10.dp))
            val w = live?.screenWidth
            val h = live?.screenHeight
            ProfileRow(
                stringResource(R.string.profile_display_resolution),
                if (w != null && h != null) "$w × $h" else stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_display_refresh),
                live?.refreshRateHz?.takeIf { it > 0f }?.let { "%.0f Hz".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_display_density),
                live?.densityDpi?.let { "$it dpi" } ?: stringResource(R.string.profile_value_na),
            )
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ProfileStatusRow(label: String, value: String, tone: StatusTone) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(label = value, tone = tone)
    }
}

// -------------------------------------------------------------------
// Data gathering. Everything here is best-effort and wrapped so a
// single unreadable sysfs node (varies wildly across OEM/kernel
// builds) never crashes the screen — it just shows "N/A" for that row.
// -------------------------------------------------------------------

private fun captureStaticProfileInfo(context: Context): StaticProfileInfo {
    val modelName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val androidVersion = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    val soc = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}".trim()
        } else {
            "${android.os.Build.HARDWARE} / ${android.os.Build.BOARD}"
        }
    }.getOrDefault("unknown")
    val abis = android.os.Build.SUPPORTED_ABIS.joinToString(", ")

    val coreIndices = detectCpuCoreIndices()
    val clusters = buildCpuClusters(coreIndices)

    val gpuName = runCatching { NativeBridge.getVulkanGpuName(0) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    val gpuVendor = runCatching { NativeBridge.getGpuVendor() }.getOrDefault("unknown")
    val gpuDeviceType = runCatching { NativeBridge.getGpuDeviceType() }.getOrDefault("unknown")
    val gpuDriverVersion = runCatching { NativeBridge.getGpuDriverVersion() }.getOrDefault("unknown")
    val gpuVramMb = runCatching { NativeBridge.getGpuVramMb() }.getOrDefault(-1L)
    val vulkanApiVersion = runCatching { NativeBridge.getVulkanApiVersion() }.getOrDefault("unknown")

    return StaticProfileInfo(
        modelName = modelName,
        androidVersion = androidVersion,
        soc = soc,
        abis = abis,
        cpuCoreCount = coreIndices.size,
        cpuClusters = clusters,
        gpuName = gpuName,
        gpuVendor = gpuVendor,
        gpuDeviceType = gpuDeviceType,
        gpuDriverVersion = gpuDriverVersion,
        gpuVramMb = gpuVramMb,
        vulkanApiVersion = vulkanApiVersion,
    )
}

private fun captureLiveProfileInfo(context: Context, clusters: List<CpuCluster>): LiveProfileInfo {
    val (rootLabel, rootTone) = readRootStatus()
    val (shizukuLabel, shizukuTone) = readShizukuStatus()

    val clusterFreqs = clusters.associate { cluster ->
        val freqs = cluster.coreIndices.mapNotNull { cpuCurFreqKhz(it) }
        cluster.label to if (freqs.isEmpty()) null else (freqs.average() / 1000.0).toInt()
    }

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    runCatching { am?.getMemoryInfo(memInfo) }
    val totalMb = memInfo.totalMem / (1024 * 1024)
    val availMb = memInfo.availMem / (1024 * 1024)
    val usedMb = (totalMb - availMb).coerceAtLeast(0)

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val metrics = DisplayMetrics()
    val refreshRate = runCatching {
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.let {
            it.getRealMetrics(metrics)
            it.refreshRate
        } ?: 0f
    }.getOrDefault(0f)

    return LiveProfileInfo(
        rootLabel = rootLabel,
        rootTone = rootTone,
        shizukuLabel = shizukuLabel,
        shizukuTone = shizukuTone,
        cpuLoadPercent = CpuLoadTracker.sample(),
        cpuTempC = readCpuTemperatureC(),
        clusterCurFreqMhz = clusterFreqs,
        ramUsedMb = usedMb,
        ramTotalMb = totalMb,
        screenWidth = metrics.widthPixels,
        screenHeight = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        refreshRateHz = refreshRate,
    )
}

private fun readRootStatus(): Pair<String, StatusTone> {
    val granted = runCatching { com.topjohnwu.superuser.Shell.isAppGrantedRoot() }.getOrNull()
    return when (granted) {
        true -> "Granted" to StatusTone.Good
        false -> "Not available" to StatusTone.Bad
        else -> "Unknown" to StatusTone.Neutral
    }
}

private fun readShizukuStatus(): Pair<String, StatusTone> {
    val running = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    val granted = runCatching { ShizukuDisplayPermission.isShizukuAvailable() }.getOrDefault(false)
    return when {
        granted -> "Connected" to StatusTone.Good
        running -> "Permission needed" to StatusTone.Warn
        else -> "Not running" to StatusTone.Neutral
    }
}

// --- CPU topology / frequency -----------------------------------------

private fun readLongFile(path: String): Long? =
    runCatching { File(path).readText().trim().toLong() }.getOrNull()

private fun parseCpuRange(spec: String): List<Int> {
    val result = mutableListOf<Int>()
    spec.split(",").forEach { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@forEach
        if (trimmed.contains("-")) {
            val bounds = trimmed.split("-")
            val a = bounds.getOrNull(0)?.trim()?.toIntOrNull()
            val b = bounds.getOrNull(1)?.trim()?.toIntOrNull()
            if (a != null && b != null) result.addAll(a..b)
        } else {
            trimmed.toIntOrNull()?.let { result.add(it) }
        }
    }
    return result
}

private fun detectCpuCoreIndices(): List<Int> {
    val possible = runCatching { File("/sys/devices/system/cpu/possible").readText().trim() }.getOrNull()
    val parsed = possible?.let { parseCpuRange(it) }
    return if (!parsed.isNullOrEmpty()) parsed else (0 until Runtime.getRuntime().availableProcessors()).toList()
}

private fun cpuMaxFreqKhz(cpu: Int): Int? =
    readLongFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.toInt()

private fun cpuCurFreqKhz(cpu: Int): Int? =
    readLongFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")?.toInt()
        ?: readLongFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_cur_freq")?.toInt()

private fun clusterLabels(n: Int): List<String> = when (n) {
    0 -> emptyList()
    1 -> listOf("All cores")
    2 -> listOf("Little", "Big")
    3 -> listOf("Little", "Mid", "Big")
    else -> List(n) { i ->
        when (i) {
            0 -> "Little"
            n - 1 -> "Prime"
            n - 2 -> "Big"
            else -> "Mid ${i}"
        }
    }
}

private fun buildCpuClusters(coreIndices: List<Int>): List<CpuCluster> {
    val byMaxFreq = coreIndices
        .mapNotNull { cpu -> cpuMaxFreqKhz(cpu)?.let { cpu to it } }
        .groupBy({ it.second }, { it.first })
        .toSortedMap()
    if (byMaxFreq.isEmpty()) {
        return if (coreIndices.isEmpty()) emptyList() else listOf(CpuCluster("All cores", coreIndices, 0))
    }
    val freqs = byMaxFreq.keys.toList()
    val labels = clusterLabels(freqs.size)
    return freqs.mapIndexed { i, freqKhz ->
        CpuCluster(labels[i], byMaxFreq.getValue(freqKhz), freqKhz / 1000)
    }
}

// --- CPU load (delta of /proc/stat between polls) ----------------------

private object CpuLoadTracker {
    @Volatile private var lastTotal: Long = -1
    @Volatile private var lastIdle: Long = -1

    fun sample(): Float? {
        val line = runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull()
            ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0] != "cpu") return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val idle = nums[3] + nums.getOrElse(4) { 0L }
        val total = nums.sum()

        val prevTotal = lastTotal
        val prevIdle = lastIdle
        lastTotal = total
        lastIdle = idle

        if (prevTotal < 0) return null
        val totalDelta = total - prevTotal
        val idleDelta = idle - prevIdle
        if (totalDelta <= 0) return null
        return (((totalDelta - idleDelta).toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
    }
}

// --- CPU temperature -----------------------------------------------------

private fun readCpuTemperatureC(): Float? {
    val base = File("/sys/class/thermal")
    val zones = runCatching { base.listFiles { f -> f.name.startsWith("thermal_zone") } }.getOrNull() ?: return null
    var best: Float? = null
    for (zone in zones) {
        val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: continue
        val looksLikeCpu = type.contains("cpu") || type.contains("soc") ||
            type.contains("tsens") || type.contains("apss") || type.contains("cluster")
        if (!looksLikeCpu) continue
        val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull() ?: continue
        val celsius = if (raw > 1000f) raw / 1000f else raw
        if (celsius in 0f..150f && (best == null || celsius > best!!)) best = celsius
    }
    return best
}
