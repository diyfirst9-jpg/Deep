package com.firstt175.deepdrop.session

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

data class PhysicalDisplayInfo(
    val width: Int,
    val height: Int,
    val dpi: Int,
)

data class AppDisplayProfile(
    val enabled: Boolean = false,
    val percent: Int = 100,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val originalDpi: Int = 0,
    val calculatedWidth: Int = 0,
    val calculatedHeight: Int = 0,
    val calculatedDpi: Int = 0,
    val dynamicClean: Boolean = false,
    val maxBackgroundApps: Int = 1,
    val disableAnimations: Boolean = false,
    val keepAwake: Boolean = false,
)

object AppDisplayProfileStore {
    private const val PREFS = "per_app_display_profiles"
    private const val PREFIX = "app_"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context, pkg: String): AppDisplayProfile {
        val p = prefs(ctx)
        val k = PREFIX + pkg
        return AppDisplayProfile(
            enabled = p.getBoolean("$k.enabled", false),
            percent = p.getInt("$k.percent", 100),
            originalWidth = p.getInt("$k.w", 0),
            originalHeight = p.getInt("$k.h", 0),
            originalDpi = p.getInt("$k.dpi", 0),
            calculatedWidth = p.getInt("$k.calcW", 0),
            calculatedHeight = p.getInt("$k.calcH", 0),
            calculatedDpi = p.getInt("$k.calcDpi", 0),
            dynamicClean = p.getBoolean("$k.clean", false),
            maxBackgroundApps = p.getInt("$k.maxBg", 1),
            disableAnimations = p.getBoolean("$k.anim", false),
            keepAwake = p.getBoolean("$k.awake", false),
        )
    }

    fun save(ctx: Context, pkg: String, profile: AppDisplayProfile) {
        val k = PREFIX + pkg
        prefs(ctx).edit()
            .putBoolean("$k.enabled", profile.enabled)
            .putInt("$k.percent", profile.percent.coerceIn(25, 100))
            .putInt("$k.w", profile.originalWidth)
            .putInt("$k.h", profile.originalHeight)
            .putInt("$k.dpi", profile.originalDpi)
            .putInt("$k.calcW", profile.calculatedWidth)
            .putInt("$k.calcH", profile.calculatedHeight)
            .putInt("$k.calcDpi", profile.calculatedDpi)
            .putBoolean("$k.clean", profile.dynamicClean)
            .putInt("$k.maxBg", profile.maxBackgroundApps.coerceAtLeast(1))
            .putBoolean("$k.anim", profile.disableAnimations)
            .putBoolean("$k.awake", profile.keepAwake)
            .apply()
    }

    fun captureOriginalIfMissing(ctx: Context, pkg: String, info: PhysicalDisplayInfo): AppDisplayProfile {
        val old = load(ctx, pkg)
        if (old.originalWidth > 0 && old.originalHeight > 0 && old.originalDpi > 0) return old
        val calc = calculate(info, old.percent)
        return old.copy(
            originalWidth = info.width,
            originalHeight = info.height,
            originalDpi = info.dpi,
            calculatedWidth = calc.first,
            calculatedHeight = calc.second,
            calculatedDpi = calc.third,
        ).also { save(ctx, pkg, it) }
    }

    fun withPercent(ctx: Context, pkg: String, percent: Int): AppDisplayProfile {
        val old = load(ctx, pkg)
        val base = PhysicalDisplayInfo(
            old.originalWidth,
            old.originalHeight,
            old.originalDpi,
        )
        val calc = calculate(base, percent)
        return old.copy(
            enabled = percent < 100,
            percent = percent.coerceIn(25, 100),
            calculatedWidth = calc.first,
            calculatedHeight = calc.second,
            calculatedDpi = calc.third,
        ).also { save(ctx, pkg, it) }
    }

    fun calculate(info: PhysicalDisplayInfo, percent: Int): Triple<Int, Int, Int> {
        if (info.width <= 0 || info.height <= 0) return Triple(0, 0, info.dpi)
        val scale = percent.coerceIn(25, 100) / 100.0
        // Keep the aspect ratio and use even dimensions for GPU/Vulkan images.
        val w = (((info.width * scale).roundToInt() / 2) * 2).coerceAtLeast(2)
        val h = (((info.height * scale).roundToInt() / 2) * 2).coerceAtLeast(2)
        val dpi = (info.dpi * scale).roundToInt().coerceAtLeast(1)
        return Triple(w, h, dpi)
    }
}

