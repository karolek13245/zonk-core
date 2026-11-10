package com.example.aiapp

// Small pure helpers for voice typing. No Android classes in here, so they can be tested on a plain computer.

/** Puts the spoken words after what was already typed, with exactly one space between the two. */
fun mergeSpoken(base: String, spoken: String): String {
    val said = spoken.trim()
    if (said.isEmpty()) return base
    if (base.isEmpty() || base.last().isWhitespace()) return base + said
    return "$base $said"
}

/**
 * What to tell the person when the phone's speech recognizer stops with an error, or null when there is nothing worth
 * saying (they simply stopped talking, or the recognizer was stopped on purpose).
 * The numbers are the SpeechRecognizer.ERROR_* codes.
 */
fun voiceErrorMessage(code: Int): String? = when (code) {
    5, 6, 7 -> null // client (we stopped it), speech timeout, no match
    1, 2, 4, 11 -> "Voice typing needs the internet on this phone right now." // network, network timeout, server
    3 -> "Couldn't use the microphone."
    8, 10 -> "Voice typing is busy. Try again in a moment."
    9 -> "Allow the microphone to use voice typing."
    12, 13 -> "This phone has no voice typing for its language yet."
    else -> "Voice typing stopped. Try again."
}
