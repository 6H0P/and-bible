#!/usr/bin/env python3
"""
Work out when each verse starts in audio Bible chapter recordings.

Forced alignment with Meta's MMS model (torchaudio.pipelines.MMS_FA): the known verse text of each chapter is
aligned against its recording, and the start time of each verse's first word is written out. The result,
`verse_timings.tsv`, goes in the audio Bible folder next to the recordings; AndBible reads it to highlight the verse
being read.

Output lines: OSIS-book <TAB> chapter <TAB> verse <TAB> start-milliseconds.

Text comes from a SWORD Bible module (e.g. the ESV module installed in AndBible); recordings are Bible Brain
chapter files, one MP3 per chapter, named like ENGESHO1DA_A01_GEN_001.mp3 or B01___01_Matthew_____ENGESHN1DA.mp3.

Usage:
  align_verses.py --sword-dir DIR --module ESV2011 --audio-dir DIR --out verse_timings.tsv [--only Gen.1 ...]

Already aligned chapters in --out are skipped, so an interrupted run can be resumed.
"""

import argparse
import re
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

import torch
import torchaudio
from pysword.modules import SwordModules

OT = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl Song "
      "Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal").split()
NT = ("Matt Mark Luke John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas "
      "1Pet 2Pet 1John 2John 3John Jude Rev").split()

CODE_LAYOUT = re.compile(r"(?:^|_)([AB])(\d{2})_[0-9A-Z]{3}_(\d{1,3})\.mp3$", re.IGNORECASE)
NAME_LAYOUT = re.compile(r"^([AB])(\d{2})_+(\d{1,3})_.*\.mp3$", re.IGNORECASE)

SAMPLE_RATE = 16000
CHUNK_SECONDS = 30
MIN_CHUNK_SAMPLES = SAMPLE_RATE // 10  # 100 ms


def parse_audio_name(name):
    """(testament index 0/1, book index within testament, chapter) or None, like AudioBibleFileNames in the app."""
    match = CODE_LAYOUT.search(name) or NAME_LAYOUT.search(name)
    if not match:
        return None
    testament, book, chapter = match.groups()
    books = OT if testament.upper() == "A" else NT
    book_index = int(book) - 1
    if not 0 <= book_index < len(books) or int(chapter) < 1:
        return None
    return (0 if books is OT else 1), book_index, int(chapter)


def find_recordings(audio_dir):
    """{(osis, chapter): path} for every chapter recording under audio_dir."""
    found = {}
    for path in sorted(Path(audio_dir).rglob("*.mp3")):
        parsed = parse_audio_name(path.name)
        if parsed:
            testament, book_index, chapter = parsed
            osis = (OT if testament == 0 else NT)[book_index]
            found[(osis, chapter)] = path
    return found


def load_audio(path):
    """Mono 16 kHz float waveform, decoded with ffmpeg."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return torch.frombuffer(bytearray(raw), dtype=torch.float32)


def normalize_words(text, dictionary):
    """Lower-case words reduced to the aligner's alphabet; words with no usable letters are dropped."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = text.lower().replace("’", "'")
    words = []
    for word in re.split(r"[\s\-—–]+", text):
        letters = "".join(ch for ch in word if ch in dictionary)
        if letters:
            words.append(letters)
    return words


