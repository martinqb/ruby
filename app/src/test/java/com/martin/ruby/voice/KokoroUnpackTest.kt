package com.martin.ruby.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class KokoroUnpackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, text) in entries) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val dir = KokoroModel.ARCHIVE_DIR

    @Test
    fun keepsWhatEnglishNeeds_andSkipsTheRest() {
        val dest = temp.newFolder("kokoro")
        KokoroModel.unpack(
            ByteArrayInputStream(
                archive(
                    "$dir/model.int8.onnx" to "model",
                    "$dir/voices.bin" to "voices",
                    "$dir/tokens.txt" to "tokens",
                    "$dir/lexicon-us-en.txt" to "us",
                    "$dir/lexicon-zh.txt" to "zh", // not needed for English
                    "$dir/espeak-ng-data/en_dict" to "espeak", // ignored by this sherpa-onnx version
                )
            ),
            dest,
        )
        assertEquals(setOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"), dest.list()!!.toSet())
        assertEquals("model", File(dest, "model.int8.onnx").readText())
        assert(KokoroModel.isInstalled(dest))
    }

    @Test
    fun pathsThatTryToClimbOut_areRefused() {
        val outer = temp.newFolder("outer")
        val dest = File(outer, "kokoro")
        KokoroModel.unpack(
            ByteArrayInputStream(
                archive(
                    "$dir/../voices.bin" to "escaped",
                    "../../voices.bin" to "escaped",
                    "/tmp/voices.bin" to "escaped",
                    "other-dir/voices.bin" to "wrong folder",
                    "$dir/sub/voices.bin" to "nested",
                    "$dir/tokens.txt" to "tokens",
                )
            ),
            dest,
        )
        assertEquals(listOf("tokens.txt"), dest.list()!!.toList())
        assertFalse(File(outer, "voices.bin").exists())
        assertFalse(KokoroModel.isInstalled(dest))
    }

    @Test
    fun speakers_matchTheModelsSpeakerNumbers() {
        // From sherpa-onnx's speaker table for kokoro-multi-lang-v1_0.
        assertEquals(3, KokoroSpeaker.HEART.sid)
        assertEquals(6, KokoroSpeaker.NICOLE.sid)
        assertEquals(KokoroSpeaker.HEART, com.martin.ruby.voice.VoicePrefs().kokoroSpeaker)
        assertEquals(VoiceEngine.KOKORO, com.martin.ruby.voice.VoicePrefs().engine)
    }
}
