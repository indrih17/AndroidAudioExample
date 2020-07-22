package ru.vinyarsky.androidaudioexample.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.database.ExoDatabaseProvider
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import okhttp3.OkHttpClient
import ru.vinyarsky.androidaudioexample.R
import ru.vinyarsky.androidaudioexample.getParcelable
import java.io.File

/**
 * [Runnable] для постепенного увеличения громкости.
 * @param exoPlayer [SimpleExoPlayer].
 * @param handler То, где мы будем запускать этот же [Runnable] после задержки.
 * @param fadeInSpeed [FadeSpeed].
 */
class VolumeRunnable(
    private val exoPlayer: SimpleExoPlayer,
    private val handler: Handler,
    private val fadeInSpeed: FadeSpeed
) : Runnable {
    override fun run() {
        val currentVolume = exoPlayer.volume
        if (currentVolume < 1f) {
            exoPlayer.volume += fadeInSpeed.onEach
            handler.postDelayed(this, fadeInSpeed.delayMs)
        }
    }
}

/**
 * Комментарии копировались из статьи.
 * @see <a href=\"https://habr.com/ru/post/339416/\">Статья</a>.
 */
class PlayerService : Service() {
    /**
     * Состояния плеера.
     * Здесь мы указываем действия, которые собираемся обрабатывать в коллбэках.
     * Например, если мы не укажем ACTION_PAUSE, то нажатие на паузу не вызовет onPause.
     * ACTION_PLAY_PAUSE обязателен, иначе не будет работать управление с Android Wear!
     */
    private val playbackStateBuilder = PlaybackStateCompat.Builder().setActions(
        PlaybackStateCompat.ACTION_PLAY
            or PlaybackStateCompat.ACTION_STOP
            or PlaybackStateCompat.ACTION_PAUSE
            or PlaybackStateCompat.ACTION_PLAY_PAUSE
            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    )

