@file:OptIn(ExperimentalMaterial3Api::class)

package com.loopguard.tiktok

import android.app.AppOpsManager
import android.app.TimePickerDialog
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------- System helpers ----------------

fun isAccessibilityEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(ctx, LoopGuardService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

@Suppress("DEPRECATION")
fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED
}

fun todayTikTokMinutes(ctx: Context): Long {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
    return stats.filter { it.packageName in LoopGuardService.TIKTOK_PACKAGES }
        .sumOf { it.totalTimeInForeground } / 60_000
}

private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)
private fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

private fun pickTime(ctx: Context, initMin: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        ctx, { _, h, m -> onPicked(h * 60 + m) }, initMin / 60, initMin % 60, DateFormat.is24HourFormat(ctx)
    ).show()
}

// ---------------- Reusable bits ----------------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun RadioRow(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- Controls ----------------

@Composable
fun SettingsScreen(prefs: Prefs, refresh: Int) {
    val ctx = LocalContext.current
    val serviceOn = remember(refresh) { isAccessibilityEnabled(ctx) }

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var swipeOn by remember { mutableStateOf(prefs.noSwipeEnabled) }
    var swipeMin by remember { mutableIntStateOf(prefs.noSwipeMin) }
    var loopOn by remember { mutableStateOf(prefs.loopLimitEnabled) }
    var loopLimit by remember { mutableIntStateOf(prefs.loopLimit) }
    var loopText by remember { mutableStateOf(prefs.loopLimit.toString()) }
    var timeOn by remember { mutableStateOf(prefs.timeLimitEnabled) }
    var timeMin by remember { mutableIntStateOf(prefs.timeLimitMin) }
    var action by remember { mutableStateOf(prefs.action) }
    var lockout by remember { mutableStateOf(prefs.lockout) }
    var allDay by remember { mutableStateOf(prefs.scheduleAll) }
    var startMin by remember { mutableIntStateOf(prefs.startMin) }
    var endMin by remember { mutableIntStateOf(prefs.endMin) }
    var halve by remember { mutableStateOf(prefs.halveLong) }
    var chime by remember { mutableStateOf(prefs.chime) }
    var haptic by remember { mutableStateOf(prefs.haptic) }

    fun setLimit(n: Int) {
        val v = n.coerceIn(1, 999)
        loopLimit = v; loopText = v.toString(); prefs.loopLimit = v
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- Accessibility setup guide ---
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (serviceOn) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (serviceOn) {
                    Text("LoopGuard is active ✓", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("One-time setup required", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. Tap the button below.\n" +
                            "2. Open “Installed apps” (or “Downloaded apps”).\n" +
                            "3. Choose “TikTok LoopGuard” and switch it ON.\n" +
                            "4. Confirm the permission prompt.\n\n" +
                            "If the switch is greyed out (Android 13+), open this app's Info page → ⋮ → " +
                            "“Allow restricted settings”, then try again."
                    )
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open Accessibility Settings") }
                }
            }
        }

        Section("Protection") {
            SwitchRow("LoopGuard enabled", null, enabled) { enabled = it; prefs.enabled = it }
        }

        Section("No-swipe timer") {
            SwitchRow(
                "Close TikTok if I stop swiping",
                "Counts up while you stay on the same video; any swipe resets it",
                swipeOn
            ) { swipeOn = it; prefs.noSwipeEnabled = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5, 10).forEach { m ->
                    FilterChip(
                        selected = swipeMin == m,
                        onClick = { swipeMin = m; prefs.noSwipeMin = m },
                        label = { Text("$m min") }
                    )
                }
            }
        }

        Section("Loop limit") {
            SwitchRow("Limit video loops", "Act once a single video has looped this many times", loopOn) {
                loopOn = it; prefs.loopLimitEnabled = it
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setLimit(loopLimit - 1) }) { Text("−") }
                OutlinedTextField(
                    value = loopText,
                    onValueChange = { t ->
                        val digits = t.filter(Char::isDigit).take(3)
                        loopText = digits
                        digits.toIntOrNull()?.takeIf { it >= 1 }?.let { loopLimit = it; prefs.loopLimit = it }
                    },
                    label = { Text("Loops") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
                OutlinedButton(onClick = { setLimit(loopLimit + 1) }) { Text("+") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { setLimit(1) }, Modifier.weight(1f)) { Text("1 Loop\n(Watch Once)") }
                FilledTonalButton(onClick = { setLimit(2) }, Modifier.weight(1f)) { Text("2 Loops\n(1 Replay)") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { setLimit(3) }, Modifier.weight(1f)) { Text("3 Loops\n(Strict)") }
                FilledTonalButton(onClick = { setLimit(5) }, Modifier.weight(1f)) { Text("5 Loops") }
            }
            SwitchRow(
                "Cut loops in half for long videos",
                "If the video is longer than 3 minutes, the limit is halved (minimum 1)",
                halve
            ) { halve = it; prefs.halveLong = it }
        }

        Section("Session time limit") {
            SwitchRow("Limit total time in TikTok", "Works alongside (or instead of) the loop limit", timeOn) {
                timeOn = it; prefs.timeLimitEnabled = it
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { m ->
                    FilterChip(
                        selected = timeMin == m,
                        onClick = { timeMin = m; prefs.timeLimitMin = m },
                        label = { Text("$m minutes") }
                    )
                }
            }
        }

        Section("When the limit is reached") {
            RadioRow(
                "Fully close TikTok (default)",
                "Home + Back, then ends TikTok's background process",
                action == EndAction.CLOSE
            ) { action = EndAction.CLOSE; prefs.action = EndAction.CLOSE }
            RadioRow(
                "Send to background",
                "Home only — playback pauses and your place in the video is kept",
                action == EndAction.BACKGROUND
            ) { action = EndAction.BACKGROUND; prefs.action = EndAction.BACKGROUND }
            if (action == EndAction.CLOSE) {
                SwitchRow("Show calm Detox Lockout screen", null, lockout) { lockout = it; prefs.lockout = it }
            }
        }

        Section("Active schedule") {
            RadioRow("The whole day (24/7)", "Always enforce the limit", allDay) { allDay = true; prefs.scheduleAll = true }
            RadioRow("Time of day window", "Outside it, TikTok opens normally", !allDay) { allDay = false; prefs.scheduleAll = false }
            if (!allDay) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(ctx, startMin) { startMin = it; prefs.startMin = it } }) {
                        Text("From ${fmtTime(startMin)}")
                    }
                    OutlinedButton(onClick = { pickTime(ctx, endMin) { endMin = it; prefs.endMin = it } }) {
                        Text("To ${fmtTime(endMin)}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { startMin = 21 * 60; endMin = 2 * 60; prefs.startMin = startMin; prefs.endMin = endMin },
                        label = { Text("Bedtime 21:00–02:00") }
                    )
                    AssistChip(
                        onClick = { startMin = 9 * 60; endMin = 17 * 60; prefs.startMin = startMin; prefs.endMin = endMin },
                        label = { Text("Work 09:00–17:00") }
                    )
                }
            }
        }

        Section("Feedback") {
            SwitchRow("Warning chime", "3-second chime before TikTok closes", chime) { chime = it; prefs.chime = it }
            SwitchRow("Haptic vibration", "Vibrate when the limit is hit", haptic) { haptic = it; prefs.haptic = it }
        }
    }
}

