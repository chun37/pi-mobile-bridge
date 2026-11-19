package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    private val pc =
        Profile(
            id = "one-pc",
            name = "PC",
            host = "100.64.0.10",
            user = "chun",
            pin = "SHA256:trusted",
            addresses = listOf("192.168.1.100", "100.64.0.10"),
        )

    @Test
    fun oldSingleAddressMigratesWithoutLosingIdentity() {
        val old =
            JSONObject()
                .put("id", "old")
                .put("name", "PC")
                .put("host", "100.64.0.10")
                .put("user", "chun")
                .put("pin", "SHA256:trusted")
        val p = Profile.from(old)
        assertEquals(listOf("100.64.0.10"), p.hosts)
        assertEquals("old", p.id)
        assertEquals("SHA256:trusted", p.pin)
    }

    @Test
    fun addressSwitchPreservesMachineIdentityAndPin() {
        val lan = pc.at("192.168.1.100")
        assertEquals("192.168.1.100", lan.host)
        assertEquals(pc.id, lan.id)
        assertEquals(pc.pin, lan.pin)
        assertEquals(pc.user, lan.user)
        assertEquals(pc.port, lan.port)
        assertEquals(pc.hosts, lan.hosts)
        assertEquals(pc.host, lan.at(pc.host).host)
    }

    @Test
    fun roundTripRetainsAllAddressesAndSelection() {
        val p = pc.at("192.168.1.100")
        assertEquals(p, Profile.from(p.json()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unregisteredAddressCannotBeSelected() {
        pc.at("other-server.example")
    }

    @Test
    fun duplicateAndBlankAddressesAreNormalized() {
        val p =
            pc.copy(addresses = listOf(" 192.168.1.100 ", "100.64.0.10", "", "192.168.1.100"))
        assertEquals(listOf("192.168.1.100", "100.64.0.10"), p.hosts)
    }
}
