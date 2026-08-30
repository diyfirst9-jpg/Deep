@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.firstt175.deepdrop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.DisplaySettings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.session.AdbDisplayController
import com.firstt175.deepdrop.session.AppDisplayProfile
import com.firstt175.deepdrop.session.AppDisplayProfileStore
import com.firstt175.deepdrop.session.DisplayOverrideState
import com.firstt175.deepdrop.session.LauncherLayout
import com.firstt175.deepdrop.session.LauncherLayoutStore
import com.firstt175.deepdrop.session.LsfgForegroundService
import com.firstt175.deepdrop.session.LsfgLog
import com.firstt175.deepdrop.session.PermissionsHelper
import com.firstt175.deepdrop.session.PhysicalDisplayInfo
import com.firstt175.deepdrop.session.ShizukuDisplayPermission
import com.firstt175.deepdrop.ui.components.GamepadHint
import com.firstt175.deepdrop.ui.components.GamepadHintOverlay
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgLogoMark
import com.firstt175.deepdrop.ui.components.LsfgSecondaryButton
import com.firstt175.deepdrop.ui.theme.LsfgPrimary
import com.firstt175.deepdrop.ui.theme.LsfgStatusGood
import com.firstt175.deepdrop.ui.theme.LsfgStatusWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LaunchableApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val isGame: Boolean,
)

// Icons are only ever shown at 54-58dp in the launcher list/grid. Raw
// adaptive-icon drawables from loadIcon() can rasterize much larger than
// that (themed/adaptive layers are commonly 432x432 or more on modern
// devices), and loadLaunchableApps() decodes one for every launchable app
// on the device up front — with 150-300 apps installed that's tens to
// low hundreds of MB of bitmap memory held for the app's whole lifetime.
// Downsampling each icon to a small fixed size here cuts that by roughly
// an order of magnitude with no visible quality loss at launcher-tile size.
private const val ICON_CACHE_PX = 128

private fun Drawable.toDownsampledBitmapDrawable(context: Context): Drawable {
    val bitmap = try {
        android.graphics.Bitmap.createBitmap(
            ICON_CACHE_PX,
            ICON_CACHE_PX,
            android.graphics.Bitmap.Config.ARGB_8888,
        ).also { bmp ->
            val canvas = android.graphics.Canvas(bmp)
            setBounds(0, 0, ICON_CACHE_PX, ICON_CACHE_PX)
            draw(canvas)
        }
    } catch (_: Throwable) {
        return this
    }
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .asSequence()
        .mapNotNull { info ->
            val ai = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null
            LaunchableApp(
                label = ai.loadLabel(pm).toString().ifBlank { ai.packageName },
                packageName = ai.packageName,
                icon = ai.loadIcon(pm).toDownsampledBitmapDrawable(context),
                isGame = ai.category == ApplicationInfo.CATEGORY_GAME,
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<LaunchableApp> { it.isGame }.thenBy { it.label.lowercase() })
        .toList()
}

private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    context.startActivity(intent)
}

/**
 * Refresh rates the device's default display can actually run at, read from
 * its [Display.Mode] list (all modes share resolution class but differ in
 * Hz on most phones). Falls back to just the display's current refresh
 * rate if the mode list can't be read for some reason.
 */
private fun getSupportedRefreshRates(context: Context): List<Int> {
    val display = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay
    }.getOrNull() ?: return emptyList()

    val fromModes = runCatching {
        display.supportedModes
            ?.map { it.refreshRate }
            .orEmpty()
    }.getOrElse { emptyList() }

    val rates = fromModes.ifEmpty {
        runCatching { listOf(display.refreshRate) }.getOrElse { emptyList() }
    }

    return rates
        .map { Math.round(it) }
        .filter { it > 0 }
        .distinct()
        .sorted()
}

private const val TAG_PRE_LAUNCH_DISPLAY = "LsfgPreLaunchDisplay"

// How long to wait after starting the target app's Activity before forcing
// the resolution/DPI override, so the app is already in the foreground
// (per product decision: open the app first, then apply the scaling —
// not the other way around). There's no cross-process "target process is
// now up" hook available here without Shizuku/root, so this is a short
// fixed delay rather than an exact signal.
private const val POST_LAUNCH_DISPLAY_DELAY_MS = 600L

/**
 * Applies (or clears) the per-app forced size/density override AFTER the
 * target app has been launched and given a moment to reach the foreground.
 *
 * Returns the resolved profile so the caller can log/display it.
 */
