package com.hashira.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Immutable
import java.text.Collator

enum class ProfileKind { MAIN, WORK, PRIVATE }

@Immutable
data class AppEntry(
    val key: String,
    val component: ComponentName,
    val label: String,
    val user: UserHandle,
    val profile: ProfileKind,
    val originalLabel: String = label
) {
    val packageName: String get() = component.packageName
}

/** Secondary profile (work or Private Space) and whether it is locked (quiet mode). */
@Immutable
data class ProfileInfo(val user: UserHandle, val kind: ProfileKind, val locked: Boolean)

data class LoadResult(val apps: List<AppEntry>, val profiles: List<ProfileInfo>)

/**
 * Access to launchable apps via the public LauncherApps API, including secondary profiles.
 * Private Space (Android 15+) is only visible if hashira is the default launcher and holds
 * ACCESS_HIDDEN_PROFILES; while locked there are no apps. Shortcuts also require being the launcher.
 */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val me = Process.myUserHandle()

    @Volatile private var infos: Map<String, LauncherActivityInfo> = emptyMap()
    @Volatile private var stamps: Map<String, Long> = emptyMap()

    fun load(): LoadResult {
        val collator = Collator.getInstance()
        val entries = ArrayList<AppEntry>()
        val profiles = ArrayList<ProfileInfo>()
        val infoMap = HashMap<String, LauncherActivityInfo>()

        for (user in userManager.userProfiles) {
            val kind = if (user == me) ProfileKind.MAIN else classify(user)
            val locked = kind != ProfileKind.MAIN &&
                runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false)
            if (kind != ProfileKind.MAIN) profiles += ProfileInfo(user, kind, locked)
            if (locked) continue

            val list = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            val serial = userManager.getSerialNumberForUser(user)
            for (info in list) {
                val cn = info.componentName
                if (user == me && cn.packageName == context.packageName) continue
                // Main profile keys keep the old format (favorites already saved).
                val key = if (user == me) cn.flattenToString() else "${cn.flattenToString()}@$serial"
                infoMap[key] = info
                entries += AppEntry(key, cn, info.label.toString(), user, kind)
            }
        }
        infos = infoMap // atomic replacement: there is never a half-built map
        // A single query to know when each package was updated (invalidates on-disk icons).
        stamps = runCatching {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                .associate { it.packageName to it.lastUpdateTime }
        }.getOrDefault(stamps)
        return LoadResult(entries.sortedWith { a, b -> collator.compare(a.label, b.label) }, profiles)
    }

    private fun classify(user: UserHandle): ProfileKind {
        if (Build.VERSION.SDK_INT >= 35) {
            val type = runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()
            if (type == UserManager.USER_TYPE_PROFILE_PRIVATE) return ProfileKind.PRIVATE
        }
        return ProfileKind.WORK
    }

    /** Last update timestamp of a package (0 if unknown). */
    fun stamp(packageName: String): Long = stamps[packageName] ?: 0L

    /** System icon (adaptive; badged for secondary profiles) or null. */
    fun systemIcon(entry: AppEntry): Drawable? = runCatching {
        val info = infos[entry.key] ?: return null
        val density = context.resources.displayMetrics.densityDpi
        if (entry.profile == ProfileKind.MAIN) info.getIcon(density) else info.getBadgedIcon(density)
    }.getOrNull()

    fun launch(entry: AppEntry): Boolean =
        runCatching { launcherApps.startMainActivity(entry.component, entry.user, null, null) }.isSuccess

    fun openAppInfo(entry: AppEntry): Boolean =
        runCatching { launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null) }.isSuccess

    /** Locks (quiet = true) or unlocks a profile; the system asks for authentication. */
    fun setQuiet(user: UserHandle, quiet: Boolean): Boolean =
        runCatching { userManager.requestQuietModeEnabled(quiet, user) }.getOrDefault(false)

    // ---- shortcuts ----
    private val shortcutFlags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

    private fun hasShortcutAccess() = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    /** App shortcuts, sorted. Only work if we are the default launcher. */
    fun shortcuts(entry: AppEntry): List<ShortcutInfo> {
        if (!hasShortcutAccess()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(shortcutFlags)
        return runCatching { launcherApps.getShortcuts(query, entry.user) }.getOrNull().orEmpty()
            .filter { it.isEnabled }
            .sortedBy { it.rank }
            .take(5)
    }

    fun shortcutById(entry: AppEntry, id: String): ShortcutInfo? {
        if (!hasShortcutAccess()) return null
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setShortcutIds(listOf(id))
            .setQueryFlags(shortcutFlags)
        return runCatching { launcherApps.getShortcuts(query, entry.user) }.getOrNull()
            ?.firstOrNull { it.isEnabled }
    }

    fun startShortcut(info: ShortcutInfo): Boolean =
        runCatching { launcherApps.startShortcut(info, null, null) }.isSuccess

    fun register(callback: LauncherApps.Callback) =
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))

    fun unregister(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)
}
