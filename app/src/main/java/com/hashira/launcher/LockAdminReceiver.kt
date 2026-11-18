package com.hashira.launcher

import android.app.admin.DeviceAdminReceiver

/** Minimal device admin receiver: it only allows locking the screen (lockNow). */
class LockAdminReceiver : DeviceAdminReceiver()
