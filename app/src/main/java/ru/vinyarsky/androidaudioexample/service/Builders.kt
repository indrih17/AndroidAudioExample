package ru.vinyarsky.androidaudioexample.service

import android.app.Notification
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import ru.vinyarsky.androidaudioexample.R

/**
 * TODO
 */
@RequiresApi(Build.VERSION_CODES.O)
internal fun audioFocusRequest(audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener): AudioFocusRequest {
    val audioAttributes = AudioAttributes.Builder()
        // Собираемся воспроизводить звуковой контент (а не звук уведомления или звонок будильника)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        // ... и именно музыку (а не трек фильма или речь)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setOnAudioFocusChangeListener(audioFocusChangeListener)
        // Если получить фокус не удалось, ничего не делаем.
        // Если true - нам выдадут фокус как только это будет возможно (например, закончится телефонный разговор).
        .setAcceptsDelayedFocusGain(false)
        // Вместо уменьшения громкости собираемся ставить на паузу.
        .setWillPauseWhenDucked(true)
        .setAudioAttributes(audioAttributes)
        .build()
}

/**
 * Создание уведомления, используя информацию из данного медиа-сеанса.
 * Активно использует [MediaMetadataCompat.getDescription] для извлечения соответствующей информации.
 * @param context Контекст, используемый для создания уведомления.
 * @param playbackState Статус воспроизведения.
 * @param notificationChannelId Айди для нотификационного канала для Андроида 8.
 * @return уведомление с информацией из данного медиа-сеанса.
 */
internal fun MediaSessionCompat.createNotification(
    context: Context,
    playbackState: Int,
    notificationChannelId: String,
    usePreviousAndNext: Boolean
): Notification {
    val description = controller.metadata.description
    val channelId = context.getString(R.string.default_notification_channel_id)
    val builder = NotificationCompat.Builder(context, channelId)
        .setContentTitle(description.title)
        .setContentText(description.subtitle)
        .setSubText(description.description)
        .setLargeIcon(description.iconBitmap)
        .setContentIntent(controller.sessionActivity)
        .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    // Добавляем кнопки

    if (usePreviousAndNext) {
        // ...на предыдущий трек
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_previous,
                context.getString(R.string.previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            )
        )
    }

    // ...play/pause
    builder.addAction(
        if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                context.getString(R.string.play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
        }
    )

    if (usePreviousAndNext) {
        // ...на следующий трек
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_next,
                context.getString(R.string.next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            )
        )
    }

    builder.setStyle(
        androidx.media.app.NotificationCompat.MediaStyle()
            // В компактном варианте показывать Action с данным порядковым номером. В нашем случае это play/pause.
            .setShowActionsInCompactView(0)
            // Указываем, что делать при смахивании
            .setCancelButtonIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP)
            )
            // Передаем токен. Это важно для Android Wear. Если токен не передать,
            // кнопка на Android Wear будет отображаться, но не будет ничего делать
            .setMediaSession(sessionToken)
    )
    builder.setSmallIcon(R.mipmap.ic_launcher)
    builder.color = ContextCompat.getColor(context, R.color.colorPrimaryDark)

    // Не отображать время создания уведомления. В нашем случае это не имеет смысла
    builder.setShowWhen(false)

    // Это важно. Без этой строчки уведомления не отображаются на Android Wear и криво отображаются на самом телефоне.
    builder.priority = NotificationCompat.PRIORITY_HIGH

    // Не надо каждый раз вываливать уведомление на пользователя
    builder.setOnlyAlertOnce(true)

    // Для Android 8
    builder.setChannelId(notificationChannelId)

    return builder.build()
}

/** @return метадату для трека. */
internal fun Track.createMetadata(context: Context): MediaMetadataCompat =
    MediaMetadataCompat.Builder()
        .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, BitmapFactory.decodeResource(context.resources, bitmapResId))
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, artist)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        .build()
