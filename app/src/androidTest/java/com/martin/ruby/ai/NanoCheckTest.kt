package com.martin.ruby.ai

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.martin.ruby.MainActivity
import com.martin.ruby.briefing.BriefingFacts
import com.martin.ruby.briefing.BriefingModule
import com.martin.ruby.briefing.BriefingProfile
import com.martin.ruby.briefing.BriefingReminder
import com.martin.ruby.briefing.BriefingText
import com.martin.ruby.briefing.CalendarEvent
import com.martin.ruby.briefing.Interest
import com.martin.ruby.briefing.MusicMoment
import com.martin.ruby.briefing.SkyTonight
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * AI engine step 1: is Gemini Nano on this phone, and what can it do? Reports only, to the
 * log ("NanoCheck"). It never downloads anything: if the model isn't on the phone yet, it
 * says so and stops. Safe on a real phone; it briefly opens Ruby, because Gemini Nano only
 * answers apps in the foreground.
 */
@RunWith(AndroidJUnit4::class)
class NanoCheckTest {

    private fun log(message: String) = Log.i("NanoCheck", message)

    private fun client(preference: Int): GenerativeModel = Generation.getClient(
        GenerationConfig.builder().apply {
            modelConfig = ModelConfig.builder().apply {
                releaseStage = ModelReleaseStage.STABLE
                this.preference = preference
            }.build()
        }.build()
    )

    private fun statusName(s: Int) = when (s) {
        FeatureStatus.AVAILABLE -> "AVAILABLE (on the phone, ready)"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE (supported, not downloaded yet)"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE (not supported on this phone)"
        else -> "unknown ($s)"
    }

