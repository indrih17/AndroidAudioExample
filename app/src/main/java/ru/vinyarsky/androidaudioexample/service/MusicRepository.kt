package ru.vinyarsky.androidaudioexample.service

import android.net.Uri
import ru.vinyarsky.androidaudioexample.R

//https://simpleguics2pygame.readthedocs.io/en/latest/_static/links/snd_links.html

internal class MusicRepository {
    private val data = listOf(
        Track(
            "Triangle",
            "Jason Shaw",
            R.drawable.image266680,
            uriString = "https://codeskulptor-demos.commondatastorage.googleapis.com/pang/paza-moduless.mp3",
            durationMs = (3 * 60 + 41) * 1000
        ),
        Track(
            "Rubix Cube",
            "Jason Shaw",
            R.drawable.image396168,
            uriString = "https://codeskulptor-demos.commondatastorage.googleapis.com/descent/background%20music.mp3",
            durationMs = (3 * 60 + 44) * 1000
        ),
        Track(
            "MC Ballad S Early Eighties",
            "Frank Nora",
            R.drawable.image533998,
            uriString = "https://commondatastorage.googleapis.com/codeskulptor-assets/sounddogs/soundtrack.mp3",
            durationMs = (2 * 60 + 50) * 1000
        ),
        Track(
            "Folk Song",
            "Brian Boyko",
            R.drawable.image544064,
            uriString = "https://commondatastorage.googleapis.com/codeskulptor-demos/riceracer_assets/music/lose.ogg",
            durationMs = (3 * 60 + 5) * 1000
        ),
        Track(
            "Morning Snowflake",
            "Kevin MacLeod",
            R.drawable.image208815,
            uriString = "https://commondatastorage.googleapis.com/codeskulptor-demos/riceracer_assets/music/race1.ogg",
            durationMs = (2 * 60 + 0) * 1000
        )
    )

    private val maxIndex = data.size - 1
    private var currentItemIndex = 0

    val next: Track
        get() {
            if (currentItemIndex == maxIndex) currentItemIndex = 0 else currentItemIndex++
            return current
        }

    val previous: Track
        get() {
            if (currentItemIndex == 0) currentItemIndex = maxIndex else currentItemIndex--
            return current
        }

    val current: Track
        get() = data[currentItemIndex]

    class Track(
        val title: String,
        val artist: String,
        val bitmapResId: Int,
        uriString: String,
        val durationMs: Long
    ) {
        val uri: Uri = Uri.parse(uriString)
    }
}