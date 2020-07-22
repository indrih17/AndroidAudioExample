package ru.vinyarsky.androidaudioexample.service

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import android.support.v4.media.session.MediaControllerCompat
import kotlinx.android.parcel.Parcelize
import ru.vinyarsky.androidaudioexample.bindService
import ru.vinyarsky.androidaudioexample.pendingIntent
import ru.vinyarsky.androidaudioexample.startForegroundService
import ru.vinyarsky.androidaudioexample.stopService

/**
 * Параметры для запуска плеера.
 * @param repository [MusicRepository].
 * @param playOnStart Если `true`, то музыка будет запущена сразу после старта сервиса.
 * @param fadeInSpeed Если не `null`, то каждый трек будет постепенно набирать громкость.
 */
@Parcelize
data class PlayerParams(
    val repository: MusicRepository,
    val playOnStart: Boolean,
    val fadeInSpeed: FadeSpeed?
) : Parcelable

/**
 * Параметры постепенного набора громкости трека.
 * @param onEach Значение, которое каждый раз будет прибавляться/убавляться.
 * @param delayMs Задержка после каждого увеличения/уменьшения.
 */
@Parcelize
data class FadeSpeed(
    val onEach: Float,
    val delayMs: Long
) : Parcelable

/** Контроллер для сервиса. Инкапсулирует логику, упрощая работу с сервисом. */
class Player(private val callback: MediaControllerCompat.Callback) {
    /** Проперти для управления плеером. */
    val transportControls: MediaControllerCompat.TransportControls?
        get() = mediaController?.transportControls

    private var mediaController: MediaControllerCompat? = null
    private var playerServiceBinder: PlayerServiceBinder? = null
    private var serviceConnection: ServiceConnection? = null

    /** Запускает сервис и передаёт ему данные. */
    fun start(activity: Activity, playerParams: PlayerParams) {
        activity.startForegroundService<PlayerService> {
            // Укажем activity, которую запустит система, если пользователь заинтересуется подробностями данной сессии
            PlayerService.putParams(intent = it, pendingIntent = activity.pendingIntent(), playerParams = playerParams)
        }
    }

    /** Биндит сервис к контексту. */
    fun bind(activity: Activity, playerParams: PlayerParams, sendMsg: (PlayerMsg) -> Unit) {
        val serviceConnection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) = disconnect()

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val playerServiceBinder = service as PlayerServiceBinder
                playerServiceBinder.sendMsg = sendMsg
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
        }
        this.serviceConnection = serviceConnection
        activity.bindService<PlayerService>(serviceConnection) {
            // Укажем activity, которую запустит система, если пользователь заинтересуется подробностями данной сессии
            PlayerService.putParams(intent = it, pendingIntent = activity.pendingIntent(), playerParams = playerParams)
        }
    }

    /** Отвязывает сервис от указанного контекста. */
    fun unbind(context: Context) {
        disconnect()
        serviceConnection?.let(context::unbindService)
    }

    private fun disconnect() {
        playerServiceBinder = null
        mediaController?.unregisterCallback(callback)
        mediaController = null
    }

    /** Полностью останавливает сервис. */
    fun stop(context: Context) {
        context.stopService<PlayerService>()
    }
}

/** Сдвоенное действие: стартануть сервис и прибиндить его к активити. */
fun Player.startAndBind(activity: Activity, playerParams: PlayerParams, sendMsg: (PlayerMsg) -> Unit) {
    start(activity, playerParams)
    bind(activity, playerParams, sendMsg)
}

/** Сдвоенное действие: отвязать сервис и остановить его. */
fun Player.unbindAndStop(context: Context) {
    unbind(context)
    stop(context)
}
