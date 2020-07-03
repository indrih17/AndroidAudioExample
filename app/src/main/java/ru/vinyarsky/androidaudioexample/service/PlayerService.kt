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
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import okhttp3.OkHttpClient
import ru.vinyarsky.androidaudioexample.R
import ru.vinyarsky.androidaudioexample.ui.MainActivity
import java.io.File

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
        ExoPlayerFactory
            .newSimpleInstance(
                this,
                DefaultRenderersFactory(this),
                DefaultTrackSelector(),
                DefaultLoadControl()
            )
            .apply { addListener(playerListener) }
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

    /** Репозиторий с музыкой. */
    private val musicRepository = MusicRepository()

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @SuppressLint("WrongConstant")
            val notificationChannel = NotificationChannel(
                NOTIFICATION_DEFAULT_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        /*
            MediaButtonReceiver при получении события ищет в приложении сервис,
            который также принимает "android.intent.action.MEDIA_BUTTON" и перенаправляет его туда.
            Если подходящий сервис не найден или их несколько, будет выброшен IllegalStateException.
        */
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ресурсы освобождать обязательно
        mediaSession.release()
        exoPlayer.release()
    }

    private val mediaSession: MediaSessionCompat by lazy(LazyThreadSafetyMode.NONE) {
        val debugTag = "PlayerService"
        MediaSessionCompat(this, debugTag).apply {
            // Отдаем наши коллбэки
            setCallback(mediaSessionCallback)

            // Укажем activity, которую запустит система, если пользователь заинтересуется подробностями данной сессии
            val activityIntent = Intent(applicationContext, MainActivity::class.java)
            setSessionActivity(PendingIntent.getActivity(applicationContext, 0, activityIntent, 0))
        }
    }

    private val mediaSessionCallback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        private var currentState = PlaybackStateCompat.STATE_STOPPED

        private var audioFocusRequested = false

        override fun onPlay() {
            if (!exoPlayer.playWhenReady) {
                val track = musicRepository.current
                mediaSession.setMetadata(track.createMetadata(context = this@PlayerService))
                prepareToPlay(track.uri)
                if (!audioFocusRequested) {
                    audioFocusRequested = true
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
            }
            mediaSession.setPlaybackState(
                playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                ).build()
            )
            currentState = PlaybackStateCompat.STATE_PLAYING
            refreshNotificationAndForegroundStatus(currentState)
        }

        override fun onPause() {
            // Останавливаем воспроизведение
            if (exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
            }

            // Сообщаем новое состояние
            mediaSession.setPlaybackState(
                playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                ).build()
            )
            currentState = PlaybackStateCompat.STATE_PAUSED
            refreshNotificationAndForegroundStatus(currentState)
        }

        override fun onStop() {
            // Останавливаем воспроизведение
            if (exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
            }
            if (audioFocusRequested) {
                audioFocusRequested = false
                abandonAudioFocus()
            }
            // Все, больше мы не "главный" плеер, уходим со сцены
            mediaSession.isActive = false
            // Сообщаем новое состояние
            mediaSession.setPlaybackState(
                playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_STOPPED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                ).build()
            )
            currentState = PlaybackStateCompat.STATE_STOPPED
            refreshNotificationAndForegroundStatus(currentState)
            stopSelf()
        }

        override fun onSkipToNext() =
            changeTrack(musicRepository.next)

        override fun onSkipToPrevious() =
            changeTrack(musicRepository.previous)

        private var currentUri: Uri? = null
        private fun prepareToPlay(uri: Uri) {
            if (uri != currentUri) {
                currentUri = uri
                exoPlayer.prepare(
                    ExtractorMediaSource.Factory(dataSourceFactory)
                        .setExtractorsFactory(extractorsFactory)
                        .createMediaSource(uri)
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

        private fun changeTrack(track: MusicRepository.Track) {
            mediaSession.setMetadata(track.createMetadata(context = this@PlayerService))
            refreshNotificationAndForegroundStatus(currentState)
            prepareToPlay(track.uri)
        }
    }

    private val extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
    private val dataSourceFactory: DataSource.Factory by lazy(LazyThreadSafetyMode.NONE) {
        val cacheFile = File(this.cacheDir.absolutePath + "/exoplayer")
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(1024 * 1024 * 100) // 100 Mb max
        val userAgent = Util.getUserAgent(this, getString(R.string.app_name))
        CacheDataSourceFactory(
            SimpleCache(cacheFile, cacheEvictor),
            OkHttpDataSourceFactory(OkHttpClient(), userAgent),
            CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
        )
    }

    private val audioFocusChangeListener = OnAudioFocusChangeListener { focusChange ->
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

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
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
        override fun onPlayerError(error: ExoPlaybackException) = Unit
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = Unit

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playWhenReady && playbackState == Player.STATE_ENDED) {
                mediaSessionCallback.onSkipToNext()
            }
        }
    }

    /** Для доступа извне к MediaSession требуется токен. Для этого научим сервис его отдавать. */
    override fun onBind(intent: Intent): IBinder? =
        PlayerServiceBinder(mediaSession)

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

    private fun createNotification(playbackState: Int): Notification =
        mediaSession.createNotification(
            context = this,
            playbackState = playbackState,
            notificationChannelId = NOTIFICATION_DEFAULT_CHANNEL_ID
        )

    private companion object {
        private const val NOTIFICATION_ID = 404
        private const val NOTIFICATION_DEFAULT_CHANNEL_ID = "default_channel"
    }
}

/** Биндер, с помощью которого можно прокидывать [MediaSessionCompat.getSessionToken]. */
class PlayerServiceBinder(private val mediaSession: MediaSessionCompat) : Binder() {
    val mediaSessionToken: MediaSessionCompat.Token
        get() = mediaSession.sessionToken
}
