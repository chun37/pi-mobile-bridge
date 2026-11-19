package com.chun.pibridge

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    private val pc =
        Profile(
            id = "pc",
            name = "PC",
            host = "100.64.0.10",
            user = "u",
            pin = "key",
            addresses = listOf("100.64.0.10", "192.168.1.100"),
        )

    @Test
    fun automaticModeAlwaysTriesLanBeforeTailscale() {
        assertEquals(listOf("192.168.1.100", "100.64.0.10"), pc.connectionHosts)
    }

    @Test
    fun explicitRouteDoesNotSilentlyFailOver() {
        assertEquals(listOf("100.64.0.10"), pc.at("100.64.0.10").connectionHosts)
        assertFalse(pc.at("100.64.0.10").autoSelect)
    }

    @Test
    fun automaticSelectionCanBeRestored() {
        assertEquals(pc.connectionHosts, pc.at(pc.host).automatic().connectionHosts)
    }

    @Test
    fun automaticAndManualModesSurviveSerialization() {
        for (p in listOf(pc, pc.at(pc.host))) assertEquals(
            p.autoSelect,
            Profile.from(p.json()).autoSelect,
        )
    }

    @Test
    fun oldSettingsDefaultToLanPriority() {
        val json = pc.json()
        json.remove("autoSelect")
        assertTrue(Profile.from(json).autoSelect)
    }

    @Test
    fun rpcTimeoutIsAnIoFailureNotCancellation() = runBlocking {
        val c = BridgeClient()
        BridgeClient::class
            .java
            .getDeclaredField("writer")
            .apply { isAccessible = true }
            .set(c, OutputStreamWriter(ByteArrayOutputStream()))
        try {
            c.request("test", JSONObject(), timeoutMs = 10)
            fail("Missing timeout")
        } catch (e: java.io.IOException) {
            assertTrue(
                generateSequence<Throwable>(e) { it.cause }
                    .any { it is TimeoutCancellationException }
            )
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            c.close()
        }
    }
}
