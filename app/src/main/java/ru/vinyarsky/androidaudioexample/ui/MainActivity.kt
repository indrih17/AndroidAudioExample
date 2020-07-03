package ru.vinyarsky.androidaudioexample.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import ru.vinyarsky.androidaudioexample.R
import ru.vinyarsky.androidaudioexample.service.PlayerService
import ru.vinyarsky.androidaudioexample.service.PlayerServiceBinder

//https://habr.com/ru/post/339416/
//https://stackoverflow.com/questions/52473974/binding-playerview-with-simpleexoplayer-from-a-service

class MainActivity : AppCompatActivity() {
    private var playerServiceBinder: PlayerServiceBinder? = null
    private var mediaController: MediaControllerCompat? = null
    private var callback: MediaControllerCompat.Callback? = null
    private var serviceConnection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val callback = object : MediaControllerCompat.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
                playButton.isEnabled = !playing
                pauseButton.isEnabled = playing
                stopButton.isEnabled = playing
            }
        }
        this.callback = callback

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val playerServiceBinder = service as PlayerServiceBinder
                this@MainActivity.playerServiceBinder = playerServiceBinder
                mediaController = try {
                    MediaControllerCompat(this@MainActivity, playerServiceBinder.mediaSessionToken).also {
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

        bindService(Intent(this, PlayerService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        playButton.setOnClickListener { mediaController?.transportControls?.play() }
        pauseButton.setOnClickListener { mediaController?.transportControls?.pause() }
        stopButton.setOnClickListener { mediaController?.transportControls?.stop() }
        skipToNextButton.setOnClickListener { mediaController?.transportControls?.skipToNext() }
        skipToPreviousButton.setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaController?.let {
            callback?.let(it::unregisterCallback)
            this.mediaController = null
        }
        playerServiceBinder = null
        serviceConnection?.let(::unbindService)
    }
}