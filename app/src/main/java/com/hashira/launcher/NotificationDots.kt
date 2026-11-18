package com.hashira.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/** Packages with active notifications. Package names only: the content is never read. */
object NotificationDots {
    val packages = MutableStateFlow<Set<String>>(emptySet())
}

class HashiraNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = publish()

    override fun onListenerDisconnected() {
        NotificationDots.packages.value = emptySet()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    private fun publish() {
        // Ongoing notifications (media player, VPN…) and group summaries are ignored.
        NotificationDots.packages.value = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { !it.isOngoing && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .map { it.packageName }
            .toSet()
    }
}
