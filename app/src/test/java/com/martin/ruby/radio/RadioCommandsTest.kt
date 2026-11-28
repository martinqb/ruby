package com.martin.ruby.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCommandsTest {

    private fun play(station: Station) = RadioCommand.Play(station)

    /** Martin's profile: WGRR (default) and WEBN. */
    private val martin = Presets.MARTIN

    @Test
    fun playTheRadio_isWGRR_byDefault() {
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Play the radio", playing = false, presets = martin))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("put on the radio please", playing = false, presets = martin))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Turn on the radio.", playing = false, presets = martin))
    }

    @Test
    fun stationsByNameOrFrequency_asSpeechRecognitionWritesThem() {
        assertEquals(play(Stations.WEBN), RadioCommands.parse("Play WEBN", playing = false, presets = martin))
        assertEquals(play(Stations.WEBN), RadioCommands.parse("play web n", playing = false, presets = martin))
        assertEquals(play(Stations.WEBN), RadioCommands.parse("put on 102.7", playing = false, presets = martin))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Play WGRR", playing = true, presets = martin))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("switch to 103.5", playing = true, presets = martin))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("play w g r r", playing = false, presets = martin))
    }

    @Test
    fun stop() {
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Stop the radio", playing = true, presets = martin))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("turn off the radio", playing = false, presets = martin))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Radio off!", playing = true, presets = martin))
        // A bare "stop" is for the radio only while it plays; otherwise it's ordinary chat.
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Stop", playing = true, presets = martin))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("pause it please", playing = true, presets = martin))
        assertNull(RadioCommands.parse("Stop", playing = false, presets = martin))
    }

    @Test
    fun ordinaryMessages_areLeftToTheChat() {
        assertNull(RadioCommands.parse("Play a song by the Beatles", playing = false, presets = martin))
        assertNull(RadioCommands.parse("What's on the radio tonight?", playing = false, presets = martin))
        assertNull(RadioCommands.parse("Remind me at 10:35 to call Mom", playing = false, presets = martin))
        assertNull(RadioCommands.parse("Stop reminding me about the oven", playing = true, presets = martin))
        assertNull(RadioCommands.parse("I love the radio", playing = false, presets = martin))
    }

    @Test
    fun theStations() {
        assertEquals(Stations.WGRR, Presets.MARTIN.default?.station)
        assertTrue(Stations.WGRR.playsInRuby)
        assertTrue(Stations.WGRR.stream!!.contains("streamtheworld.com") && Stations.WGRR.stream!!.contains("WGRRFM"))
        // WEBN's only stream is iHeart's own player stream, so it opens in iHeartRadio.
        assertFalse(Stations.WEBN.playsInRuby)
        assertEquals(1701, Stations.WEBN.iHeartId)
    }

    @Test
    fun stopWhileAnotherAppPlays_isForThePhone_notTheAI() {
        // WEBN in iHeartRadio counts as playing: "stop" is a radio command, not chat.
        assertEquals(RadioCommand.Stop, RadioCommands.parse("stop", playing = true, presets = martin))
        assertEquals("Paused WEBN 102.7 in iHeartRadio.", RadioCommands.pauseReply(Stations.WEBN, stopped = true))
        assertEquals(
            "WEBN 102.7 is still playing in iHeartRadio. Want me to try pausing it again?",
            RadioCommands.pauseReply(Stations.WEBN, stopped = false),
        )
    }

    @Test
    fun yes_afterTheTryAgainOffer_isStop_andOnlyThen() {
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Yes", playing = true, presets = martin, retryOffered = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Yes, try again.", playing = true, presets = martin, retryOffered = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("try again", playing = true, presets = martin, retryOffered = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Sure please.", playing = true, presets = martin, retryOffered = true))
        // No offer made, or nothing playing any more: a "yes" is for the chat.
        assertNull(RadioCommands.parse("Yes", playing = true, presets = martin, retryOffered = false))
        assertNull(RadioCommands.parse("Yes", playing = false, presets = martin, retryOffered = true))
        // Anything more than a yes is for the chat too.
        assertNull(RadioCommands.parse("Yes, and what's the weather?", playing = true, presets = martin, retryOffered = true))
    }

    @Test
    fun webnsButton_isPlay_exceptWhileItPlaysInIHeartRadio() {
        val webn = Stations.WEBN
        assertEquals("Play", Radio.State().buttonLabel(webn))
        // Opened, but not (or no longer) playing there: back to Play.
        assertEquals("Play", Radio.State(inIHeart = webn, iHeartPlaying = false).buttonLabel(webn))
        assertEquals("Open", Radio.State(inIHeart = webn, iHeartPlaying = true).buttonLabel(webn))
        // Only WEBN's own button changes; stations that play in Ruby are always Play.
        assertEquals("Play", Radio.State(inIHeart = webn, iHeartPlaying = true).buttonLabel(Stations.WGRR))
    }

    @Test
    fun noReplyMentionsASteeringWheel() {
        for (app in listOf(Stations.WEBN, null)) {
            val reply = RadioCommands.pauseReply(app, stopped = false)
            assertFalse(reply, reply.contains("steering", ignoreCase = true))
            assertTrue(reply, reply.endsWith("Want me to try pausing it again?"))
        }
    }

    @Test
    fun stopReply_onlyClaimsWhatReallyStopped() {
        val wgrr = Stations.WGRR
        val webn = Stations.WEBN
        // Ruby's player didn't end: says so, claims nothing.
        assertEquals(
            "I pressed stop, but WGRR 103.5 is still playing. Try the Stop radio button.",
            RadioCommands.stopReply(wgrr, ourStopped = false, otherPlaying = false, otherStopped = false, app = null),
        )
        // Ruby's player ended and nothing else plays.
        assertEquals("Radio off.", RadioCommands.stopReply(wgrr, ourStopped = true, otherPlaying = false, otherStopped = false, app = null))
        // Ruby's player ended, then another app was paused too.
        assertEquals(
            "Radio off, and paused WEBN 102.7 in iHeartRadio.",
            RadioCommands.stopReply(wgrr, ourStopped = true, otherPlaying = true, otherStopped = true, app = webn),
        )
        assertEquals(
            "Radio off, and paused the other app.",
            RadioCommands.stopReply(wgrr, ourStopped = true, otherPlaying = true, otherStopped = true, app = null),
        )
        // Ruby's player ended, but the other app kept playing.
        assertEquals(
            "Radio off. Something's still playing in another app. Want me to try pausing it again?",
            RadioCommands.stopReply(wgrr, ourStopped = true, otherPlaying = true, otherStopped = false, app = null),
        )
        // Only another app was playing.
        assertEquals(
            "Paused WEBN 102.7 in iHeartRadio.",
            RadioCommands.stopReply(null, ourStopped = true, otherPlaying = true, otherStopped = true, app = webn),
        )
    }

    @Test
    fun theNoteForTheAI_saysWhatThePhoneReallyDid() {
        assertNull(RadioCommands.note(null, emptyList()))
        assertEquals(
            "Radio (done by the phone; this really happened): WEBN 102.7 was opened in the iHeartRadio app. " +
                "Recently: 9:10 AM: played WGRR 103.5 in Ruby; 9:11 AM: opened WEBN 102.7 in the iHeartRadio app.",
            RadioCommands.note(
                "WEBN 102.7 was opened in the iHeartRadio app",
                listOf("9:10 AM: played WGRR 103.5 in Ruby", "9:11 AM: opened WEBN 102.7 in the iHeartRadio app"),
            ),
        )
        // It goes with each message, after the reminders, in both engines (Free mode sends the same text).
        val sent = com.martin.ruby.chat.ClaudeChat.compose("stop", null, "9:12 AM", "Waiting reminders: none.", null, null, radio = "Radio (done by the phone; this really happened): x.")
        assertTrue(sent, sent.contains("[Waiting reminders: none.]\n[Radio (done by the phone; this really happened): x.]\nstop"))
        assertTrue(com.martin.ruby.ai.FreeChatText.SYSTEM_PROMPT.contains("never deny it"))
    }

    @Test
    fun aDroppedStream_isTriedAgain_withGrowingGaps_thenGivenUp() {
        val r = Reconnect()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L), List(5) { r.next(retriable = true) })
        assertNull("gives up after five tries", r.next(retriable = true))
        r.playing() // back on air: starts over
        assertEquals(2_000L, r.next(retriable = true))
        assertNull("a bad station address isn't retried", Reconnect().next(retriable = false))
        // Network and stream errors are worth retrying; a broken file format isn't.
        assertTrue(Reconnect.retriable(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(Reconnect.retriable(androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertFalse(Reconnect.retriable(androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test
    fun presets_newUsersHaveNone_andPlayTheRadioSaysSo() {
        assertEquals(RadioCommand.NoStations, RadioCommands.parse("Play the radio", playing = false, presets = Presets()))
        assertNull(Presets().default)
        // A station Ruby knows still plays by name.
        assertEquals(play(Stations.WGRR), RadioCommands.parse("play WGRR", playing = false, presets = Presets()))
    }

    @Test
    fun playAPresetByTheNameTheUserGaveIt() {
        val jazz = Station("rb:123", "WNKU Jazz 89.7", "Kentucky, USA", "https://example.com/jazz.mp3", null, listOf("WNKU Jazz 89.7"), "jazz")
        val mine = Presets.MARTIN.add(jazz).rename("rb:123", "Sunday jazz")
        assertEquals(play(jazz), RadioCommands.parse("Play Sunday jazz", playing = false, presets = mine))
        assertEquals(play(jazz), RadioCommands.parse("put on sunday jazz please", playing = false, presets = mine))
        // Its station name still works too, and the default is unchanged.
        assertEquals(play(jazz), RadioCommands.parse("play WNKU Jazz 89.7", playing = false, presets = mine))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("play the radio", playing = false, presets = mine))
    }

    @Test
    fun presets_addRenameReorderDefaultRemove() {
        val a = Station("a", "Alpha FM", "", "https://a", null)
        val b = Station("b", "Beta FM", "", "https://b", null)
        val c = Station("c", "Gamma FM", "", "https://c", null)
        var p = Presets().add(a).add(b).add(c).add(a) // a twice: saved once
        assertEquals(listOf("a", "b", "c"), p.list.map { it.station.id })
        assertEquals("a", p.default?.station?.id) // the first saved becomes the default
        p = p.move("c", -1).move("c", -1).move("c", -1) // up past the top: stays first
        assertEquals(listOf("c", "a", "b"), p.list.map { it.station.id })
        p = p.makeDefault("b").rename("b", "  My Beta  ").rename("a", "   ")
        assertEquals("b", p.default?.station?.id)
        assertEquals("My Beta", p.list.first { it.station.id == "b" }.label)
        assertEquals("Alpha FM", p.list.first { it.station.id == "a" }.label) // a blank name keeps the station's
        p = p.remove("b") // removing the default: the first left takes over
        assertEquals("c", p.default?.station?.id)
        assertEquals(p, Presets.fromJson(p.toJson())) // saved on the phone and read back
        assertEquals(Presets(), Presets.fromJson(null)) // a new user starts with none
        assertEquals(Presets.MARTIN, Presets.fromJson(Presets.MARTIN.toJson()))
    }

    @Test
    fun radioBrowsersAnswer_andIHeartStreams() {
        val json = """[
          {"stationuuid":"u1","name":"WVXU 91.7","url":"http://x","url_resolved":"https://playerservices.example/wvxu.mp3","state":"Ohio","country":"The United States Of America","tags":"news,npr,public radio"},
          {"stationuuid":"u2","name":"102.7 WEBN","url":"https://stream.revma.ihrhls.com/zc1701","url_resolved":"https://stream.revma.ihrhls.com/zc1701","state":"Cincinnati OH","country":"USA","tags":"rock"},
          {"stationuuid":"u3","name":"WVXU 91.7","url":"x","url_resolved":"https://playerservices.example/wvxu.mp3","state":"Ohio","country":"USA","tags":""},
          {"stationuuid":"u4","name":"","url":"x","url_resolved":"https://nameless"}
        ]"""
        val found = RadioBrowser.parse(json)
        assertEquals(listOf("WVXU 91.7", "102.7 WEBN"), found.map { it.name }) // no duplicates, no nameless
        val wvxu = found[0]
        assertTrue(wvxu.playsInRuby)
        assertEquals("rb:u1", wvxu.id)
        assertEquals("Ohio, The United States Of America", wvxu.city)
        assertEquals("news, npr", wvxu.genre)
        // iHeart's own player stream: not played in Ruby; opens in iHeartRadio by its station number.
        val webn = found[1]
        assertFalse(webn.playsInRuby)
        assertEquals(1701, webn.iHeartId)
        assertTrue(RadioBrowser.url(RadioBrowser.SERVERS[0], RadioBrowser.By.GENRE, "classic rock").contains("tag=classic+rock"))
        assertTrue(RadioBrowser.url(RadioBrowser.SERVERS[0], RadioBrowser.By.PLACE, "Ohio").contains("state=Ohio&hidebroken=true"))
        // Curated stations are found by search too (WGRR isn't in Radio Browser).
        assertEquals(listOf(Stations.WGRR), Stations.curatedMatching("WGRR"))
        assertEquals(Stations.CURATED, Stations.curatedMatching("Cincinnati"))
    }

    @Test
    fun afterTheBriefing_onlyWhenOn_inTheCar_andNotStopped() {
        assertTrue(RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = false, inCar = true, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = true, inCar = false, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = true))
    }
}