    private val exoPlayer: SimpleExoPlayer by lazy(LazyThreadSafetyMode.NONE) {
        SimpleExoPlayer.Builder(this).build().apply { addListener(playerListener) }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** @see PlayerParams.fadeInSpeed */
    private val fadeInVolumeRunnable: Runnable? by lazy(LazyThreadSafetyMode.NONE) {
        playerParams.fadeInSpeed?.let { VolumeRunnable(exoPlayer, handler, it) }
    }

    /**
     * Всегда существует вероятность, что несколько приложений захотят одновременно воспроизвести звук.
     * Или поступил входящий звонок и надо срочно остановить музыку.
     * Для решения этих проблем в системный сервис AudioManager включили возможность запроса аудиофокуса.
     * Аудиофокус является правом воспроизводить звук и выдается только одному приложению в каждый момент времени.
     * Если приложению отказали в предоставлении аудиофокуса или забрали его позже, воспроизведение звука необходимо остановить.
     * Как правило фокус всегда предоставляется, то есть когда у приложения нажимают play, все остальные приложения замолкают.
     * Исключение бывает только при активном телефонном разговоре.
     * Технически нас никто не заставляет получать фокус, но мы же не хотим раздражать пользователя?
     * Ну и плюс окно блокировки игнорирует приложения без аудиофокуса.
     * Фокус необходимо запрашивать в onPlay() и освобождать в onStop().
     */
    private val audioManager: AudioManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @SuppressLint("WrongConstant")
            val notificationChannel = NotificationChannel(
                NOTIFICATION_DEFAULT_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let(::initService)
        /*
            MediaButtonReceiver при получении события ищет в приложении сервис,
            который также принимает "android.intent.action.MEDIA_BUTTON" и перенаправляет его туда.
            Если подходящий сервис не найден или их несколько, будет выброшен IllegalStateException.
        */
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun initService(intent: Intent) {
        if (intent.hasExtra(PLAYER_PARAMS)) {
            val playerParams = intent.getParcelable<PlayerParams>(PLAYER_PARAMS).also { playerParams = it }
            mediaSession.setSessionActivity(intent.getParcelable(PENDING_INTENT))
            setDefaultState(playerParams)
        }
    }

    private lateinit var playerParams: PlayerParams
    private val musicRepository: MusicRepository get() = playerParams.repository

    private fun setDefaultState(playerParams: PlayerParams) {
        mediaSession.setMetadata(playerParams.repository.current.createMetadata(context = this@PlayerService))
        // Указываем, что наше приложение теперь активный плеер и кнопки
        // на окне блокировки должны управлять именно нами
        // Сразу после получения фокуса
        mediaSession.isActive = true

        if (playerParams.playOnStart) {
            mediaSessionCallback.onPlay()
        } else {
            // Обязательно стартуем, иначе будет ANR
            startForeground(NOTIFICATION_ID, createNotification(PlaybackStateCompat.STATE_PAUSED))
            // А потом убираем из форграунда, давая возможность включить из пуш уведомления.
            mediaSessionCallback.onPause()
        }
    }

    private var binder: PlayerServiceBinder? = null

    /** Для доступа извне к MediaSession требуется токен. Для этого научим сервис его отдавать. */
    override fun onBind(intent: Intent): IBinder =
        PlayerServiceBinder(mediaSession).also { binder = it }

    override fun onUnbind(intent: Intent?): Boolean {
        binder = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ресурсы освобождать обязательно
        mediaSession.release()
        exoPlayer.release()
        cache.release()
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (e: IllegalArgumentException) {
            // На случай, если сервис не был прибинден, а следственно и не регистрировался ресейвер.
        }
    }

    private val mediaSession: MediaSessionCompat by lazy(LazyThreadSafetyMode.NONE) {
        val debugTag = "PlayerService"
        MediaSessionCompat(this, debugTag).apply { setCallback(mediaSessionCallback) }
    }

    private var currentTrackUri: Uri? = null

    private val mediaSessionCallback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        private var currentState = PlaybackStateCompat.STATE_STOPPED
            set(value) {
                field = value
                updatePlaybackState(value)
                refreshNotificationAndForegroundStatus(value)
            }

        private var isAudioFocusRequested = false

        override fun onPlay() {
            if (!exoPlayer.playWhenReady) {
                val track = musicRepository.current
                mediaSession.setMetadata(track.createMetadata(context = this@PlayerService))
                prepareToPlay(track.uri)
                if (!isAudioFocusRequested) {
                    isAudioFocusRequested = true
                    if (abandonAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
                }

                // Указываем, что наше приложение теперь активный плеер и кнопки
                // на окне блокировки должны управлять именно нами
                // Сразу после получения фокуса
                mediaSession.isActive = true

                /*
                    Допустим пользователь слушает музыку в наушниках и выдергивает их.
                    Если эту ситуацию специально не обработать, звук переключится на динамик телефона и его услышат все окружающие.
                    Было бы хорошо в этом случае встать на паузу.
                    Для этого в Android есть специальный бродкаст AudioManager.ACTION_AUDIO_BECOMING_NOISY.
                 */
                registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

                // Запускаем воспроизведение
                exoPlayer.playWhenReady = true
                exoPlayer.repeatMode = if (track.repeatable) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                fadeInVolumeRunnable?.let {
                    exoPlayer.volume = 0f
                    handler.post(it)
                }
            }
            currentState = PlaybackStateCompat.STATE_PLAYING
        }

        override fun onPause() {
            // Останавливаем воспроизведение
            if (exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
            }

            // Сообщаем новое состояние
            currentState = PlaybackStateCompat.STATE_PAUSED
        }

        override fun onStop() {
            // Останавливаем воспроизведение
            if (exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
            }
            if (isAudioFocusRequested) {
                isAudioFocusRequested = false
                abandonAudioFocus()
            }
            // Все, больше мы не "главный" плеер, уходим со сцены
            mediaSession.isActive = false
            // Сообщаем новое состояние
            currentState = PlaybackStateCompat.STATE_STOPPED
            stopSelf()
        }

        override fun onSkipToNext() {
            (musicRepository as? MultiTrackRepository)?.let { changeTrack(it.next()) }
        }

        override fun onSkipToPrevious() {
            (musicRepository as? MultiTrackRepository)?.let { changeTrack(it.previous()) }
        }

        private fun prepareToPlay(uri: Uri) {
            if (currentTrackUri != uri) {
                currentTrackUri = uri
                exoPlayer.prepare(
                    ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(uri)
                )
            }
        }

        @Suppress("DEPRECATION")
        private fun abandonAudioFocus() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest(audioFocusChangeListener))
            } else {
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }

        private fun changeTrack(track: Track) {
            mediaSession.setMetadata(track.createMetadata(context = this@PlayerService))
            refreshNotificationAndForegroundStatus(currentState)
            prepareToPlay(track.uri)
        }
    }