class Aligner:
    def __init__(self):
        bundle = torchaudio.pipelines.MMS_FA
        self.device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
        self.model = bundle.get_model(with_star=True).to(self.device)
        self.dictionary = bundle.get_dict(star="*")
        self.frame_ms = 1000 * 320 / SAMPLE_RATE  # the model emits one frame per 320 samples

    @torch.inference_mode()
    def emissions(self, waveform):
        chunk = CHUNK_SECONDS * SAMPLE_RATE
        parts = []
        for start in range(0, waveform.numel(), chunk):
            piece = waveform[start:start + chunk]
            if piece.numel() < MIN_CHUNK_SAMPLES:
                # A sliver left at the end is too short for the model's convolutions; pad it with silence.
                piece = torch.nn.functional.pad(piece, (0, MIN_CHUNK_SAMPLES - piece.numel()))
            piece = piece.unsqueeze(0).to(self.device)
            emission, _ = self.model(piece)
            parts.append(emission[0].cpu())
        return torch.cat(parts)

    def verse_starts(self, waveform, verses):
        """Start time (ms) of each verse, aligned against the whole chapter; verses is [(number, text)]."""
        # A leading '*' wildcard absorbs the spoken book/chapter announcement before verse 1.
        words, verse_first_word = ["*"], {}
        for number, text in verses:
            verse_words = normalize_words(text, self.dictionary)
            if not verse_words:
                continue
            verse_first_word[number] = len(words)
            words.extend(verse_words)
        tokens = [self.dictionary[ch] for word in words for ch in word]
        emission = self.emissions(waveform)
        if emission.shape[0] < len(tokens):
            raise ValueError(f"recording too short for its text ({emission.shape[0]} frames, {len(tokens)} tokens)")
        labels, _ = torchaudio.functional.forced_align(
            emission.unsqueeze(0), torch.tensor([tokens], dtype=torch.int32), blank=0
        )
        token_spans = torchaudio.functional.merge_tokens(labels[0], torch.zeros(labels.shape[1]))
        # Map each token span back to its word: first token of a word gives the word's start frame.
        word_start_frame, token_index = [], 0
        for word in words:
            word_start_frame.append(token_spans[token_index].start)
            token_index += len(word)
        return {number: round(word_start_frame[index] * self.frame_ms) for number, index in verse_first_word.items()}


def chapter_verses(bible, structure, osis, chapter):
    testament = "ot" if osis in OT else "nt"
    book = structure.get_books()[testament][(OT if testament == "ot" else NT).index(osis)]
    count = book.chapter_lengths[chapter - 1]
    return book.name, [
        (verse, bible.get(books=[book.name], chapters=[chapter], verses=[verse], clean=True))
        for verse in range(1, count + 1)
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sword-dir", required=True, help="SWORD folder containing mods.d and modules")
    parser.add_argument("--module", required=True, help="Bible module name, e.g. ESV2011")
    parser.add_argument("--audio-dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", nargs="*", help="Only these chapters, e.g. Gen.1 John.3")
    args = parser.parse_args()

    modules = SwordModules(args.sword_dir)
    modules.parse_modules()
    bible = modules.get_bible_from_module(args.module)
    structure = bible.get_structure()

    recordings = find_recordings(args.audio_dir)
    out = Path(args.out)
    done = set()
    if out.exists():
        for line in out.read_text().splitlines():
            parts = line.split("\t")
            if len(parts) == 4:
                done.add((parts[0], int(parts[1])))
    wanted = {tuple([c.split(".")[0], int(c.split(".")[1])]) for c in args.only} if args.only else None

    aligner = Aligner()
    todo = [key for key in sorted(recordings, key=lambda k: ((OT + NT).index(k[0]), k[1]))
            if key not in done and (wanted is None or key in wanted)]
    print(f"{len(recordings)} recordings, {len(done)} already aligned, {len(todo)} to do", flush=True)
    failures = 0
    with out.open("a") as sink:
        for n, (osis, chapter) in enumerate(todo, 1):
            started = time.time()
            try:
                _, verses = chapter_verses(bible, structure, osis, chapter)
                starts = aligner.verse_starts(load_audio(recordings[(osis, chapter)]), verses)
                for verse in sorted(starts):
                    sink.write(f"{osis}\t{chapter}\t{verse}\t{starts[verse]}\n")
                sink.flush()
                print(f"[{n}/{len(todo)}] {osis} {chapter}: {len(starts)} verses in {time.time() - started:.1f}s",
                      flush=True)
            except Exception as e:
                failures += 1
                print(f"[{n}/{len(todo)}] {osis} {chapter}: FAILED {e}", file=sys.stderr, flush=True)
    print(f"Done. {failures} chapters failed.", flush=True)


if __name__ == "__main__":
    main()
