/*
 * Copyright (c) 2026 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

package net.bible.service.device.speak.audio

/**
 * Plays recorded Bible chapters (one audio file per chapter) instead of text-to-speech.
 *
 * Provided by platforms that support it through [net.bible.android.control.speak.SpeakPlatform.audioBible];
 * when a Bible chapter has a recording, the speak service plays it with this player.
 * Callbacks arrive on the main thread.
 */
interface AudioBiblePlayer {
    /** Whether a recording of [chapter] should be played when reading the Bible module [bookInitials]. */
    fun hasAudio(bookInitials: String, chapter: AudioChapter): Boolean

    /**
     * Starts playing [chapter] from [startMs]. When a chapter ends the player continues with the next recorded
     * chapter, reporting each start with [AudioBibleListener.onChapterStarted].
     */
    fun play(chapter: AudioChapter, startMs: Long, listener: AudioBibleListener)
    fun pause()
    fun resume()
    fun stop()
    fun seekBy(deltaMs: Long)
    /** Playback speed; 1.0 is normal. */
    fun setSpeed(speed: Float)

    fun next(chapter: AudioChapter): AudioChapter?
    fun previous(chapter: AudioChapter): AudioChapter?

    val positionMs: Long
    val durationMs: Long
}

interface AudioBibleListener {
    fun onChapterStarted(chapter: AudioChapter)
    /** The last recorded chapter ended. */
    fun onFinished()
    fun onError(message: String)
}
