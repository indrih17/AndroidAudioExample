package ru.vinyarsky.androidaudioexample.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.support.v4.media.session.MediaControllerCompat
import androidx.fragment.app.FragmentActivity
import ru.vinyarsky.androidaudioexample.bindService
import ru.vinyarsky.androidaudioexample.pendingIntent
import ru.vinyarsky.androidaudioexample.startForegroundService
import ru.vinyarsky.androidaudioexample.stopService

class Player(private val callback: MediaControllerCompat.Callback) {
    val transportControls: MediaControllerCompat.TransportControls?
        get() = mediaController?.transportControls

    private var mediaController: MediaControllerCompat? = null
    private var playerServiceBinder: PlayerServiceBinder? = null
    private var serviceConnection: ServiceConnection? = null

    fun start(activity: FragmentActivity, repository: MusicRepository, playOnStart: Boolean = false) {
        activity.startForegroundService<PlayerService> {
            // Укажем activity, которую запустит система, если пользователь заинтересуется подробностями данной сессии
            PlayerService.putParams(
                intent = it,
                musicRepository = repository,
                pendingIntent = activity.pendingIntent(),
                playOnStart = playOnStart
            )
        }
    }

    fun bind(activity: FragmentActivity) {
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val playerServiceBinder = service as PlayerServiceBinder
                this@Player.playerServiceBinder = playerServiceBinder
                mediaController = try {
                    MediaControllerCompat(activity, playerServiceBinder.mediaSessionToken).also {
                        it.registerCallback(callback)
                        callback.onPlaybackStateChanged(it.playbackState)
                    }
                } catch (e: RemoteException) {
                    null
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                playerServiceBinder = null
                mediaController?.unregisterCallback(callback)
                mediaController = null
            }
        }
        this.serviceConnection = serviceConnection
        activity.bindService<PlayerService>(serviceConnection)
    }

    fun unbind(activity: FragmentActivity) {
        mediaController?.let {
            it.unregisterCallback(callback)
            this.mediaController = null
        }
        playerServiceBinder = null
        serviceConnection?.let(activity::unbindService)
    }

    fun stop(activity: FragmentActivity) {
        activity.stopService<PlayerService>()
    }
}

fun Player.startAndBind(activity: FragmentActivity, repository: MusicRepository, playOnStart: Boolean = false) {
    start(activity, repository, playOnStart)
    bind(activity)
}

fun Player.unbindAndStop(activity: FragmentActivity) {
    unbind(activity)
    stop(activity)
}
