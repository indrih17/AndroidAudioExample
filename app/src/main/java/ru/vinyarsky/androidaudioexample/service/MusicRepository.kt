package ru.vinyarsky.androidaudioexample.service

import android.net.Uri
import android.os.Parcelable
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import ru.vinyarsky.androidaudioexample.R

/**
 * Музыкальный трек.
 * @param title Название.
 * @param artist Автор трека.
 * @param bitmapResId Ресурс на битмапу, которая будет отображаться в пуше.
 * @param uriString Ссылка, откуда брать трек.
 * @param durationMs Продолжительность трека (в миллисекундах).
 * @param repeatable Зациклить трек или нет.
 * @property uri Ссылка на трек в виде [Uri].
 */
class Track(
    val title: String,
    val artist: String,
    val bitmapResId: Int,
    uriString: String,
    val durationMs: Long,
    val repeatable: Boolean = false
) {
    val uri: Uri = Uri.parse(uriString)
}

interface MusicRepository : Parcelable {
    val current: Track
}

interface SingleTrackRepository : MusicRepository

interface MultiTrackRepository : MusicRepository {
    fun previous(): Track
    fun next(): Track
}

@Parcelize
class SingleTrackRepositoryImpl : SingleTrackRepository {
    override val current: Track
        get() = Track(
            "Triangle",
            "Jason Shaw",
            R.drawable.image266680,
            uriString = "https://codeskulptor-demos.commondatastorage.googleapis.com/pang/paza-moduless.mp3",
            durationMs = (3 * 60 + 41) * 1000
        )
}

@Parcelize
class MultiTrackRepositoryImpl : MultiTrackRepository {
    @IgnoredOnParcel
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

    @IgnoredOnParcel
    private val maxIndex = data.size - 1

    @IgnoredOnParcel
    private var currentItemIndex = 0

    override fun next(): Track {
        if (currentItemIndex == maxIndex) currentItemIndex = 0 else currentItemIndex++
        return current
    }

    override fun previous(): Track {
        if (currentItemIndex == 0) currentItemIndex = maxIndex else currentItemIndex--
        return current
    }

    override val current: Track
        get() = data[currentItemIndex]
}
