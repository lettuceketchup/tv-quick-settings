package io.github.lettuceketchup.tvqs

// Sleep timer maths. 0 minutes = off. Times show as "$min min".
val SLEEP_STEPS = listOf(10, 15, 30, 60, 90, 120)

fun minutesLeft(msLeft: Long): Int = ((msLeft + 59_999) / 60_000).toInt()

fun nextLonger(leftMin: Int): Int? = SLEEP_STEPS.firstOrNull { it > leftMin }

fun nextShorter(leftMin: Int): Int? =
    if (leftMin <= 0) null else SLEEP_STEPS.lastOrNull { it < leftMin } ?: 0

// Milestones still to come; Task 8 shows one pill at each.
fun remindersAhead(startedMin: Int, msLeft: Long): List<Int> =
    listOf(60, 30, 15, 5).filter { it < startedMin && it * 60_000L < msLeft }

fun extensionMin(startedMin: Int): Int = minOf(15, startedMin)
