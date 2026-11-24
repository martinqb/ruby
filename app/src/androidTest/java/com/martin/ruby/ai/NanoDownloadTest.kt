package com.martin.ruby.ai

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.martin.ruby.MainActivity
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Downloads the full Gemini Nano model, logging progress ("NanoDownload"). DOWNLOADS
 * GIGABYTES: run it only when asked, on Wi-Fi (Martin asked on 2026-11-24). It keeps Ruby
 * open meanwhile, since Gemini Nano works with apps in the foreground.
 */
@RunWith(AndroidJUnit4::class)
class NanoDownloadTest {

    private fun log(message: String) = Log.i("NanoDownload", message)

    @Test
    fun downloadTheFullModel() = runBlocking {
        val model = Generation.getClient(
            GenerationConfig.builder().apply {
                modelConfig = ModelConfig.builder().apply {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = ModelPreference.FULL
                }.build()
            }.build()
        )
        val before = model.checkStatus()
        log("Status before: $before (0 unavailable, 1 downloadable, 2 downloading, 3 available)")
        if (before == FeatureStatus.AVAILABLE) {
            log("Already on the phone; nothing to download.")
            return@runBlocking
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val started = SystemClock.elapsedRealtime()
            var total = 0L
            var lastLogged = -1L
            withTimeout(90 * 60_000L) {
                model.download().takeWhile { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            total = status.bytesToDownload
                            log("Started: %.2f GB to download".format(total / 1e9))
                            true
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val done = status.totalBytesDownloaded
                            val percent = if (total > 0) done * 100 / total else -1
                            // About every 5%, so the log stays readable.
                            if (percent / 5 != lastLogged / 5) {
                                lastLogged = percent
                                val secs = (SystemClock.elapsedRealtime() - started) / 1000
                                log("Progress: $percent% (%.2f of %.2f GB) after ${secs}s".format(done / 1e9, total / 1e9))
                            }
                            true
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            log("Completed in ${(SystemClock.elapsedRealtime() - started) / 1000} s.")
                            false
                        }
                        is DownloadStatus.DownloadFailed -> {
                            log("FAILED: error ${status.e.errorCode}: ${status.e.message}")
                            false
                        }
                        else -> true
                    }
                }.collect {}
            }
        } finally {
            scenario.close()
        }
        val after = model.checkStatus()
        log("Status after: $after")
        assertEquals("The model should now be on the phone", FeatureStatus.AVAILABLE, after)
    }
}
