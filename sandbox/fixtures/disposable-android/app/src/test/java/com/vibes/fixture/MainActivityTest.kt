package com.vibes.fixture

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test fun greetingIsStable() {
        assertEquals("Vibes worker fixture", greetingText())
    }
}
