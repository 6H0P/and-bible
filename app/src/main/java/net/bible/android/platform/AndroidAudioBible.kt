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

package net.bible.android.platform

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.bible.android.activity.R
import net.bible.service.device.speak.audio.AudioBibleIndex
import net.bible.service.device.speak.audio.AudioBibleListener
import net.bible.service.device.speak.audio.AudioBiblePlayer
import net.bible.service.device.speak.audio.AudioChapter
import net.bible.service.device.speak.audio.AudioVerseTimings
import net.bible.sharedcore.log.Log
import net.bible.sharedui.reading.host.slots.AudioBibleFolderRow
import java.io.File

private const val TAG = "AudioBible"

/**
 * The user's audio Bible: a folder of chapter recordings chosen with the system folder picker.
 *
 * The folder is kept in device-local preferences (a content URI is meaningless on another device), and the
 * chapter index from the last scan is cached in the app's files so playback can start without rescanning.
 */
class AudioBibleLibrary(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("audio_bible", Context.MODE_PRIVATE)
    private val cacheFile = File(this.context.filesDir, "audio_bible_index.tsv")
    private val timingsCacheFile = File(this.context.filesDir, "audio_bible_verse_timings.tsv")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var index: AudioBibleIndex = loadCachedIndex()
        private set

    /** Verse start times from the folder's verse_timings.tsv, for highlighting the verse being read. */
    @Volatile var timings: AudioVerseTimings = loadCachedTimings()
        private set

    private val _row = MutableStateFlow(row(scanning = false))
    /** The settings row shown in the advanced Speak page. */
    val row: StateFlow<AudioBibleFolderRow?> = _row

    private val folderUri: Uri? get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** Recordings are used for Bible modules whose initials contain this text (the audio is an ESV reading). */
    fun appliesTo(bookInitials: String): Boolean = bookInitials.contains(MODULE_MATCH, ignoreCase = true)

    /** Remembers [uri] (from ACTION_OPEN_DOCUMENT_TREE) and scans it for chapter recordings. */
    fun setFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not keep access to audio Bible folder", e)
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        rescan()
    }

    fun rescan() {
        val uri = folderUri ?: return
        _row.value = row(scanning = true)
        scope.launch {
            val files = try {
                listFiles(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Scanning audio Bible folder failed", e)
                emptyList()
            }
            val scanned = AudioBibleIndex.fromFiles(files)
            index = scanned
            val timingsText = files.firstOrNull { it.first == AudioVerseTimings.FILE_NAME }?.let { (_, location) ->
                try {
                    context.contentResolver.openInputStream(Uri.parse(location))?.use { it.reader().readText() }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not read verse timings", e)
                    null
                }
            } ?: ""
            timings = AudioVerseTimings.parse(timingsText)
            try {
                cacheFile.writeText(scanned.serialize())
                timingsCacheFile.writeText(timingsText)
            } catch (e: Exception) {
                Log.e(TAG, "Could not cache audio Bible index", e)
            }
            Log.i(TAG, "Audio Bible folder has ${scanned.size} chapter recordings, ${timings.chapterCount} with verse timings")
            _row.value = row(scanning = false)
        }
    }

    /** (display name, document URI) of every file in the folder tree, a few levels deep. */
    private fun listFiles(treeUri: Uri): List<Pair<String, String>> {
        val resolver = context.contentResolver
        val files = mutableListOf<Pair<String, String>>()
        fun walk(documentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_FOLDER_DEPTH) walk(id, depth + 1)
                    } else {
                        files += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri), 0)
        return files
    }

    private fun loadCachedIndex(): AudioBibleIndex = try {
        if (cacheFile.exists()) AudioBibleIndex.deserialize(cacheFile.readText()) else AudioBibleIndex(emptyMap())
    } catch (e: Exception) {
        Log.e(TAG, "Could not read cached audio Bible index", e)
        AudioBibleIndex(emptyMap())
    }

    private fun loadCachedTimings(): AudioVerseTimings = try {
        if (timingsCacheFile.exists()) AudioVerseTimings.parse(timingsCacheFile.readText()) else AudioVerseTimings.EMPTY
    } catch (e: Exception) {
        Log.e(TAG, "Could not read cached verse timings", e)
        AudioVerseTimings.EMPTY
    }

    private fun row(scanning: Boolean): AudioBibleFolderRow {
        val summary = when {
            folderUri == null -> context.getString(R.string.audio_bible_folder_not_set)
            scanning -> context.getString(R.string.audio_bible_folder_scanning)
            index.size == 0 -> context.getString(R.string.audio_bible_folder_empty)
            timings.chapterCount > 0 ->
                context.getString(R.string.audio_bible_folder_found_with_timings, index.size, MODULE_MATCH)
            else -> context.getString(R.string.audio_bible_folder_found, index.size, MODULE_MATCH)
        }
        return AudioBibleFolderRow(context.getString(R.string.audio_bible_folder_title), summary)
    }

    companion object {
        private const val KEY_FOLDER = "folder_uri"
        private const val MODULE_MATCH = "ESV"
        private const val MAX_FOLDER_DEPTH = 3

        @Volatile private var instance: AudioBibleLibrary? = null
        fun get(context: Context): AudioBibleLibrary =
            instance ?: synchronized(this) { instance ?: AudioBibleLibrary(context).also { instance = it } }
    }
}

