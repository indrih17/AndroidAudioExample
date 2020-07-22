package ru.vinyarsky.androidaudioexample.ui

import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import ru.vinyarsky.androidaudioexample.R
import ru.vinyarsky.androidaudioexample.service.FadeSpeed
import ru.vinyarsky.androidaudioexample.service.MultiTrackRepositoryImpl
import ru.vinyarsky.androidaudioexample.service.Player
import ru.vinyarsky.androidaudioexample.service.PlayerParams
import ru.vinyarsky.androidaudioexample.service.startAndBind

class MainActivity : AppCompatActivity() {
    private val player = Player(
        callback = object : MediaControllerCompat.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
                playButton.isEnabled = !playing
                pauseButton.isEnabled = playing
                stopButton.isEnabled = playing
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playButton.setOnClickListener { player.transportControls?.play() }
        pauseButton.setOnClickListener { player.transportControls?.pause() }
        stopButton.setOnClickListener { player.transportControls?.stop() }
        skipToNextButton.setOnClickListener { player.transportControls?.skipToNext() }
        skipToPreviousButton.setOnClickListener { player.transportControls?.skipToPrevious() }

        player.startAndBind(
            activity = this,
            playerParams = PlayerParams(
                repository = MultiTrackRepositoryImpl(),
                playOnStart = true,
                fadeInSpeed = FadeSpeed(onEach = 0.05f, delayMs = 200)
            ),
            sendMsg = ::println
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        player.unbind(this)
    }
}
