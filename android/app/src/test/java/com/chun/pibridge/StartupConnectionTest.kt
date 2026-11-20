package com.chun.pibridge

import org.junit.Assert.*
import org.junit.Test

class StartupConnectionTest {
    private val first = Profile(id = "one", name = "first", host = "192.0.2.1", user = "test")
    private val second = first.copy(id = "two", name = "second", host = "192.0.2.2")

    @Test fun prefersLastChosenProfile() {
        assertEquals(second, startupProfile(listOf(first, second), "two"))
    }
    @Test fun missingOrRemovedProfileFallsBackToFirstSaved() {
        assertEquals(first, startupProfile(listOf(first, second), "missing"))
        assertEquals(first, startupProfile(listOf(first, second), ""))
    }
    @Test fun emptyConfigurationDoesNotInventAConnection() {
        assertNull(startupProfile(emptyList(), "missing"))
    }
}