/**
 * Plays chapter recordings from [library] with [MediaPlayer], continuing chapter by chapter.
 * Callbacks run on the main thread (the player is created from the speak service's main-thread calls).
 */
class AndroidAudioBiblePlayer(context: Context, private val library: AudioBibleLibrary) : AudioBiblePlayer {
    private companion object {
        const val VERSE_TICK_MS = 200L
    }

    private val context = context.applicationContext
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlaying = false
    private var speed = 1f
    private var current: AudioChapter? = null
    /** Position to resume from when the player had to be recreated. */
    private var resumeFromMs = 0L
    private var listener: AudioBibleListener? = null
    private var lastVerse: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    /** While playing, reports the verse being read whenever it changes (needs verse timings). */
    private val verseTicker = object : Runnable {
        override fun run() {
            val mp = player
            val chapter = current
            if (mp == null || chapter == null || !prepared) return
            val verse = library.timings.verseAt(chapter, mp.currentPosition.toLong())
            if (verse != null && verse != lastVerse) {
                lastVerse = verse
                listener?.onVerseStarted(chapter, verse)
            }
            if (mp.isPlaying) handler.postDelayed(this, VERSE_TICK_MS)
        }
    }

    override fun hasAudio(bookInitials: String, chapter: AudioChapter): Boolean =
        library.appliesTo(bookInitials) && library.index.contains(chapter)

    override fun play(chapter: AudioChapter, startMs: Long, listener: AudioBibleListener) {
        release()
        current = chapter
        this.listener = listener
        lastVerse = null
        wantPlaying = true
        val location = library.index.locationOf(chapter)
        if (location == null) {
            listener.onError(context.getString(R.string.audio_bible_playback_error))
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(context, Uri.parse(location))
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                if (startMs > 0) mp.seekTo(startMs.toInt())
                if (wantPlaying) startPlayback(mp)
                listener.onChapterStarted(chapter)
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                val next = library.index.next(chapter)
                if (next != null) {
                    play(next, 0L, listener)
                } else {
                    release()
                    current = null
                    listener.onFinished()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error $what/$extra for $chapter")
                if (player === mp) {
                    release()
                    listener.onError(context.getString(R.string.audio_bible_playback_error))
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open recording of $chapter", e)
            release()
            listener.onError(context.getString(R.string.audio_bible_playback_error))
        }
    }

    private fun startPlayback(mp: MediaPlayer) {
        mp.start()
        handler.removeCallbacks(verseTicker)
        handler.post(verseTicker)
        // Setting playback params on a paused player would start it, so speed is applied only while playing.
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
            Log.e(TAG, "Could not set audio Bible speed $speed", e)
        }
    }

    override fun pause() {
        wantPlaying = false
        val mp = player ?: return
        if (prepared && mp.isPlaying) {
            resumeFromMs = mp.currentPosition.toLong()
            mp.pause()
        }
    }

    override fun resume() {
        wantPlaying = true
        val mp = player
        if (mp != null && prepared) {
            startPlayback(mp)
        }
        // Not yet prepared: the prepared listener starts playback because wantPlaying is set.
    }

    override fun stop() {
        wantPlaying = false
        release()
        current = null
    }

    override fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        if (!prepared) return
        val target = (mp.currentPosition + deltaMs).coerceIn(0L, mp.duration.toLong().coerceAtLeast(0L))
        mp.seekTo(target.toInt())
        handler.removeCallbacks(verseTicker)
        handler.post(verseTicker)
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 2.5f)
        val mp = player ?: return
        if (prepared && mp.isPlaying) startPlayback(mp)
    }

    override fun next(chapter: AudioChapter): AudioChapter? = library.index.next(chapter)
    override fun previous(chapter: AudioChapter): AudioChapter? = library.index.previous(chapter)
    override fun verseStartMs(chapter: AudioChapter, verse: Int): Long? = library.timings.startOf(chapter, verse)

    override val positionMs: Long get() = player?.takeIf { prepared }?.currentPosition?.toLong() ?: resumeFromMs
    override val durationMs: Long get() = player?.takeIf { prepared }?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L

    private fun release() {
        handler.removeCallbacks(verseTicker)
        val mp = player ?: return
        player = null
        prepared = false
        try {
            mp.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPlayer", e)
        }
    }
}
