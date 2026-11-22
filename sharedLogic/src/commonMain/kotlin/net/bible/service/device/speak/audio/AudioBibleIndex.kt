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

import org.crosswire.jsword.versification.BibleBook

/** One recorded chapter of an audio Bible. */
data class AudioChapter(val book: BibleBook, val chapter: Int) : Comparable<AudioChapter> {
    override fun compareTo(other: AudioChapter): Int =
        compareValuesBy(this, other, { it.book.ordinal }, { it.chapter })
}

/**
 * Recognises Bible Brain (Faith Comes By Hearing) chapter file names.
 *
 * Bible Brain numbers books per testament: `A01`..`A39` for the Old Testament and `B01`..`B27` for the
 * New Testament (Protestant order). Two layouts are used by its downloads:
 *  - `ENGESHO1DA_A01_GEN_001.mp3` (fileset, testament+book, USFM code, chapter)
 *  - `B01___01_Matthew_____ENGESHN1DA.mp3` (testament+book, chapter, padded name, fileset)
 */
object AudioBibleFileNames {
    private val codeLayout = Regex("""(?:^|_)([AB])(\d{2})_[0-9A-Z]{3}_(\d{1,3})\.mp3$""", RegexOption.IGNORE_CASE)
    private val nameLayout = Regex("""^([AB])(\d{2})_+(\d{1,3})_.*\.mp3$""", RegexOption.IGNORE_CASE)

    private val oldTestament = BibleBook.entries.subList(BibleBook.GEN.ordinal, BibleBook.MAL.ordinal + 1)
    private val newTestament = BibleBook.entries.subList(BibleBook.MATT.ordinal, BibleBook.REV.ordinal + 1)

    /** The chapter a file holds, or null if the name is not a recognised chapter file. */
    fun parse(fileName: String): AudioChapter? {
        val match = codeLayout.find(fileName) ?: nameLayout.find(fileName) ?: return null
        val (testament, bookNo, chapterNo) = match.destructured
        val books = if (testament.equals("A", ignoreCase = true)) oldTestament else newTestament
        val book = books.getOrNull(bookNo.toInt() - 1) ?: return null
        val chapter = chapterNo.toInt().takeIf { it > 0 } ?: return null
        return AudioChapter(book, chapter)
    }
}

/**
 * Maps chapters to the location (a platform URI or path) of their audio file, and gives the playing order.
 */
class AudioBibleIndex(private val locations: Map<AudioChapter, String>) {
    private val ordered: List<AudioChapter> = locations.keys.sorted()

    val size: Int get() = locations.size
    fun locationOf(chapter: AudioChapter): String? = locations[chapter]
    fun contains(chapter: AudioChapter): Boolean = chapter in locations

    /** The next recorded chapter in Bible order, crossing into the next book; null after the last one. */
    fun next(chapter: AudioChapter): AudioChapter? = ordered.firstOrNull { it > chapter }

    /** The previous recorded chapter in Bible order; null before the first one. */
    fun previous(chapter: AudioChapter): AudioChapter? = ordered.lastOrNull { it < chapter }

    /** One `OSIS-book<TAB>chapter<TAB>location` line per chapter, for caching a scan. */
    fun serialize(): String = ordered.joinToString("\n") { "${it.book.osis}\t${it.chapter}\t${locations[it]}" }

    companion object {
        /** Builds an index from `(fileName, location)` pairs, ignoring files that are not chapter recordings. */
        fun fromFiles(files: Iterable<Pair<String, String>>): AudioBibleIndex =
            AudioBibleIndex(files.mapNotNull { (name, location) -> AudioBibleFileNames.parse(name)?.let { it to location } }.toMap())

        fun deserialize(text: String): AudioBibleIndex = AudioBibleIndex(
            text.lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val book = BibleBook.fromExactOSIS(parts[0]) ?: return@mapNotNull null
                val chapter = parts[1].toIntOrNull() ?: return@mapNotNull null
                AudioChapter(book, chapter) to parts[2]
            }.toMap()
        )
    }
}
