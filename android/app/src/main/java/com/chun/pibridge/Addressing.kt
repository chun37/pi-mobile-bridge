package com.chun.pibridge

fun isTailscaleAddress(host: String): Boolean {
    val value = host.lowercase()
    val second = value.split('.').getOrNull(1)?.toIntOrNull()
    return (value.startsWith("100.") && second in 64..127) ||
        value.endsWith(".ts.net") ||
        value.startsWith("fd7a:115c:a1e0:")
}

fun addressPriority(host: String): Int {
    if (isTailscaleAddress(host)) return 2
    val value = host.lowercase()
    val second = value.split('.').getOrNull(1)?.toIntOrNull()
    return if (
        value.startsWith("192.168.") ||
            value.startsWith("10.") ||
            (value.startsWith("172.") && second in 16..31) ||
            (':' in value && (value.startsWith("fd") || value.startsWith("fc")))
    )
        0
    else 1
}

fun addressLabel(host: String): String =
    when (addressPriority(host)) {
        0 -> "LAN"
        2 -> "Tailscale"
        else -> "SSH"
    }
