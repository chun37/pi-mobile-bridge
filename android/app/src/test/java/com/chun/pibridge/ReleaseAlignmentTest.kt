package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseAlignmentTest {
    @Test fun matchingProductVersionsIgnoreExternalVersionSchemes() {
        assertEquals("一致", releaseAlignment("0.4.0", "0.4.0", "0.4.0", true))
        val report = debugReport(debugSections("0.4.0 (4)", "17", true, "pc", null, null,
            JSONObject("""{"relay":{"version":"0.4.0"},"bridge":{"bridgeVersion":"0.4.0","runtime":{"piVersion":"1.6.0","nodeVersion":"v26"},"build":{"revision":"extension-commit","modified":true}},"herdr":{"version":"0.9.5"}}"""),
            "0.4.0", "app-commit", "clean"))
        assertTrue(report.contains("稼働版の一致: 一致"))
        assertTrue(report.contains("ビルドコミット: app-commit"))
        assertTrue(report.contains("拡張ロード時コミット: extension-commit"))
        assertTrue(report.contains("拡張ロード時の未コミット変更: true"))
    }
    @Test fun oldRelayOrOldExtensionIsVisible() {
        assertTrue(releaseAlignment("0.4.0", "0.2.1", "0.4.0", true).contains("不一致"))
        assertTrue(releaseAlignment("0.4.0", "0.4.0", "0.3.2", true).contains("不一致"))
        assertTrue(releaseAlignment("0.4.0", "0.2.1", null, true).contains("不一致"))
    }
    @Test fun unknownAndDisconnectedAreNotCalledAligned() {
        for (value in listOf(null, JSONObject.NULL, JSONObject(), 4, "", "0.4.0x"))
            assertTrue(releaseAlignment("0.4.0", "0.4.0", value, true).contains("未確認"))
        assertEquals("未確認（未接続）", releaseAlignment("0.4.0", "0.4.0", "0.4.0", false))
    }
    @Test fun appBuildInfoAbsentRemainsUnknown() {
        val report = debugReport(debugSections("0.4.0 (4)", "17", true, "pc", null, null, null,
            "0.4.0", "unknown", "unknown"))
        assertTrue(report.contains("ビルドコミット: 未取得"))
    }
}
