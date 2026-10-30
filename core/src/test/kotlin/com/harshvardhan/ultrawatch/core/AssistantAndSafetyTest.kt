package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAndSafetyTest {

    @Test fun voiceCommands() {
        assertEquals(Command.Call("mom"), VoiceCommandParser.parse("Call mom"))
        assertEquals(Command.Call("mummy"), VoiceCommandParser.parse("mummy ko call karo"))
        assertEquals(Command.Message("rahul", "i'm running late"), VoiceCommandParser.parse("Message Rahul saying I'm running late"))
        assertEquals(Command.Message("priya", null), VoiceCommandParser.parse("send a text to Priya"))
        assertEquals(Command.Message("papa", "i reached"), VoiceCommandParser.parse("tell papa that I reached"))
        assertEquals(Command.PlayMusic(null), VoiceCommandParser.parse("Play music"))
        assertEquals(Command.PlayMusic("arijit singh"), VoiceCommandParser.parse("play arijit singh"))
        assertEquals(Command.PlayMusic(null), VoiceCommandParser.parse("gaana bajao"))
        assertEquals(Command.PlayVideo("cats"), VoiceCommandParser.parse("play video of cats"))
        assertEquals(Command.PauseMusic, VoiceCommandParser.parse("pause"))
        assertEquals(Command.NextTrack, VoiceCommandParser.parse("Next song."))
        assertEquals(Command.StartWorkout("Run"), VoiceCommandParser.parse("start a run"))
        assertEquals(Command.StartWorkout("Cycle"), VoiceCommandParser.parse("start cycling workout"))
        assertEquals(Command.StopWorkout, VoiceCommandParser.parse("stop workout"))
        assertEquals(Command.ShowHeartRate, VoiceCommandParser.parse("what's my heart rate?"))
        assertEquals(Command.ShowQuote("NIFTY 50"), VoiceCommandParser.parse("How is Nifty doing today?"))
        assertEquals(Command.ShowQuote("BANKNIFTY"), VoiceCommandParser.parse("bank nifty"))
        assertEquals(Command.SetTimer(300), VoiceCommandParser.parse("set a timer for 5 minutes"))
        assertEquals(Command.ReadMessages, VoiceCommandParser.parse("read my messages"))
        assertEquals(Command.Breathe, VoiceCommandParser.parse("start breathing"))
        assertTrue(VoiceCommandParser.parse("what is the meaning of life") is Command.Unknown)
        assertTrue(VoiceCommandParser.parse("   ") is Command.Unknown)
    }

    private val contacts = listOf(
        Contact("1", "Mummy", "+91 90000 00001", starred = true),
        Contact("2", "Rahul Sharma", "+91 90000 00002"),
        Contact("3", "Rahul Verma", "+91 90000 00003", starred = true),
        Contact("4", "Priyanka Das", "+358 40 000 0004"),
        Contact("5", "Papa", "+91 90000 00005"),
    )

    @Test fun contactMatching() {
        assertEquals("1", ContactMatcher.bestMatch("mom", contacts)!!.id)        // relation alias
        assertEquals("5", ContactMatcher.bestMatch("dad", contacts)!!.id)
        assertEquals("3", ContactMatcher.bestMatch("rahul", contacts)!!.id)      // starred wins tie
        assertEquals("2", ContactMatcher.bestMatch("rahul sharma", contacts)!!.id)
        assertEquals("4", ContactMatcher.bestMatch("priyanka", contacts)!!.id)
        assertEquals("4", ContactMatcher.bestMatch("priyanca", contacts)!!.id)   // mis-hearing
        assertEquals("4", ContactMatcher.bestMatch("pri", contacts)!!.id)        // prefix
        assertNull(ContactMatcher.bestMatch("zebra", contacts))
        assertEquals(3, ContactMatcher.levenshtein("kitten", "sitting"))
    }

    @Test fun quickReplies() {
        val q = ReplySuggester.suggest("Are you coming to lunch?")
        assertTrue(q.contains("Sounds good"))
        assertTrue(q.size <= 4)
        assertEquals("Namaste 🙏", ReplySuggester.suggest("Namaste ji")[0])
        assertTrue(ReplySuggester.suggest("Where are you??").contains("On my way"))
        assertEquals(listOf("OK 👍", "Talk later"), ReplySuggester.suggest("random statement"))
        // "phone" inside another word must not match
        assertFalse(ReplySuggester.suggest("my headphones arrived").contains("Will call you shortly"))
    }

    private fun feed(d: FallDetector, startMs: Long, durMs: Long, g: Double, stepMs: Long = 20): Pair<Long, Boolean> {
        var t = startMs
        var fired = false
        val z = (g * 9.80665).toFloat()
        while (t < startMs + durMs) { if (d.onSample(t, 0f, 0f, z)) fired = true; t += stepMs }
        return t to fired
    }

    @Test fun fallDetectedOnFreeFallImpactStill() {
        val d = FallDetector()
        var (t, f) = feed(d, 0, 500, 1.0); assertFalse(f)
        feed(d, t, 200, 0.2).also { t = it.first; assertFalse(it.second) }   // free fall 200 ms
        feed(d, t, 40, 3.5).also { t = it.first; assertFalse(it.second) }    // impact
        feed(d, t, 2_500, 1.0).also { assertTrue(it.second) }                 // lying still
        assertEquals(FallDetector.State.IDLE, d.state)
    }

    @Test fun noFallForArmSwingOrShortDrop() {
        val d = FallDetector()
        var t = 0L
        // brief 40 ms low-g blip (wrist flick) then a spike: no fall
        feed(d, t, 40, 0.2).also { t = it.first }
        feed(d, t, 40, 3.5).also { t = it.first }
        assertFalse(feed(d, t, 3_000, 1.0).second)
        // real free fall + impact but then walking around (not still): no fall
        feed(d, t + 3_000, 200, 0.2).also { t = it.first }
        feed(d, t, 40, 3.5).also { t = it.first }
        var fired = false
        var tt = t
        repeat(300) { i -> if (d.onSample(tt, 0f, 0f, ((if (i % 2 == 0) 1.6 else 0.6) * 9.80665).toFloat())) fired = true; tt += 20 }
        assertFalse(fired)
    }

    @Test fun countdownAndSafetyCheck() {
        val c = Countdown(30_000)
        c.start(1_000)
        assertEquals(20_000, c.remainingMs(11_000))
        assertFalse(c.isExpired(30_000))
        assertTrue(c.isExpired(31_000))
        c.cancel(); assertFalse(c.isExpired(40_000))

        val s = SafetyCheck(0, 600_000, 60_000)
        assertEquals(SafetyCheck.Status.ACTIVE, s.status(10_000))
        assertEquals(SafetyCheck.Status.GRACE, s.status(630_000))
        assertEquals(SafetyCheck.Status.ALERT, s.status(661_000))
        s.checkIn()
        assertEquals(SafetyCheck.Status.CHECKED_IN, s.status(700_000))
        assertEquals(0, SafetyCheck(0, 1_000).remainingMs(5_000))
    }

    @Test fun sosMessage() {
        val m = SosMessage.build("Prakash", "Fall detected", 60.29245, 25.04056, 12f)
        assertEquals("SOS from Prakash: Fall detected. Location: https://maps.google.com/?q=60.292450,25.040560 (±12 m)", m)
        assertTrue(m.length <= 160)
        assertEquals("SOS from UltraWatch user: SOS button. Location unavailable.", SosMessage.build("", "SOS button", null, null))
    }

    @Test fun batteryEstimates() {
        // charging 50 -> 60 in 10 min => 1 %/min => 40 min to full
        assertEquals(40, BatteryMath.minutesRemaining(50, 0, 60, 600_000, true))
        // discharging 80 -> 78 in 20 min => 0.1 %/min => 780 min
        assertEquals(780, BatteryMath.minutesRemaining(80, 0, 78, 1_200_000, false))
        assertNull(BatteryMath.minutesRemaining(50, 0, 50, 600_000, false))
        assertNull(BatteryMath.minutesRemaining(50, 0, 51, 30_000, true))
        assertEquals("13 h 0 min", BatteryMath.formatMinutes(780))
        assertEquals("40 min", BatteryMath.formatMinutes(40))
    }

    @Test fun chargeReminderHysteresis() {
        val r = BatteryMath.ChargeReminder(80)
        assertFalse(r.onLevel(79, true))
        assertTrue(r.onLevel(80, true))
        assertFalse(r.onLevel(81, true))      // only once
        assertFalse(r.onLevel(78, false))     // small drop doesn't re-arm
        assertFalse(r.onLevel(80, true))
        assertFalse(r.onLevel(75, false))     // drops to 75 -> re-arms
        assertTrue(r.onLevel(80, true))
        assertFalse(BatteryMath.ChargeReminder().onLevel(90, false)) // not charging
    }

    @Test fun translatorLanguages() {
        assertEquals("Hindi", TranslatorLanguages.name("hi"))
        assertEquals("fi-FI", TranslatorLanguages.speechTag("fi"))
        assertEquals("xx", TranslatorLanguages.speechTag("xx"))
        assertEquals(TranslatorLanguages.ALL.size, TranslatorLanguages.ALL.map { it.first }.toSet().size)
    }
}