object AdbDisplayController {
    private const val TAG = "LsfgAdbDisplay"
    const val WRITE_SECURE_SETTINGS_COMMAND =
        "adb shell pm grant com.firstt175.deepdrop android.permission.WRITE_SECURE_SETTINGS"
    private const val DISPLAY_ID = android.view.Display.DEFAULT_DISPLAY
    // USER_CURRENT sentinel (android.os.UserHandle.USER_CURRENT), avoids needing
    // a live user id lookup via hidden API.
    private const val USER_CURRENT = -2

    // Android 9+ (API 28+) enforces restrictions on reflective calls into
    // non-SDK (hidden) APIs, which is exactly what IWindowManager access
    // below relies on. Enforcement can vary by OEM/ROM and sometimes throws
    // NoSuchMethodException/SecurityException even when the method exists.
    // WRITE_SECURE_SETTINGS is sufficient to write these Settings.Global
    // keys, which disable that enforcement for the app. This mirrors what
    // the `settings put global hidden_api_policy 1` ADB command does.
    private val HIDDEN_API_POLICY_KEYS = arrayOf(
        "hidden_api_policy",
        "hidden_api_policy_pre_p_apps",
        "hidden_api_policy_p_apps",
    )
    @Volatile
    private var hiddenApiPolicyUnlocked = false

    /**
     * Disables hidden-API enforcement so the IWindowManager reflection calls
     * below run reliably across Android versions/OEMs. Idempotent and safe
     * to call before every reflective operation; only writes once per process.
     */
    private fun unlockHiddenApiPolicyIfNeeded(ctx: Context) {
        if (hiddenApiPolicyUnlocked) return
        runCatching {
            for (key in HIDDEN_API_POLICY_KEYS) {
                Settings.Global.putInt(ctx.contentResolver, key, 1)
            }
            hiddenApiPolicyUnlocked = true
            LsfgLog.i(TAG, "hidden API policy unlocked")
        }.onFailure {
            LsfgLog.e(TAG, "failed to unlock hidden API policy", it)
        }
    }

