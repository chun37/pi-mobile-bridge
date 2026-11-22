package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DebugInfoTest {
    private fun sections(hello: JSONObject? = null, snapshot: JSONObject? = null, connected: Boolean = true) =
        debugSections("0.3.0 (3, debug)", "17 / API 37", connected, "192.0.2.1", hello,
            JSONObject("""{"sessionKey":"p1","sessionId":"s1","instanceId":"i1","model":"cached"}"""), snapshot)
    @Test fun formatsRunningVersionsRatherThanInstalledOrCachedValues() {
        val report = debugReport(sections(JSONObject("""{"relayVersion":"old","herdrVersion":"old"}"""),
            JSONObject("""{"relay":{"version":"new","pid":42,"modified":false,"executableReplaced":true},"herdr":{"version":"server","protocol":22},"bridge":{"bridgeVersion":"0.3.2","runtime":{"piVersion":"1.6.0","nodeVersion":"v25"}},"session":{"model":"running","thinkingLevel":"off"},"collectedAt":"now"}""")))
        for (text in listOf("relay: new", "Herdr: server", "Pi: 1.6.0", "Node.js: v25", "thinking: off", "モデル: running", "PID: 42"))
            assertTrue(report, report.contains(text))
        assertFalse(report.contains("relay: old"))
        assertTrue(report.contains("未コミット変更を含むビルド: false"))
    }
    @Test fun legacyAndOfflineUseConnectionTimeValuesAndExplicitUnknown() {
        val report = debugReport(sections(JSONObject("""{"relayVersion":"0.1.0","herdrVersion":"0.9.5"}"""), connected = false))
        assertTrue(report.contains("未接続（接続時の情報）"))
        assertTrue(report.contains("relay: 0.1.0"))
        assertTrue(report.contains("Pi: 未取得"))
        assertTrue(report.contains("Node.js: 未取得"))
    }
    @Test fun malformedValuesNeverBecomeFakeVersionStrings() {
        val report = debugReport(sections(snapshot = JSONObject("""{"bridge":{"runtime":{"piVersion":null,"nodeVersion":{},"platform":""}},"relay":{"version":[],"revision":" "}}""")))
        assertTrue(report.contains("Pi: 未取得"))
        assertTrue(report.contains("Node.js: 未取得"))
        assertTrue(report.contains("relay: 未取得"))
        assertTrue(report.contains("ビルドコミット: 未取得"))
    }
    @Test fun whitelistExcludesCredentialsConversationAndPathsFromCopyText() {
        val info = JSONObject("""{"protocol":1,"relayVersion":"0.2.0","capabilities":["sessionDebug",42],"token":"SECRET","sessionFile":"SECRET","lastResponse":"SECRET","relayRuntime":{"token":"SECRET"}}""")
        val safe = debugConnectionInfo(info)
        assertTrue(supportsSessionDebug(safe))
        assertFalse(safe.toString().contains("SECRET"))
        val report = debugReport(sections(safe, JSONObject("""{"token":"SECRET","bridge":{"runtime":{"token":"SECRET"}},"session":{"lastResponse":"SECRET"}}""")))
        assertFalse(report.contains("SECRET"))
    }
    @Test fun unsupportedCapabilityIsNotInferredFromVersion() {
        assertFalse(supportsSessionDebug(null))
        assertFalse(supportsSessionDebug(JSONObject("""{"relayVersion":"99.0.0"}""")))
        assertTrue(supportsSessionDebug(JSONObject("""{"capabilities":["sessionDebug"]}""")))
    }
}