    private suspend fun <T> timed(what: String, block: suspend () -> T): T? {
        val started = SystemClock.elapsedRealtime()
        return try {
            withTimeout(120_000) { block() }.also { log("$what: OK in ${SystemClock.elapsedRealtime() - started} ms") }
        } catch (e: GenAiException) {
            log("$what: FAILED after ${SystemClock.elapsedRealtime() - started} ms, error ${e.errorCode}: ${e.message}")
            null
        } catch (e: Exception) {
            log("$what: FAILED after ${SystemClock.elapsedRealtime() - started} ms: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Test
    fun check() = runBlocking {
        val models = listOf("full" to ModelPreference.FULL, "fast" to ModelPreference.FAST)
        val available = mutableListOf<Pair<String, GenerativeModel>>()
        for ((name, preference) in models) {
            val model = client(preference)
            val status = timed("[$name] status") { model.checkStatus() }
            log("[$name] status: ${status?.let(::statusName)}")
            if (status != FeatureStatus.AVAILABLE) continue
            available += name to model
            timed("[$name] details") {
                log(
                    "[$name] model ${model.getBaseModelName()}, token limit ${model.getTokenLimit()}, " +
                        "system prompt ${model.isSystemPromptAvailable()}, structured output " +
                        "${model.isStructuredOutputFeatureAvailable()}, thinking ${model.isThinkingModeAvailable()}"
                )
            }
        }
        if (available.isEmpty()) {
            log("No model on the phone yet; nothing was downloaded. Stopping here.")
            return@runBlocking
        }

        // The morning briefing starts in the background (the car connecting): does Nano answer then?
        val (firstName, first) = available.first()
        timed("[$firstName] from the background") { first.generateContent("Say good morning in five words.").candidates.first().text }
            ?.let { log("[$firstName] background answer: $it") }

        // In the foreground, as when Ruby is open.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            SystemClock.sleep(1_500)
            for ((name, model) in available) {
                timed("[$name] warm-up") { model.warmup() }
                timed("[$name] hello") { model.generateContent("Say good morning in five words.").candidates.first().text }
                    ?.let { log("[$name] hello answer: $it") }

                // As Free mode writes it: the shorter prompt and Free mode's extra rules.
                val facts = sampleFacts()
                val system = BriefingText.SYSTEM_PROMPT + BriefingText.FREE_MODE_RULES
                val prompt = BriefingText.prompt(facts, compact = true)
                timed("[$name] count briefing tokens") {
                    model.countTokens(generateContentRequest(SystemInstruction(system), TextPart(prompt)) {}).totalTokens
                }?.let { log("[$name] briefing prompt: $it tokens") }
                prompt.lines().filter { it.startsWith("Moon:") || it.startsWith("Weather:") }.forEach { log("[$name] fact: $it") }
                val briefing = timed("[$name] write a briefing") {
                    model.generateContent(
                        generateContentRequest(SystemInstruction(system), TextPart(prompt)) { maxOutputTokens = 700 }
                    ).candidates.first().text
                }
                if (briefing != null) {
                    val parsed = BriefingText.parse(briefing, facts.segments)
                    log("[$name] briefing: ${parsed.parts.size} of ${facts.segments.size} parts tagged, ${parsed.words} words")
                    briefing.lines().forEach { log("[$name]   $it") }
                }

                val reminder = timed("[$name] a reminder as structured output") {
                    model.generateContent(
                        generateContentRequest(
                            SystemInstruction(
                                "Turn the user's request into one JSON object and nothing else: " +
                                    "{\"text\": what to remind, \"time\": \"HH:mm\" 24-hour or null, \"place\": a place name or null}. " +
                                    "It is now 08:49 on Tuesday, November 24, 2026."
                            ),
                            TextPart("Remind me to call Mom at 5:30 this afternoon"),
                        ) { maxOutputTokens = 100 }
                    ).candidates.first().text
                }
                if (reminder != null) {
                    log("[$name] reminder answer: $reminder")
                    val json = reminder.substringAfter('{', "").substringBeforeLast('}', "")
                    runCatching { JSONObject("{$json}") }
                        .onSuccess { log("[$name] reminder JSON: text=${it.optString("text")}, time=${it.optString("time")}, place=${it.optString("place")}") }
                        .onFailure { log("[$name] reminder JSON couldn't be read: ${it.message}") }
                }
            }
        } finally {
            scenario.close()
        }
    }

    /** Facts that tripped Nano up on the phone: tonight's full moon, clear now but overcast later, the Lucy fossil. */
    private fun sampleFacts(): BriefingFacts {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone).withHour(7).withMinute(42)
        val hours = (0 until 48).map { h ->
            com.martin.ruby.briefing.ForecastHour(now.toLocalDate().atStartOfDay().plusHours(h.toLong()), 27 + (h % 24) / 3, 10, 0.0, if (h < 10) 0 else 3, if (h < 10) 5 else 90, 12, 20000)
        }
        val forecast = com.martin.ruby.briefing.Forecast("F", "mph", 27, 0, 41, 25, 3, 10, null, null, hours)
        fun at(h: Int, m: Int) = now.withHour(h).withMinute(m).atZone(zone).toInstant().toEpochMilli()
        return BriefingFacts(
            now = now,
            profile = BriefingProfile(BriefingModule.entries.toSet(), Interest.SKY_TONIGHT),
            events = listOf(
                CalendarEvent("Team stand-up", at(9, 0), at(9, 15), false),
                CalendarEvent("Dentist", at(13, 30), at(14, 0), false, "Main Street Dental"),
            ),
            reminders = listOf(BriefingReminder("call Mom", dueMillis = at(17, 30))),
            headsUp = listOf("Rain likely from about 3:00 PM (70% chance): an umbrella would help."),
            forecast = forecast,
            sky = SkyTonight.compute(39.10, -84.51, now.toLocalDate(), zone, forecast),
            music = listOf(
                MusicMoment(1974, "Donald Johanson and Tom Gray discover the 40% complete Australopithecus afarensis skeleton, " +
                    "nicknamed \"Lucy\" (after The Beatles song \"Lucy in the Sky with Diamonds\"), in the Awash Valley of Ethiopia.", "event"),
                MusicMoment(1941, "Pete Best, Indian-English drummer and songwriter", "birth"),
            ),
            recentGreetings = listOf("Good morning! Coffee in hand, I hope."),
        )
    }
}