// ---------------- Dashboard ----------------

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DashboardScreen(prefs: Prefs, refresh: Int) {
    val ctx = LocalContext.current
    val history = remember(refresh) { prefs.history() }
    val today = remember(refresh) { prefs.interventionsToday() }
    val usageOk = remember(refresh) { hasUsageAccess(ctx) }
    val usageMin = remember(refresh, usageOk) { if (usageOk) todayTikTokMinutes(ctx) else 0L }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(Modifier.weight(1f), "Time reclaimed (est.)", fmtMinutes(prefs.minutesSaved))
                StatCard(Modifier.weight(1f), "Interventions today", today.toString())
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(Modifier.weight(1f), "Day streak", "${prefs.streak} 🔥")
                StatCard(Modifier.weight(1f), "Loops intercepted", prefs.loopsIntercepted.toString())
            }
        }
        item {
            StatCard(Modifier.fillMaxWidth(), "Doomscroll sessions broken", prefs.sessionsBroken.toString())
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (usageOk) {
                        Text("TikTok today: $usageMin min", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("Show today's real TikTok screen time", style = MaterialTheme.typography.titleMedium)
                        Text("Grant “Usage access” to see how long TikTok has been open today.")
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Grant usage access") }
                    }
                }
            }
        }
        item { Text("History", style = MaterialTheme.typography.titleMedium) }
        if (history.isEmpty()) {
            item { Text("No interventions yet.") }
        }
        items(history) { h ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmt.format(Date(h.time)), style = MaterialTheme.typography.labelMedium)
                    Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    Text(
                        "${h.loops} loops · ${h.reason} · " +
                            if (h.action == EndAction.CLOSE.name) "closed" else "sent to background",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
