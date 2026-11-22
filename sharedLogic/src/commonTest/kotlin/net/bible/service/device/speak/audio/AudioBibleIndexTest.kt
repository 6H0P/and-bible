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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioBibleIndexTest {
    @Test
    fun parsesOldTestamentCodeLayout() {
        assertEquals(AudioChapter(BibleBook.GEN, 1), AudioBibleFileNames.parse("ENGESHO1DA_A01_GEN_001.mp3"))
        assertEquals(AudioChapter(BibleBook.PS, 119), AudioBibleFileNames.parse("ENGESHO1DA_A19_PSA_119.mp3"))
        assertEquals(AudioChapter(BibleBook.MAL, 4), AudioBibleFileNames.parse("ENGESHO1DA_A39_MAL_004.mp3"))
    }

    @Test
    fun parsesNewTestamentNameLayout() {
        assertEquals(AudioChapter(BibleBook.MATT, 1), AudioBibleFileNames.parse("B01___01_Matthew_____ENGESHN1DA.mp3"))
        assertEquals(AudioChapter(BibleBook.COR1, 12), AudioBibleFileNames.parse("B07___12_1CorinthiansENGESHN1DA.mp3"))
        assertEquals(AudioChapter(BibleBook.HEB, 13), AudioBibleFileNames.parse("B19___13_Hebrews_____ENGESHN1DA.mp3"))
        assertEquals(AudioChapter(BibleBook.REV, 22), AudioBibleFileNames.parse("B27___22_Revelation__ENGESHN1DA.mp3"))
    }

    @Test
    fun parsesNewTestamentInCodeLayout() {
        assertEquals(AudioChapter(BibleBook.JOHN, 3), AudioBibleFileNames.parse("ENGESHN1DA_B04_JHN_003.mp3"))
    }

    @Test
    fun ignoresNonChapterFiles() {
        assertNull(AudioBibleFileNames.parse("copyright.pdf"))
        assertNull(AudioBibleFileNames.parse("cover.jpg"))
        assertNull(AudioBibleFileNames.parse("ENGESHO1DA_A01_GEN_001.wav"))
        assertNull(AudioBibleFileNames.parse("ENGESHO1DA_A40_XXX_001.mp3")) // only 39 OT books
        assertNull(AudioBibleFileNames.parse("B28___01_Extra_______ENGESHN1DA.mp3")) // only 27 NT books
        assertNull(AudioBibleFileNames.parse("ENGESHO1DA_A01_GEN_000.mp3"))
    }

    private val index = AudioBibleIndex.fromFiles(
        listOf(
            "ENGESHO1DA_A01_GEN_049.mp3" to "uri:gen49",
            "ENGESHO1DA_A01_GEN_050.mp3" to "uri:gen50",
            "ENGESHO1DA_A02_EXO_001.mp3" to "uri:exo1",
            "ENGESHO1DA_A39_MAL_004.mp3" to "uri:mal4",
            "B01___01_Matthew_____ENGESHN1DA.mp3" to "uri:matt1",
            "copyright.pdf" to "uri:copyright",
        )
    )

    @Test
    fun buildsIndexSkippingOtherFiles() {
        assertEquals(5, index.size)
        assertEquals("uri:exo1", index.locationOf(AudioChapter(BibleBook.EXOD, 1)))
        assertNull(index.locationOf(AudioChapter(BibleBook.EXOD, 2)))
    }

    @Test
    fun nextAndPreviousFollowBibleOrderAcrossBooksAndTestaments() {
        assertEquals(AudioChapter(BibleBook.GEN, 50), index.next(AudioChapter(BibleBook.GEN, 49)))
        assertEquals(AudioChapter(BibleBook.EXOD, 1), index.next(AudioChapter(BibleBook.GEN, 50)))
        assertEquals(AudioChapter(BibleBook.MATT, 1), index.next(AudioChapter(BibleBook.MAL, 4)))
        assertNull(index.next(AudioChapter(BibleBook.MATT, 1)))
        assertEquals(AudioChapter(BibleBook.GEN, 50), index.previous(AudioChapter(BibleBook.EXOD, 1)))
        assertNull(index.previous(AudioChapter(BibleBook.GEN, 49)))
    }

    @Test
    fun serializationRoundTrips() {
        val restored = AudioBibleIndex.deserialize(index.serialize())
        assertEquals(index.size, restored.size)
        assertEquals("uri:matt1", restored.locationOf(AudioChapter(BibleBook.MATT, 1)))
        assertEquals(AudioChapter(BibleBook.EXOD, 1), restored.next(AudioChapter(BibleBook.GEN, 50)))
    }

    private val timings = AudioVerseTimings.parse(
        // Unsorted on purpose, plus a header-like line and a damaged line that must be ignored.
        "book\tchapter\tverse\tms\nGen\t1\t2\t8100\nGen\t1\t1\t4360\nGen\t1\t3\t17180\nJohn\t3\t16\t120500\nGen\t1\tx\t1\n"
    )

    @Test
    fun verseAtFindsTheVerseBeingRead() {
        val gen1 = AudioChapter(BibleBook.GEN, 1)
        assertNull(timings.verseAt(gen1, 1000)) // chapter announcement, before verse 1
        assertEquals(1, timings.verseAt(gen1, 4360))
        assertEquals(1, timings.verseAt(gen1, 8099))
        assertEquals(2, timings.verseAt(gen1, 8100))
        assertEquals(3, timings.verseAt(gen1, 600_000)) // past the last start: still the last verse
        assertEquals(16, timings.verseAt(AudioChapter(BibleBook.JOHN, 3), 130_000))
        assertNull(timings.verseAt(AudioChapter(BibleBook.EXOD, 1), 5000)) // no timings for the chapter
        assertEquals(2, timings.chapterCount)
    }

    @Test
    fun startOfGivesTheVerseStart() {
        assertEquals(8100L, timings.startOf(AudioChapter(BibleBook.GEN, 1), 2))
        assertNull(timings.startOf(AudioChapter(BibleBook.GEN, 1), 31))
    }

    @Test
    fun deserializeSkipsDamagedLines() {
        val restored = AudioBibleIndex.deserialize("Gen\t1\turi:a\nnot a line\nNope\t2\turi:b\nExod\tx\turi:c")
        assertEquals(1, restored.size)
    }
}