private suspend fun applyDisplayProfileAfterLaunch(
    context: Context,
    packageName: String,
): AppDisplayProfile? {
    if (!AdbDisplayController.isReady(context)) return null
    return runCatching {
        val current = AdbDisplayController.readDisplay(context) ?: return@runCatching null
        // Always (re)derive from the true physical panel size/stable density,
        // never from whatever size/density might currently be force-applied
        // from a previous session, so the percent-based calculation can't
        // compound across launches.
        val stored = AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, current)
        if (stored.originalWidth <= 0 || stored.originalHeight <= 0) return@runCatching stored

        if (stored.enabled && stored.percent < 100) {
            val applied = AdbDisplayController.apply(context, stored)
            if (applied) {
                DisplayOverrideState.markApplied(context, packageName)
            }
            LsfgLog.i(
                TAG_PRE_LAUNCH_DISPLAY,
                "Post-launch display for $packageName: ${stored.percent}% -> " +
                    "${stored.calculatedWidth}x${stored.calculatedHeight} @ ${stored.calculatedDpi}dpi " +
                    "applied=$applied",
            )
        } else {
            // No per-app override for this app (or 100%): clear any stale
            // forced size/density left over from a previous app's session.
            AdbDisplayController.reset(context)
            DisplayOverrideState.clear(context)
        }
        stored
    }.onFailure {
        LsfgLog.e(TAG_PRE_LAUNCH_DISPLAY, "applyDisplayProfileAfterLaunch failed", it)
    }.getOrNull()
}

