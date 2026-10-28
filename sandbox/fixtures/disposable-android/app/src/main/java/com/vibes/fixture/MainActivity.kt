package com.vibes.fixture

import android.app.Activity

/** Small disposable target used only to validate the isolated worker image. */
class MainActivity : Activity() {
    fun greeting(): String = greetingText()
}

internal fun greetingText(): String = "Vibes worker fixture"
