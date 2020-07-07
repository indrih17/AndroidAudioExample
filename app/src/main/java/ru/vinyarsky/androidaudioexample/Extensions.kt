package ru.vinyarsky.androidaudioexample

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Parcelable
import androidx.core.content.ContextCompat

inline fun <reified T : Parcelable?> Intent.getParcelable(key: String): T =
    getParcelableExtra<T>(key) as T

inline fun <reified S : Service> Context.startForegroundService(passParams: (Intent) -> Unit = {}) =
    ContextCompat.startForegroundService(this, Intent(this, S::class.java).also(passParams))

inline fun <reified S : Service> ContextWrapper.bindService(
    serviceConnection: ServiceConnection,
    flag: Int = Context.BIND_AUTO_CREATE,
    passParams: (Intent) -> Unit = {}
): Boolean =
    bindService(Intent(this, S::class.java).also(passParams), serviceConnection, flag)

inline fun <reified S : Service> ContextWrapper.stopService() =
    stopService(Intent(this, S::class.java))

fun Activity.pendingIntent(requestCode: Int = 0, frags: Int = 0): PendingIntent {
    val activityIntent = Intent(applicationContext, this::class.java)
    return PendingIntent.getActivity(applicationContext, requestCode, activityIntent, frags)
}