@Composable
fun GameLauncherScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var showAll by remember { mutableStateOf(true) }

    // Setup and automatic-overlay entry points now live here on the launcher
    // (first page) instead of the settings screen. Setup is re-checked every
    // time this screen is (re)composed — which happens fresh whenever the
    // user navigates back to it — so it disappears on its own once every
    // permission has been granted, without needing a manual refresh.
    val prefs = remember { LsfgPreferences(context) }
    val configState by produceConfigState(prefs).collectAsState()
    val setupComplete = PermissionsHelper.isSetupComplete(context)
    val autoOverlayCount = configState.autoEnabledApps.size

    // How app icons are arranged: single-column list or icon grid.
    // Persisted so the choice sticks across app restarts.
    var layoutMode by remember { mutableStateOf(LauncherLayoutStore.load(context)) }
    var showLayoutMenu by remember { mutableStateOf(false) }

    suspend fun launchApp(app: LaunchableApp) {
        val profile = withContext(Dispatchers.IO) {
            AppDisplayProfileStore.load(context, app.packageName)
        }

        // Open the app first. The resolution/DPI profile (below) is applied
        // only after the app has actually launched and had a moment to come
        // to the foreground — not before, so tapping a card always enters
        // the app immediately instead of waiting on a WindowManager call.
        // The LSFG overlay itself, when enabled, is handled separately once
        // the target app is in foreground; the user can press Start from
        // that overlay.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Toast.makeText(context, context.getString(R.string.toast_no_launch_button, app.label), Toast.LENGTH_SHORT).show()
            return
        }

        if (profile.enabled) {
            delay(POST_LAUNCH_DISPLAY_DELAY_MS)
            withContext(Dispatchers.IO) { applyDisplayProfileAfterLaunch(context, app.packageName) }
        }
    }

    // loadLaunchableApps() queries every launcher activity and decodes an icon
    // for each one — real work, not instant. Running it on Dispatchers.IO
    // keeps the UI thread free while it happens.
    suspend fun refresh() {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (AdbDisplayController.isReady(context) && !LsfgForegroundService.isRunning.value) {
                // Safety net: every time the Deepdrop launcher itself comes
                // up (and no LSFG session currently owns the display), make
                // sure the display is actually at native resolution/DPI. If
                // a previous session's forced override was left applied
                // (crash, kill, reboot with a stale display_settings.xml,
                // etc.) this restores it immediately instead of leaving the
                // whole system scaled down until the user notices. Skipped
                // while a session is running so it can't undo a legitimate,
                // currently-active override for a backgrounded game.
                AdbDisplayController.restoreIfDrifted(context)
            }
        }
        refresh()
    }

    // remember(apps) so the filter only re-runs when the app list actually
    // changes (refresh), not on every recomposition triggered by showAll.
    val games = remember(apps) { apps.filter { it.isGame } }

    // Requesting focus here (rather than on some inner element) is what lets
    // onPreviewKeyEvent below see L1/R1 regardless of which row/tile in the
    // list currently has touch focus.
    val gamepadFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { gamepadFocusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(gamepadFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    // Mirrors the Games/All apps FilterChips above: L1 = previous
                    // tab (games), R1 = next tab (all apps).
                    Key.ButtonL1 -> { showAll = false; true }
                    Key.ButtonR1 -> { showAll = true; true }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LsfgLogoMark(size = 30.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Deepdrop",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Box {
                    IconButton(onClick = { showLayoutMenu = true }) {
                        Icon(
                            when (layoutMode) {
                                LauncherLayout.LIST -> Icons.Filled.ViewAgenda
                                LauncherLayout.GRID -> Icons.Filled.GridView
                            },
                            contentDescription = stringResource(R.string.cd_layout_mode),
                        )
                    }
                    DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layout_list)) },
                            leadingIcon = { Icon(Icons.Filled.ViewAgenda, contentDescription = null) },
                            onClick = {
                                layoutMode = LauncherLayout.LIST
                                LauncherLayoutStore.save(context, LauncherLayout.LIST)
                                showLayoutMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layout_grid)) },
                            leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null) },
                            onClick = {
                                layoutMode = LauncherLayout.GRID
                                LauncherLayoutStore.save(context, LauncherLayout.GRID)
                                showLayoutMenu = false
                            },
                        )
                    }
                }
                IconButton(onClick = { scope.launch { refresh() } }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }

            // Shows only until every Setup item is granted, then disappears on
            // its own — no need to dismiss it manually.
            if (!setupComplete) {
                LsfgSecondaryButton(
                    text = "Setup — grant all permissions",
                    leadingIcon = Icons.Filled.Accessibility,
                    onClick = { nav.navigate(Routes.SETUP) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = !showAll,
                    onClick = { showAll = false },
                    label = { Text(stringResource(R.string.filter_games_count, games.size)) },
                    leadingIcon = { Icon(Icons.Filled.Gamepad, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = true },
                    label = { Text(stringResource(R.string.filter_all_apps_count, apps.size)) },
                    leadingIcon = { Icon(Icons.Filled.Apps, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = { nav.navigate(Routes.AUTOMATIC_OVERLAY) },
                    label = {
                        Text(
                            if (autoOverlayCount == 0) {
                                stringResource(R.string.automatic_overlay_count_zero)
                            } else {
                                stringResource(R.string.automatic_overlay_count_n, autoOverlayCount)
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = { nav.navigate(Routes.PROFILE) },
                    label = { Text(stringResource(R.string.profile_button)) },
                    leadingIcon = { Icon(Icons.Filled.AccountCircle, null, Modifier.size(18.dp)) },
                )
            }

            // Crossfade keyed on showAll: the "Games"/"All apps" chips swap the
            // entire list content instantly otherwise, which reads as a jarring
            // flash rather than a tab change.
            Crossfade(targetState = showAll, label = "launcherList") { all ->
                val listForTab = if (all) apps else games
                if (listForTab.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Apps, null, Modifier.size(54.dp), tint = LsfgStatusWarn)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.empty_no_apps_title), fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    when (layoutMode) {
                        LauncherLayout.LIST -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(listForTab, key = { it.packageName }) { app ->
                                LauncherAppRow(nav = nav, app = app) {
                                    scope.launch { launchApp(app) }
                                }
                            }
                        }
                        LauncherLayout.GRID -> LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(listForTab, key = { it.packageName }) { app ->
                                LauncherAppTile(nav = nav, app = app) {
                                    scope.launch { launchApp(app) }
                                }
                            }
                        }
                    }
                }
            }
        }

    GamepadHintOverlay(
        hints = listOf(
            GamepadHint("L1/R1", stringResource(R.string.gamepad_hint_switch_tab)),
            GamepadHint("A", stringResource(R.string.gamepad_hint_launch_app)),
            GamepadHint("B", stringResource(R.string.gamepad_hint_back)),
        ),
    )
    }
}

/**
 * Long-press context menu shared by every layout (list row, grid tile,
 * Switch-style tile): settings, app info — the same pair a regular
 * Android home screen shows on long-press, minus uninstall.
 */
@Composable
private fun AppQuickActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onAppInfo: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_settings)) },
            leadingIcon = { Icon(Icons.Filled.DisplaySettings, contentDescription = null) },
            onClick = onSettings,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_app_info)) },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = onAppInfo,
        )
    }
}