    /**
     * Display control no longer depends on Shizuku.
     *
     * The user grants WRITE_SECURE_SETTINGS once from a PC/ADB:
     *   adb shell pm grant com.firstt175.deepdrop android.permission.WRITE_SECURE_SETTINGS
     *
     * After that, the app calls WindowManagerService directly through the
     * IWindowManager AIDL interface (the same calls the `wm size`/`wm density`
     * shell commands make), which WRITE_SECURE_SETTINGS is sufficient to
     * authorize without an actual shell UID.
     *
     * NOTE: writing Settings.Global("display_size_forced"/"display_density_forced")
     * directly does NOT work on modern Android (10+): WindowManagerService's
     * DisplayWindowSettings persists forced size/density to
     * /data/system/display_settings.xml and only reads the legacy Settings.Global
     * keys once, as a migration source at boot. A live write from an app is
     * silently ignored — confirmed on-device: the write call reports success
     * but CaptureEngine/DisplayMetrics keep reporting the untouched native
     * resolution and density. Calling IWindowManager directly goes through the
     * same live code path as `wm size`/`wm density` and reconfigures the
     * display immediately.
     */
    private fun windowManagerService(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.WINDOW_SERVICE) as android.os.IBinder
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        stub.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
    }.onFailure { LsfgLog.e(TAG, "windowManagerService() lookup failed", it) }.getOrNull()

    private fun iwm(): Class<*> = Class.forName("android.view.IWindowManager")

    fun isReady(ctx: Context): Boolean {
        val granted = ctx.checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            LsfgLog.w(TAG, "WRITE_SECURE_SETTINGS is not granted")
        }
        return granted
    }

    /** Kept for source compatibility; ADB grants are performed outside the app. */
    fun requestPermission() {
        LsfgLog.w(
            TAG,
            "ADB permission required. Run: $WRITE_SECURE_SETTINGS_COMMAND",
        )
    }

    fun grantCommand(): String = WRITE_SECURE_SETTINGS_COMMAND

    private fun shizukuReady(): Boolean = ShizukuDisplayPermission.isShizukuAvailable()

    private fun shizukuCommandBlocking(command: String): ShizukuDisplayPermission.CommandResult? =
        runCatching { runBlocking { ShizukuDisplayPermission.exec(command) } }
            .onFailure { LsfgLog.e(TAG, "Shizuku command bridge failed: $command", it) }
            .getOrNull()

    private fun readCurrentViaWmShell(): PhysicalDisplayInfo? {
        val size = shizukuCommandBlocking("wm size") ?: return null
        val density = shizukuCommandBlocking("wm density") ?: return null
        if (!size.ok || !density.ok) return null

        val sizeMatch = Regex("Override size:\\s*(\\d+)x(\\d+)").find(size.stdout)
            ?: Regex("Physical size:\\s*(\\d+)x(\\d+)").find(size.stdout)
        val densityMatch = Regex("Override density:\\s*(\\d+)").find(density.stdout)
            ?: Regex("Physical density:\\s*(\\d+)").find(density.stdout)
        val w = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val h = sizeMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val dpi = densityMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || dpi <= 0) return null
        return PhysicalDisplayInfo(w, h, dpi)
    }

    /**
     * Returns the physical panel mode, not an existing forced size override.
     * Stable device density is used as the original DPI baseline.
     */
    fun readDisplay(ctx: Context): PhysicalDisplayInfo? {
        return runCatching {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching null

            val mode = display.mode
            val width = mode.physicalWidth
            val height = mode.physicalHeight
            val dpi = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE

            if (width <= 0 || height <= 0 || dpi <= 0) {
                LsfgLog.w(TAG, "readDisplay: invalid physical display ${width}x${height} @ ${dpi}dpi")
                null
            } else {
                LsfgLog.i(TAG, "readDisplay: physical ${width}x${height} @ ${dpi}dpi")
                PhysicalDisplayInfo(width, height, dpi)
            }
        }.onFailure {
            LsfgLog.e(TAG, "readDisplay failed", it)
        }.getOrNull()
    }

    /**
     * Reads the CURRENT base size/density — i.e. whatever [setForcedDisplaySize]/
     * [setForcedDisplayDensityForUser] most recently applied, or the native
     * panel values if nothing is currently forced. This is the WindowManager
     * counterpart to what `adb shell wm size`/`wm density` print, and is
     * deliberately different from [readDisplay] (which always reports the true
     * physical panel, ignoring any active override).
     */
    private fun readCurrentBaseDisplay(ctx: Context): PhysicalDisplayInfo? {
        if (shizukuReady()) {
            readCurrentViaWmShell()?.let { return it }
        }
        if (!isReady(ctx)) return null
        return runCatching {
            val wm = windowManagerService() ?: return@runCatching null
            val cls = iwm()
            val point = android.graphics.Point()
            cls.getMethod("getBaseDisplaySize", Int::class.javaPrimitiveType, android.graphics.Point::class.java)
                .invoke(wm, DISPLAY_ID, point)
            val density = cls.getMethod("getBaseDisplayDensity", Int::class.javaPrimitiveType)
                .invoke(wm, DISPLAY_ID) as Int
            if (point.x <= 0 || point.y <= 0 || density <= 0) null
            else PhysicalDisplayInfo(point.x, point.y, density)
        }.onFailure {
            LsfgLog.e(TAG, "readCurrentBaseDisplay failed: ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrNull()
    }

    /**
     * Safety net for the launcher's home screen: compares the display's
     * current base size/density against the true native panel values and,
     * if they don't match, immediately restores the native ones.
     *
     * This catches any forced size/density left over from a session that
     * never got to clean up after itself — e.g. the app or the target game
     * was killed/crashed mid-session, the device rebooted into a stale
     * `display_settings.xml` state, or [DisplayOverrideState] lost track of
     * the owner for any other reason — instead of leaving the whole system
     * scaled down until the user notices and reboots.
     *
     * Returns true if drift was detected and a reset was attempted.
     */
    fun restoreIfDrifted(ctx: Context): Boolean {
        if (!DisplayOverrideState.isPersistentlyActive(ctx)) return false
        if (!isReady(ctx) && !shizukuReady()) return false
        val native = readDisplay(ctx) ?: return false
        val current = readCurrentBaseDisplay(ctx) ?: return false

        val drifted = current.width != native.width ||
            current.height != native.height ||
            current.dpi != native.dpi
        if (!drifted) return false

        LsfgLog.w(
            TAG,
            "restoreIfDrifted: current ${current.width}x${current.height}@${current.dpi}dpi != " +
                "native ${native.width}x${native.height}@${native.dpi}dpi — resetting",
        )
        val restored = reset(ctx)
        DisplayOverrideState.clear(ctx)
        LsfgLog.i(TAG, "restoreIfDrifted: reset() returned $restored")
        return true
    }

    fun apply(ctx: Context, profile: AppDisplayProfile): Boolean {
        val secureReady = isReady(ctx)
        if ((!secureReady && !shizukuReady()) || profile.originalWidth <= 0 || profile.originalHeight <= 0) {
            LsfgLog.w(TAG, "apply skipped: secure=$secureReady shizuku=${shizukuReady()} original=${profile.originalWidth}x${profile.originalHeight}")
            return false
        }

        if (profile.percent >= 100) return reset(ctx)

        val (targetWidth, targetHeight, targetDpi) = AppDisplayProfileStore.calculate(
            PhysicalDisplayInfo(profile.originalWidth, profile.originalHeight, profile.originalDpi),
            profile.percent,
        )
        if (targetWidth <= 0 || targetHeight <= 0 || targetDpi <= 0) return false

        // Prefer the same shell path as `adb shell wm ...` when Shizuku is
        // available. It updates WindowManagerService directly and avoids
        // hidden-API reflection on OEM builds.
        if (shizukuReady()) {
            val size = shizukuCommandBlocking("wm size ${targetWidth}x${targetHeight}")
            if (size?.ok == true) {
                val density = shizukuCommandBlocking("wm density $targetDpi")
                if (density?.ok == true) {
                    val current = readCurrentViaWmShell()
                    val verified = current?.width == targetWidth && current.height == targetHeight && current.dpi == targetDpi
                    if (verified) {
                        LsfgLog.i(TAG, "apply verified via Shizuku: ${targetWidth}x${targetHeight}@${targetDpi}")
                        return true
                    }
                }
            }
            // Never leave a partially applied override behind.
            shizukuCommandBlocking("wm size reset")
            shizukuCommandBlocking("wm density reset")
            LsfgLog.w(TAG, "apply via Shizuku did not verify; override rolled back")
            if (!secureReady) return false
        }

        if (!secureReady) return false

        // Last-resort compatibility path for builds where Shizuku is absent.
        return runCatching {
            val wm = windowManagerService() ?: return@runCatching false
            val cls = iwm()
            val okSize = runCatching {
                cls.getMethod("setForcedDisplaySize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(wm, DISPLAY_ID, targetWidth, targetHeight)
                true
            }.getOrDefault(false)
            val okDensity = if (okSize) setForcedDensity(wm, cls, targetDpi) else false
            if (!okSize || !okDensity) {
                reset(ctx)
                false
            } else {
                val current = readCurrentBaseDisplay(ctx)
                val verified = current?.width == targetWidth && current.height == targetHeight && current.dpi == targetDpi
                if (!verified) reset(ctx)
                verified
            }
        }.onFailure {
            LsfgLog.e(TAG, "apply fallback failed", it)
        }.getOrDefault(false)
    }

    /**
     * Tries every known IWindowManager signature/user-id combination for
     * forcing display density, in order, stopping at the first one that
     * doesn't throw. Logs each failed attempt with the concrete exception
     * type/message so a real failure is diagnosable from logcat instead of
     * just "resolution changed, DPI silently didn't".
     */
    private fun setForcedDensity(wm: Any, cls: Class<*>, densityDpi: Int): Boolean {
        // Clear any density this app previously forced before setting a new
        // value: on some OEM builds a second setForcedDisplayDensityForUser
        // call while a prior forced value from an earlier/interrupted
        // session is still active is a silent no-op.
        runCatching {
            cls.getMethod(
                "clearForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, DISPLAY_ID, USER_CURRENT)
        }

        val attempts: List<Pair<String, () -> Unit>> = listOf(
            "setForcedDisplayDensityForUser(USER_CURRENT)" to {
                cls.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, densityDpi, USER_CURRENT)
            },
            "setForcedDisplayDensityForUser(resolvedUserId)" to {
                val resolvedUser = resolveCurrentUserId()
                cls.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, densityDpi, resolvedUser)
            },
            "setForcedDisplayDensity(legacy)" to {
                // Pre-N / OEM fallback signature without a user id.
                cls.getMethod(
                    "setForcedDisplayDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, densityDpi)
            },
        )

        for ((label, attempt) in attempts) {
            val result = runCatching(attempt)
            if (result.isSuccess) {
                LsfgLog.i(TAG, "setForcedDisplayDensity via $label succeeded (dpi=$densityDpi)")
                return true
            }
            val err = result.exceptionOrNull()
            LsfgLog.w(TAG, "setForcedDisplayDensity via $label failed: ${err?.javaClass?.simpleName}: ${err?.message}", err)
        }
        return false
    }

    /** Best-effort resolution of the actual current-user id, as a fallback for OEMs that mishandle USER_CURRENT (-2) from an app process. */
    private fun resolveCurrentUserId(): Int = runCatching {
        Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
    }.getOrElse {
        runCatching {
            val handle = android.os.Process.myUserHandle()
            Class.forName("android.os.UserHandle").getMethod("getIdentifier").invoke(handle) as Int
        }.getOrDefault(0)
    }

    fun reset(ctx: Context): Boolean {
        var shellReset = false
        if (shizukuReady()) {
            val size = shizukuCommandBlocking("wm size reset")
            val density = shizukuCommandBlocking("wm density reset")
            shellReset = size?.ok == true && density?.ok == true
            if (shellReset) {
                val native = readDisplay(ctx)
                val current = readCurrentViaWmShell()
                if (native != null && current != null &&
                    current.width == native.width && current.height == native.height && current.dpi == native.dpi) {
                    LsfgLog.i(TAG, "reset verified via Shizuku")
                    DisplayOverrideState.clear(ctx)
                    return true
                }
            }
        }

        if (!isReady(ctx)) return shellReset
        val wm = windowManagerService() ?: return shellReset
        val cls = iwm()
        val okSize = runCatching {
            cls.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType).invoke(wm, DISPLAY_ID)
            true
        }.getOrDefault(false)
        val okDensity = runCatching {
            cls.getMethod("clearForcedDisplayDensityForUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, DISPLAY_ID, USER_CURRENT)
            true
        }.recoverCatching {
            cls.getMethod("clearForcedDisplayDensityForUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, DISPLAY_ID, resolveCurrentUserId())
            true
        }.recoverCatching {
            cls.getMethod("clearForcedDisplayDensity", Int::class.javaPrimitiveType).invoke(wm, DISPLAY_ID)
            true
        }.getOrDefault(false)
        val native = readDisplay(ctx)
        val current = readCurrentBaseDisplay(ctx)
        val verified = native != null && current != null &&
            native.width == current.width && native.height == current.height && native.dpi == current.dpi
        DisplayOverrideState.clear(ctx)
        LsfgLog.i(TAG, "reset: okSize=$okSize okDensity=$okDensity verified=$verified")
        return (okSize || okDensity || shellReset) && verified
    }

    /**
     * These global settings are also covered by WRITE_SECURE_SETTINGS, so they
     * no longer need a Shizuku shell.
     */
    fun getActivityManagerConstants(ctx: Context): String? =
        Settings.Global.getString(ctx.contentResolver, "activity_manager_constants")

    fun restoreActivityManagerConstants(ctx: Context, value: String?) {
        val resolver = ctx.contentResolver
        if (value == null || value == "null" || value.isBlank()) {
            Settings.Global.putString(resolver, "activity_manager_constants", null)
        } else {
            Settings.Global.putString(resolver, "activity_manager_constants", value)
        }
    }

    fun setMaxBackgroundApps(ctx: Context, limit: Int) {
        val n = limit.coerceAtLeast(1)
        Settings.Global.putString(
            ctx.contentResolver,
            "activity_manager_constants",
            "max_cached_processes=$n",
        )
    }

    // ---- System animation scale (Settings.Global, same WRITE_SECURE_SETTINGS
    // permission as the display-size controls above). Turning these to 0
    // removes every system transition/window animation, which shaves a small
    // but real amount of per-frame overhead — the same effect as enabling
    // Developer Options → "Window/Transition/Animator duration scale: Off".
    private val ANIMATION_SCALE_KEYS = arrayOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    fun areAnimationsEnabled(ctx: Context): Boolean {
        val scale = Settings.Global.getFloat(ctx.contentResolver, "window_animation_scale", 1f)
        return scale > 0f
    }

    fun setAnimationsEnabled(ctx: Context, enabled: Boolean): Boolean {
        if (!isReady(ctx)) return false
        val scale = if (enabled) 1f else 0f
        return runCatching {
            for (key in ANIMATION_SCALE_KEYS) {
                Settings.Global.putFloat(ctx.contentResolver, key, scale)
            }
            LsfgLog.i(TAG, "setAnimationsEnabled($enabled)")
            true
        }.onFailure {
            LsfgLog.e(TAG, "setAnimationsEnabled failed", it)
        }.getOrDefault(false)
    }

    // ---- Stay awake while charging. BATTERY_PLUGGED_AC(1) | USB(2) | WIRELESS(4).
    // Useful during a session so the screen doesn't dim/lock mid-game when the
    // device is on a charger; restored to whatever the user had before.
    private const val STAY_ON_KEY = "stay_on_while_plugged_in"
    private const val STAY_ON_ALL_SOURCES = 7

    fun getStayOnWhilePluggedIn(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, STAY_ON_KEY, 0)

    fun isStayAwakeEnabled(ctx: Context): Boolean = getStayOnWhilePluggedIn(ctx) != 0

    fun setStayOnWhilePluggedIn(ctx: Context, value: Int): Boolean {
        if (!isReady(ctx)) return false
        return runCatching {
            Settings.Global.putInt(ctx.contentResolver, STAY_ON_KEY, value)
            LsfgLog.i(TAG, "setStayOnWhilePluggedIn($value)")
            true
        }.onFailure {
            LsfgLog.e(TAG, "setStayOnWhilePluggedIn failed", it)
        }.getOrDefault(false)
    }

    fun enableStayAwake(ctx: Context): Boolean = setStayOnWhilePluggedIn(ctx, STAY_ON_ALL_SOURCES)

    /**
     * There is no supported in-app ADB equivalent of `am kill-all` after
     * switching away from Shizuku. Display resolution/DPI remain fully
     * functional; this optional cleanup is intentionally a no-op.
     */
    fun killCachedProcesses() {
        LsfgLog.i(TAG, "killCachedProcesses skipped: no shell/Shizuku dependency")
    }
}
