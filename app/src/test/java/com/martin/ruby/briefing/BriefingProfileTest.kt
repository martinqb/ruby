package com.martin.ruby.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 1: the segments and who they're for. */
class BriefingProfileTest {

    @Test
    fun aNewProfile_hasEverythingButTheInterest() {
        val p = BriefingProfile()
        assertEquals(
            listOf(BriefingModule.GREETING, BriefingModule.GLANCE, BriefingModule.MUSIC, BriefingModule.HEADS_UP, BriefingModule.SIGN_OFF),
            p.spoken,
        )
        assertFalse(p.has(BriefingModule.INTEREST))
        // Turned on but nothing chosen: still nothing to say.
        assertFalse(p.with(BriefingModule.INTEREST, true).has(BriefingModule.INTEREST))
        assertTrue(p.with(BriefingModule.INTEREST, true).copy(interest = Interest.SKY_TONIGHT).has(BriefingModule.INTEREST))
    }

    @Test
    fun segmentsAreSpokenInTheOutlinesOrder() {
        val all = BriefingProfile(BriefingModule.entries.toSet(), Interest.SKY_TONIGHT)
        assertEquals(listOf("greeting", "glance", "interest", "music", "headsup", "signoff"), all.spoken.map { it.tag })
        val fewer = all.with(BriefingModule.MUSIC, false).with(BriefingModule.GREETING, false)
        assertEquals(listOf("glance", "interest", "headsup", "signoff"), fewer.spoken.map { it.tag })
    }

    @Test
    fun everythingOn_fitsWellUnderTwoMinutes() {
        val words = BriefingModule.entries.sumOf { it.words }
        assertTrue("$words words planned", words <= BriefingText.MAX_WORDS)
        assertTrue("${BriefingText.seconds(BriefingText.MAX_WORDS)} s at most", BriefingText.seconds(BriefingText.MAX_WORDS) < 120)
    }

    @Test
    fun savedAndReadBack_onThePhone() {
        val mine = BriefingProfile(BriefingModule.entries.toSet(), Interest.SKY_TONIGHT)
        assertEquals(mine, BriefingProfile.fromJson(mine.toJson()))
        val plain = BriefingProfile()
        assertEquals(plain, BriefingProfile.fromJson(plain.toJson()))
        // Nothing saved yet, or something unreadable: a new profile.
        assertEquals(BriefingProfile(), BriefingProfile.fromJson(null))
        assertEquals(BriefingProfile(), BriefingProfile.fromJson("not json"))
        // Names a later version added are skipped, not fatal.
        assertEquals(
            BriefingProfile(setOf(BriefingModule.GREETING), null),
            BriefingProfile.fromJson("""{"modules":["GREETING","HOROSCOPE"],"interest":"KNITTING"}"""),
        )
    }
}