@Composable
private fun LauncherAppRow(
    nav: NavHostController,
    app: LaunchableApp,
    onLaunch: () -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(AppDisplayProfileStore.load(context, app.packageName)) }

    Box(modifier = Modifier.fillMaxWidth()) {
    LsfgCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = { showMenu = true },
            ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(app.icon),
                contentDescription = null,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(13.dp)),
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (profile.enabled) {
                    Text(
                        "${profile.percent}% • ${profile.calculatedWidth}×${profile.calculatedHeight} • ${profile.calculatedDpi}dpi",
                        style = MaterialTheme.typography.labelSmall,
                        color = LsfgPrimary,
                    )
                }
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.DisplaySettings, contentDescription = stringResource(R.string.cd_app_settings))
            }
            Button(
                onClick = onLaunch,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LsfgPrimary),
            ) {
                Icon(Icons.Filled.Apps, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.action_launch), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    AppQuickActionsMenu(
        expanded = showMenu,
        onDismiss = { showMenu = false },
        onSettings = { showMenu = false; showSettings = true },
        onAppInfo = { showMenu = false; openAppInfo(context, app.packageName) },
    )
    }

    if (showSettings) {
        AppCardSettingsDialog(
            nav = nav,
            packageName = app.packageName,
            label = app.label,
            initial = profile,
            onDismiss = { showSettings = false },
            onSaved = {
                profile = it
                showSettings = false
            },
        )
    }
}

@Composable
private fun LauncherAppTile(
    nav: NavHostController,
    app: LaunchableApp,
    onLaunch: () -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(AppDisplayProfileStore.load(context, app.packageName)) }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onLaunch,
                    onLongClick = { showMenu = true },
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Image(
                    painter = rememberAsyncImagePainter(app.icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                if (profile.enabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(13.dp)
                            .clip(RoundedCornerShape(50))
                            .background(LsfgPrimary),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        AppQuickActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onSettings = { showMenu = false; showSettings = true },
            onAppInfo = { showMenu = false; openAppInfo(context, app.packageName) },
        )
    }

    if (showSettings) {
        AppCardSettingsDialog(
            nav = nav,
            packageName = app.packageName,
            label = app.label,
            initial = profile,
            onDismiss = { showSettings = false },
            onSaved = {
                profile = it
                showSettings = false
            },
        )
    }
}

private const val TAG_APP_CARD_SETTINGS = "LsfgAppCardSettings"

