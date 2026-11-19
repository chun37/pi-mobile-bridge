package com.chun.pibridge

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    @Test
    fun identityIsGeneratedAndEncrypted() {
        val id = Identity(context)
        val pub = id.publicKey()
        assertTrue(pub.startsWith("ssh-ed25519 "))
        assertEquals(pub, id.publicKey())
        val encrypted = java.io.File(context.noBackupFilesDir, "identity.aesgcm").readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("PRIVATE KEY"))
        assertTrue(encrypted.size > 100)
    }

    @Test
    fun setupProfile() {
        val host = args.getString("host") ?: error("Pass -e host <SSH host>")
        val user = args.getString("user") ?: error("Pass -e user <SSH user>")
        val pin = args.getString("pin") ?: ""
        val profile =
            Profile(id = "verification-pc", name = "検証PC", host = host, user = user, pin = pin)
        Profiles(context).save(profile)
        Identity(context).publicKey()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Test
    fun forcedCommandAndNoPty() = runBlocking {
        val profile = Profiles(context).all().first { it.id == "verification-pc" }
        val c = BridgeClient()
        c.connect(profile, Identity(context).privatePem())
        try {
            val field =
                BridgeClient::class.java.getDeclaredField("ssh").apply { isAccessible = true }
            val ssh = field.get(c) as net.schmizz.sshj.SSHClient
            val session = ssh.startSession()
            val command = session.exec("printf PIBRIDGE_SHELL_MUST_NOT_RUN")
            command.outputStream.write(
                (JSONObject().put("id", "security").put("method", "ping").toString() + "\n")
                    .toByteArray()
            )
            command.outputStream.flush()
            val lines = command.inputStream.bufferedReader()
            var reply: JSONObject? = null
            withContext(Dispatchers.IO) {
                repeat(100) {
                    if (reply == null) {
                        val line = lines.readLine() ?: error("Relay exited")
                        assertFalse(line.contains("PIBRIDGE_SHELL_MUST_NOT_RUN"))
                        val p = JSONObject(line)
                        if (p.optString("id") == "security") reply = p
                    }
                }
            }
            assertTrue(reply!!.getJSONObject("result").getBoolean("pong"))
            command.close()
            session.close()
            val pty = ssh.startSession()
            try {
                pty.allocateDefaultPTY()
                fail("PTY unexpectedly allowed")
            } catch (_: net.schmizz.sshj.connection.ConnectionException) {} finally {
                pty.close()
            }
        } finally {
            c.close()
        }
    }

    @Test
    fun bothRegisteredAddressesUseSameServer() = runBlocking {
        val profile = Profiles(context).all().first { it.id == "verification-pc" }
        val lan = args.getString("lanHost") ?: error("Pass -e lanHost <LAN address>")
        val vpn = args.getString("tailscaleHost") ?: error("Pass -e tailscaleHost <VPN address>")
        for (host in listOf(lan, vpn, lan)) {
            val c = BridgeClient()
            c.connect(profile.at(host), Identity(context).privatePem())
            val reader = launch(Dispatchers.IO) { runCatching { c.readLoop() } }
            try {
                assertEquals(
                    1,
                    c.request("hello", JSONObject().put("protocol", 1)).getInt("protocol"),
                )
                assertTrue(c.request("ping").getBoolean("pong"))
                println("DIRECT_ROUTE_OK $host")
            } finally {
                c.close()
                reader.cancelAndJoin()
            }
        }
    }

    @Test
    fun hostKeyPinning() = runBlocking {
        val profile = Profiles(context).all().first { it.id == "verification-pc" }
        val pem = Identity(context).privatePem()
        val unknown = BridgeClient()
        try {
            unknown.connect(profile.copy(pin = ""), pem)
            fail("Unpinned host accepted")
        } catch (e: UntrustedHost) {
            assertEquals(profile.pin, e.fingerprint)
        } finally {
            unknown.close()
        }
        val wrong = BridgeClient()
        try {
            wrong.connect(profile.copy(pin = "SHA256:wrong"), pem)
            fail("Changed host key accepted")
        } catch (e: HostKeyChanged) {} finally {
            wrong.close()
        }
    }

    @Test
    fun terminalOnlyFallback() = runBlocking {
        val pane =
            args.getString("fallbackPane") ?: error("Pass fallbackPane for an owned test shell")
        val profile = Profiles(context).all().first { it.id == "verification-pc" }
        val c = BridgeClient()
        c.connect(profile, Identity(context).privatePem())
        val reader = launch(Dispatchers.IO) { runCatching { c.readLoop() } }
        try {
            val entry =
                c.request("sessions.list").getJSONArray("sessions").objects().first {
                    it.optString("paneId") == pane
                }
            assertFalse(entry.getBoolean("bridge"))
            c.request(
                "terminal.input",
                JSONObject()
                    .put("paneId", pane)
                    .put("text", "printf 'PIBRIDGE_FALLBACK_VERIFIED\\n'")
                    .put("keys", org.json.JSONArray(listOf("enter"))),
            )
            withTimeout(10000) {
                while (
                    !c.request(
                            "terminal.read",
                            JSONObject().put("paneId", pane).put("source", "visible"),
                        )
                        .toString()
                        .contains("PIBRIDGE_FALLBACK_VERIFIED")
                ) delay(100)
            }
        } finally {
            c.close()
            reader.cancelAndJoin()
        }
    }

    @Test
    fun sshRelayAndConversation() = runBlocking {
        val profile = Profiles(context).all().first { it.id == "verification-pc" }
        val c = BridgeClient()
        c.connect(profile, Identity(context).privatePem())
        val events = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        c.events = { events.add(it) }
        val reader = launch(Dispatchers.IO) { runCatching { c.readLoop() } }
        try {
            assertEquals(1, c.request("hello", JSONObject().put("protocol", 1)).getInt("protocol"))
            val sessions = c.request("sessions.list").getJSONArray("sessions").objects()
            assertTrue(sessions.isNotEmpty())
            val pane = args.getString("pane") ?: error("Pass an owned fixture pane")
            val session = sessions.first { it.optString("sessionKey") == pane }
            assertTrue(session.getBoolean("bridge"))
            val h = c.request("session.open", JSONObject().put("sessionKey", pane))
            assertTrue(h.has("entries"))
            // Terminal-origin input exercises the PC's actual TUI path, not sendUserMessage.
            c.request(
                "terminal.input",
                JSONObject()
                    .put("paneId", pane)
                    .put("text", "PC_DEVICE_TEST")
                    .put("keys", org.json.JSONArray(listOf("enter"))),
            )
            waitFor(15000) { events.any { it.optString("kind") == "agent_settled" } }
            val pc =
                c.request("session.open", JSONObject().put("sessionKey", pane))
                    .getJSONArray("entries")
                    .objects()
            assertTrue(
                pc.any {
                    it.optJSONObject("message")?.textContent()?.contains("PC_DEVICE_TEST") ==
                        true && it.optString("origin") == "pc"
                }
            )
            events.clear()
            c.request(
                "session.send",
                JSONObject()
                    .put("sessionKey", pane)
                    .put("text", "mobile-task-file DEVICE_TEST")
                    .put("clientMsgId", "device-task-" + System.currentTimeMillis()),
            )
            waitFor(20000) { events.any { it.optString("kind") == "agent_settled" } }
            assertTrue(events.any { it.optString("kind") == "message_update" })
            assertTrue(events.any { it.optString("kind") == "tool_execution_end" })
            val history = c.request("session.open", JSONObject().put("sessionKey", pane))
            val entries = history.getJSONArray("entries").objects()
            assertTrue(
                entries.any {
                    it.optString("origin") == "mobile" &&
                        it.optJSONObject("message")?.textContent()?.contains("DEVICE_TEST") == true
                }
            )
            assertTrue(
                entries.any { it.optJSONObject("message")?.optString("role") == "assistant" }
            )
            val delta =
                c.request(
                    "session.open",
                    JSONObject().put("sessionKey", pane).put("since", history.getString("leafId")),
                )
            assertEquals(0, delta.getJSONArray("entries").length())
            val reset =
                c.request(
                    "session.open",
                    JSONObject().put("sessionKey", pane).put("since", "missing-entry"),
                )
            assertTrue(reset.getBoolean("reset"))
            val terminal =
                c.request(
                    "terminal.read",
                    JSONObject().put("paneId", pane).put("source", "visible"),
                )
            assertTrue(terminal.toString().contains("DEVICE_TEST"))
            try {
                c.request("server.stop")
                fail("Arbitrary API allowed")
            } catch (_: IllegalStateException) {}
            java.io.File(context.filesDir, "device-test-history.json").writeText(history.toString())
        } finally {
            c.close()
            reader.cancelAndJoin()
        }
    }

    private suspend fun waitFor(timeout: Long, predicate: () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(100) }
    }
}
