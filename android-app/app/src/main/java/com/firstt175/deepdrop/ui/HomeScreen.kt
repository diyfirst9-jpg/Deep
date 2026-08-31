package com.firstt175.deepdrop.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.R
import com.firstt175.deepdrop.prefs.AppLanguage
import com.firstt175.deepdrop.prefs.AppLanguagePrefs
import com.firstt175.deepdrop.prefs.CaptureSource
import com.firstt175.deepdrop.prefs.LsfgPreferences
import com.firstt175.deepdrop.session.CrashReporter
import com.firstt175.deepdrop.session.PermissionsHelper
import com.firstt175.deepdrop.ui.components.GamepadHint
import com.firstt175.deepdrop.ui.components.GamepadHintOverlay
import com.firstt175.deepdrop.ui.components.CollapsibleSection
import com.firstt175.deepdrop.ui.components.IconBadge
import com.firstt175.deepdrop.ui.components.LsfgLogoMark
import com.firstt175.deepdrop.ui.components.StatusTone
import com.firstt175.deepdrop.ui.components.StepCard
import com.firstt175.deepdrop.ui.theme.LsfgStatusGood
import com.firstt175.deepdrop.ui.theme.LsfgStatusWarn

@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    val clipboard = LocalClipboardManager.current

    var showMoreMenu by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var crashPreview by remember { mutableStateOf("") }
    var showCrashDetail by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (CrashReporter.hasPendingCrash(ctx)) {
            crashPreview = CrashReporter.readCrashSummary(ctx)
            // Move the crash file aside immediately so the dialog never
            // reappears on the next launch (e.g. if the user swipes the app
            // away instead of tapping a button). The file is still kept
            // under last_crash_seen.txt so the share chip can attach it.
            CrashReporter.markPendingCrashSeen(ctx)
            showCrashDialog = true
        }
    }

    if (showCrashDialog) {
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            icon = {
                IconBadge(
                    icon = Icons.Filled.BugReport,
                    tint = MaterialTheme.colorScheme.tertiary,
                    size = 44.dp,
                )
            },
            title = { Text(stringResource(R.string.crash_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.crash_dialog_body))
                    if (showCrashDetail) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = crashPreview.take(4000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(crashPreview))
                            Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Copy")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CrashReporter.clearPendingCrash(ctx)
                    showCrashDialog = false
                    nav.navigate(Routes.LOG_VIEWER)
                }) { Text("View log") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showCrashDetail = !showCrashDetail }) {
                        Text(stringResource(R.string.crash_dialog_view))
                    }
                    TextButton(onClick = {
                        CrashReporter.clearPendingCrash(ctx)
                        showCrashDialog = false
                    }) { Text(stringResource(R.string.crash_dialog_dismiss)) }
                }
            },
        )
    }

    val a11yEnabled = PermissionsHelper.isAccessibilityServiceEnabled(ctx)

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top bar: logo + title + quick actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            LsfgLogoMark(size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Frame generation via Vulkan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconChip(
                icon = Icons.Filled.Accessibility,
                tint = if (a11yEnabled) LsfgStatusGood else LsfgStatusWarn,
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Spacer(Modifier.size(8.dp))
            IconChip(
                icon = Icons.Filled.BugReport,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { nav.navigate(Routes.LOG_VIEWER) },
            )
            Spacer(Modifier.size(8.dp))
            Box {
                IconChip(
                    icon = Icons.Filled.MoreVert,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { showMoreMenu = true },
                )
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    if (!PermissionsHelper.isIgnoringBatteryOptimizations(ctx)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.battery_exempt_menu_item)) },
                            leadingIcon = { Icon(Icons.Filled.BatteryFull, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                ctx.startActivity(
                                    PermissionsHelper.buildIgnoreBatteryOptimizationsIntent(ctx)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Re-read legal notice") },
                        leadingIcon = { Icon(Icons.Filled.Gavel, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            nav.navigate(Routes.LEGAL)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.crash_export_log)) },
                        leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            nav.navigate(Routes.LOG_VIEWER)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.language_menu_item)) },
                        leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            showLanguageDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.credits_title)) },
                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            nav.navigate(Routes.CREDITS)
                        },
                    )
                }
            }
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(onDismiss = { showLanguageDialog = false })
        }

        // Settings now has just two entries: FrameGen (embedded inline right here —
        // no separate destination — carries the DLL/shader status too) and Overlay.
        // Setup and the manual session start/stop live on the launcher's first
        // page instead.
        val dllStatus = if (state.shadersReady) StatusTone.Good
        else if (state.dllDisplayName != null) StatusTone.Warn
        else StatusTone.Neutral
        val dllLabel = if (state.shadersReady) "Ready"
        else if (state.dllDisplayName != null) "Pending"
        else "Required"

        val frameGenSummary = if (!state.shadersReady) {
            if (state.dllDisplayName != null) {
                "${state.dllDisplayName} · shaders pending"
            } else {
                stringResource(R.string.dll_status_none)
            }
        } else if (state.lsfgEnabled) {
            buildString {
                append("Multiplier ${state.multiplier}× · flow ${"%.2f".format(state.flowScale)}")
                if (state.performanceMode) append(" · perf")
                if (state.hdrMode) append(" · HDR")
            }
        } else {
            "Off — raw capture passthrough"
        }
        if (!state.legalAccepted) {
            // Legal terms haven't been accepted yet — keep this a plain nav-away
            // card rather than expanding inline content the user can't use yet.
            StepCard(
                number = 1,
                title = stringResource(R.string.nav_framegen_pacing),
                subtitle = frameGenSummary,
                status = dllStatus,
                statusLabel = dllLabel,
                emphasized = true,
                onClick = { nav.navigate(Routes.LEGAL) },
            )
        } else {
            CollapsibleSection(
                title = stringResource(R.string.nav_framegen_pacing),
                subtitle = "$frameGenSummary · $dllLabel",
                startExpanded = !state.shadersReady,
            ) {
                FrameGenPacingSection(nav)
            }
        }

        val overlayDisplaySummary = buildString {
            append(
                when (state.captureSource) {
                    CaptureSource.SHIZUKU -> "Shizuku capture"
                    CaptureSource.ROOT -> "Root capture"
                    else -> "MediaProjection"
                },
            )
            append(" · ")
            append(
                when (state.drawerEdge) {
                    com.firstt175.deepdrop.prefs.DrawerEdge.LEFT -> "left handle"
                    com.firstt175.deepdrop.prefs.DrawerEdge.RIGHT -> "right handle"
                    com.firstt175.deepdrop.prefs.DrawerEdge.TOP -> "top handle"
                    com.firstt175.deepdrop.prefs.DrawerEdge.BOTTOM -> "bottom handle"
                },
            )
            if (state.fpsCounterEnabled) append(" · FPS")
        }
        StepCard(
            number = 2,
            title = stringResource(R.string.nav_overlay_display),
            subtitle = overlayDisplaySummary,
            status = StatusTone.Neutral,
            statusLabel = when (state.captureSource) {
                CaptureSource.SHIZUKU -> "Shizuku"
                CaptureSource.ROOT -> "Root"
                else -> "MP"
            },
            onClick = { nav.navigate(Routes.OVERLAY_DISPLAY) },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "v${versionName(ctx)}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    // Shows itself only while a controller is connected (see
    // GamepadInputManager) — tells the user B backs out and A/D-pad drive
    // the step list, since those two are wired app-wide in MainActivity.
    GamepadHintOverlay(
        hints = listOf(
            GamepadHint("A", stringResource(R.string.gamepad_hint_select)),
            GamepadHint("B", stringResource(R.string.gamepad_hint_back)),
        ),
    )
    }
}

/** System / English / Thai picker. Saves to [AppLanguagePrefs] and recreates the
 *  Activity so [MainActivity.attachBaseContext] re-wraps with the new locale —
 *  Compose can't hot-swap resource-derived strings otherwise. */
@Composable
private fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    var selected by remember { mutableStateOf(AppLanguagePrefs.get(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_menu_item)) },
        text = {
            Column {
                val options = listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.language_system),
                    AppLanguage.ENGLISH to stringResource(R.string.language_english),
                    AppLanguage.THAI to stringResource(R.string.language_thai),
                )
                options.forEach { (lang, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = lang },
                    ) {
                        RadioButton(selected = selected == lang, onClick = { selected = lang })
                        Spacer(Modifier.size(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppLanguagePrefs.set(ctx, selected)
                onDismiss()
                activity?.recreate()
            }) { Text(stringResource(R.string.language_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_dialog_dismiss)) }
        },
    )
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun versionName(ctx: Context): String {
    return runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")
}