@Composable
private fun AppCardSettingsDialog(
    nav: NavHostController,
    packageName: String,
    label: String,
    initial: AppDisplayProfile,
    onDismiss: () -> Unit,
    onSaved: (AppDisplayProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(initial) }
    var percent by remember { mutableStateOf(initial.percent.toFloat()) }
    var clean by remember { mutableStateOf(initial.dynamicClean) }
    var noAnimations by remember { mutableStateOf(initial.disableAnimations) }
    var keepAwake by remember { mutableStateOf(initial.keepAwake) }
    var fixedPerfMode by remember { mutableStateOf(initial.fixedPerformanceMode) }
    var dozeWhitelist by remember { mutableStateOf(initial.dozeWhitelist) }
    var forceStopBg by remember { mutableStateOf(initial.forceStopBackground) }
    var refreshRateHz by remember { mutableStateOf(initial.lockRefreshRateHz) }
    var wifiLock by remember { mutableStateOf(initial.wifiHighPerfLock) }
    var info by remember { mutableStateOf<PhysicalDisplayInfo?>(null) }
    val supportedHz = remember { getSupportedRefreshRates(context) }
    val refreshRateOptions = remember(supportedHz) { listOf(0) + supportedHz }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_app_settings_title, label)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val secureGranted = ShizukuDisplayPermission.hasWriteSecureSettings(context)
                // Shizuku/WRITE_SECURE_SETTINGS granting itself now lives only
                // on the single Setup screen — this dialog just flags it here
                // when it's still missing, instead of duplicating the flow.
                if (!secureGranted) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 3.dp,
                    ) {
                        Column(
                            Modifier
                                .padding(14.dp)
                                .clickable { nav.navigate(Routes.SETUP) },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.status_no_permission), fontWeight = FontWeight.Bold)
                            Text(
                                "Go to Setup to grant it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (profile.originalWidth > 0) {
                    Text(stringResource(R.string.display_original_format, profile.originalWidth, profile.originalHeight, profile.originalDpi))
                    Text(stringResource(R.string.display_calculated_format, profile.calculatedWidth, profile.calculatedHeight, profile.calculatedDpi))
                } else {
                    Text(stringResource(R.string.display_original_not_saved))
                }
                Text(stringResource(R.string.display_resolution_percent, percent.toInt()))
                Slider(
                    value = percent,
                    onValueChange = { percent = ((it / 5f).toInt() * 5).coerceIn(25, 100).toFloat() },
                    valueRange = 25f..100f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background Dynamic Clean", fontWeight = FontWeight.SemiBold)
                    }
                    Switch(checked = clean, onCheckedChange = { clean = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.disable_animations_title), fontWeight = FontWeight.SemiBold)
                    }
                    Switch(checked = noAnimations, onCheckedChange = { noAnimations = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.keep_awake_title), fontWeight = FontWeight.SemiBold)
                    }
                    Switch(checked = keepAwake, onCheckedChange = { keepAwake = it })
                }
                val shizukuReady = ShizukuDisplayPermission.isShizukuAvailable()
                Text(
                    "Performance (requires Shizuku)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!shizukuReady) {
                    Text(
                        "Shizuku is not connected — these won't apply until it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fixed Performance Mode", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Locks CPU/GPU clocks at max for the session",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = fixedPerfMode, onCheckedChange = { fixedPerfMode = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Doze Whitelist", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Keeps background services (voice chat, etc.) from being throttled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = dozeWhitelist, onCheckedChange = { dozeWhitelist = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Force-Stop Background Apps", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Kills other installed apps outright before launch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = forceStopBg, onCheckedChange = { forceStopBg = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi High-Perf Lock", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Stops the Wi-Fi radio from sleeping between packets (reduces ping spikes, not baseline ping)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wifiLock, onCheckedChange = { wifiLock = it })
                }
                Text("Lock Refresh Rate", fontWeight = FontWeight.SemiBold)
                if (supportedHz.isEmpty()) {
                    Text(
                        "Couldn't read this device's supported refresh rates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    refreshRateOptions.forEach { hz ->
                        FilterChip(
                            selected = refreshRateHz == hz,
                            onClick = { refreshRateHz = hz },
                            label = { Text(if (hz == 0) "Auto" else "${hz}Hz") },
                        )
                    }
                }
                Text(
                    stringResource(R.string.limit_background_title),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (!AdbDisplayController.isReady(context)) {
                    if (ShizukuDisplayPermission.isShizukuAvailable()) {
                        scope.launch {
                            val granted = ShizukuDisplayPermission.grantWriteSecureSettings(context)
                            if (!granted) Toast.makeText(context, context.getString(R.string.toast_shizuku_grant_failed), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        AdbDisplayController.requestPermission()
                        val command = AdbDisplayController.grantCommand()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", command))
                        Toast.makeText(context, context.getString(R.string.toast_no_permission_copied_adb), Toast.LENGTH_LONG).show()
                    }
                    return@Button
                }
                val current = AdbDisplayController.readDisplay(context)
                if (current == null) {
                    LsfgLog.w(TAG_APP_CARD_SETTINGS, "Save[$packageName]: readDisplay() returned null, see LsfgAdbDisplay log above")
                    Toast.makeText(context, context.getString(R.string.toast_read_display_failed), Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val captured = AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, current)
                val saved = AppDisplayProfileStore.withPercent(context, packageName, percent.toInt()).copy(
                    dynamicClean = clean,
                    maxBackgroundApps = 1,
                    disableAnimations = noAnimations,
                    keepAwake = keepAwake,
                    fixedPerformanceMode = fixedPerfMode,
                    dozeWhitelist = dozeWhitelist,
                    forceStopBackground = forceStopBg,
                    lockRefreshRateHz = refreshRateHz,
                    wifiHighPerfLock = wifiLock,
                ).also { AppDisplayProfileStore.save(context, packageName, it) }
                onSaved(saved)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Button(onClick = {
                if (!AdbDisplayController.isReady(context)) {
                    LsfgLog.w(TAG_APP_CARD_SETTINGS, "ReadReal[$packageName]: not ready, prompting for permission")
                    AdbDisplayController.requestPermission()
                    val command = AdbDisplayController.grantCommand()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", command))
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_adb_copied_with_command, command),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val current = AdbDisplayController.readDisplay(context)
                    if (current == null) {
                        LsfgLog.w(TAG_APP_CARD_SETTINGS, "ReadReal[$packageName]: readDisplay() returned null, see LsfgAdbDisplay log above")
                    }
                    if (current != null) info = current
                    val captured = current?.let { AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, it) }
                    if (captured != null) {
                        profile = captured
                        percent = captured.percent.toFloat()
                    }
                }
            }) { Text(stringResource(R.string.action_read_real_display)) }
        },
    )
}
