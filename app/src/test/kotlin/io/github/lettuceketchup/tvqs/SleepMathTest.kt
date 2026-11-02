package io.github.lettuceketchup.tvqs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepMathTest {
    @Test fun longerFromOff() = assertEquals(10, nextLonger(0))
    @Test fun longerFromRunning() = assertEquals(30, nextLonger(23))
    @Test fun longerAtTop() = assertNull(nextLonger(120))
    @Test fun shorterFromRunning() = assertEquals(15, nextShorter(23))
    @Test fun shorterBelowSmallestIsOff() = assertEquals(0, nextShorter(8))
    @Test fun shorterWhenOff() = assertNull(nextShorter(0))
    @Test fun remindersForAnHour() = assertEquals(listOf(30, 15, 5), remindersAhead(60, 60 * 60_000L))
    @Test fun remindersAfterExtending() = assertEquals(listOf(15, 5), remindersAhead(60, 16 * 60_000L))
    @Test fun noRemindersForShortTimer() = assertEquals(emptyList<Int>(), remindersAhead(5, 5 * 60_000L))
    @Test fun extensionShortTimer() = assertEquals(10, extensionMin(10))
    @Test fun extensionLongTimer() = assertEquals(15, extensionMin(60))
    @Test fun roundsUp() = assertEquals(2, minutesLeft(61_000))
}