    private fun updatePlaybackState(playbackState: Int) {
        mediaSession.setPlaybackState(
            playbackStateBuilder.setState(
                playbackState,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f
            ).build()
        )
    }

    private fun refreshNotificationAndForegroundStatus(playbackState: Int) {
        when (playbackState) {
            PlaybackStateCompat.STATE_PLAYING ->
                startForeground(NOTIFICATION_ID, createNotification(playbackState))

            PlaybackStateCompat.STATE_PAUSED -> {
                // На паузе мы перестаем быть foreground, однако оставляем уведомление, чтобы пользователь мог нажать play.
                NotificationManagerCompat
                    .from(this@PlayerService)
                    .notify(NOTIFICATION_ID, createNotification(playbackState))
                stopForeground(false)
            }
            else ->
                // Все, можно прятать уведомление
                stopForeground(true)
        }
    }

    private val cache: SimpleCache by lazy(LazyThreadSafetyMode.NONE) {
        val cacheFile = File(this.cacheDir.absolutePath + "/exoplayer")
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(1024 * 1024 * 100) // 100 Mb max
        SimpleCache(cacheFile, cacheEvictor, ExoDatabaseProvider(this))
    }
    private val extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
    private val dataSourceFactory: DataSource.Factory by lazy(LazyThreadSafetyMode.NONE) {
        CacheDataSourceFactory(
            cache,
            OkHttpDataSourceFactory(
                OkHttpClient(),
                Util.getUserAgent(this, getString(R.string.app_name))
            ),
            CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
        )
    }

    private val audioFocusChangeListener: OnAudioFocusChangeListener = OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN ->
                // Фокус предоставлен.
                // Например, был входящий звонок и фокус у нас отняли.
                // Звонок закончился, фокус выдали опять
                // и мы продолжили воспроизведение.
                mediaSessionCallback.onPlay()

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                // Фокус отняли, потому что какому-то приложению надо
                // коротко "крякнуть".
                // Например, проиграть звук уведомления или навигатору сказать
                // "Через 50 метров поворот направо".
                // В этой ситуации нам разрешено не останавливать вопроизведение,
                // но надо снизить громкость.
                // Приложение не обязано именно снижать громкость,
                // можно встать на паузу, что мы здесь и делаем.
                mediaSessionCallback.onPause()

            else ->
                // Фокус совсем отняли.
                mediaSessionCallback.onPause()
        }
    }

    private val becomingNoisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Disconnecting headphones - stop playback
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                mediaSessionCallback.onPause()
            }
        }
    }

    private val playerListener = object : Player.EventListener {
        override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) = Unit
        override fun onLoadingChanged(isLoading: Boolean) = Unit
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = Unit
        override fun onPlayerError(error: ExoPlaybackException) {
            if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                binder?.sendMsg?.invoke(PlayerMsg.ConnectionError)

                // Это необходимо, т.к. при отстутсвии интернета, после подключения к сети трек ну будет проигрываться.
                currentTrackUri = null
            } else {
                error.printStackTrace()
            }
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playWhenReady && playbackState == Player.STATE_ENDED) {
                mediaSessionCallback.onSkipToNext()
            }
        }
    }

    private fun createNotification(playbackState: Int): Notification =
        mediaSession.createNotification(
            context = this,
            playbackState = playbackState,
            notificationChannelId = NOTIFICATION_DEFAULT_CHANNEL_ID,
            usePreviousAndNext = musicRepository is MultiTrackRepository
        )

    companion object {
        private const val PENDING_INTENT = "pending_intent"
        private const val PLAYER_PARAMS = "player_params"

        fun putParams(intent: Intent, pendingIntent: PendingIntent, playerParams: PlayerParams) {
            intent.putExtra(PENDING_INTENT, pendingIntent)
            intent.putExtra(PLAYER_PARAMS, playerParams)
        }

        private const val NOTIFICATION_ID = 404
        private const val NOTIFICATION_DEFAULT_CHANNEL_ID = "default_channel"
    }
}

sealed class PlayerMsg {
    object ConnectionError : PlayerMsg()
}

/** Биндер, с помощью которого можно прокидывать [MediaSessionCompat.getSessionToken]. */
class PlayerServiceBinder(private val mediaSession: MediaSessionCompat) : Binder() {
    val mediaSessionToken: MediaSessionCompat.Token
        get() = mediaSession.sessionToken

    var sendMsg: (PlayerMsg) -> Unit = {}
}
