package com.chun.pibridge

import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import org.json.JSONObject

class UntrustedHost(val fingerprint: String) : Exception("ホスト鍵の確認が必要: $fingerprint")

class HostKeyChanged : Exception("ホスト鍵が変更されています。接続を拒否しました")

class BridgeClient(private val trace: (String) -> Unit = {}) {
    init {
        // Android's truncated provider also uses the name BC, but lacks X25519.
        synchronized(BridgeClient::class.java) {
            if (
                java.security.Security.getProvider("BC")
                    !is org.bouncycastle.jce.provider.BouncyCastleProvider
            ) {
                java.security.Security.removeProvider("BC")
                java.security.Security.insertProviderAt(
                    org.bouncycastle.jce.provider.BouncyCastleProvider(),
                    1,
                )
            }
        }
    }

    private var ssh: SSHClient? = null
    private var channel: net.schmizz.sshj.connection.channel.direct.Session.Command? = null
    private var writer: OutputStreamWriter? = null
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    @Volatile var events: (JSONObject) -> Unit = {}

    suspend fun connect(profile: Profile, pem: String, connectTimeoutMs: Int = 10000) =
        withContext(Dispatchers.IO) {
            close()
            val client = SSHClient()
            ssh = client
            client.connectTimeout = connectTimeoutMs
            client.timeout = 15000
            var offered = ""
            var changed = false
            client.addHostKeyVerifier(
                object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                    override fun verify(
                        hostname: String,
                        port: Int,
                        key: java.security.PublicKey,
                    ): Boolean {
                        val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
                        offered =
                            "SHA256:" +
                                Base64.getEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                        MessageDigest.getInstance("SHA-256").digest(encoded)
                                    )
                        changed = profile.pin.isNotEmpty() && profile.pin != offered
                        return profile.pin.isNotEmpty() && profile.pin == offered
                    }

                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                        emptyList()
                }
            )
            try {
                client.connect(profile.host, profile.port)
                trace("ssh_handshake")
            } catch (e: Exception) {
                client.close()
                if (changed) throw HostKeyChanged()
                if (offered.isNotEmpty() && profile.pin.isEmpty()) throw UntrustedHost(offered)
                throw e
            }
            // SSHJ's PKCS8 parser does not support Ed25519 OID 1.3.101.112.
            // Decode our own seed and hand SSHJ an explicit EdDSA key pair instead.
            val info =
                org.bouncycastle.openssl.PEMParser(java.io.StringReader(pem)).use {
                    it.readObject() as org.bouncycastle.asn1.pkcs.PrivateKeyInfo
                }
            val raw =
                org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(info)
                    as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
            val spec =
                net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(
                    raw.encoded,
                    net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName("Ed25519"),
                )
            val privateKey = net.i2p.crypto.eddsa.EdDSAPrivateKey(spec)
            val publicKey =
                net.i2p.crypto.eddsa.EdDSAPublicKey(
                    net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(spec.a, spec.params)
                )
            client.authPublickey(
                profile.user,
                net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper(publicKey, privateKey),
            )
            trace("ssh_auth")
            client.connection.keepAlive.keepAliveInterval = 30
            val command = client.startSession().exec("pibridge relay")
            trace("ssh_exec")
            channel = command
            writer = OutputStreamWriter(command.outputStream, Charsets.UTF_8)
            // Drain stderr separately so an error cannot block stdout. Never copy remote
            // diagnostics into chat.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    command.errorStream.copyTo(
                        object : java.io.OutputStream() {
                            override fun write(b: Int) {}

                            override fun write(b: ByteArray, off: Int, len: Int) {}
                        }
                    )
                }
            }
            command
        }

    suspend fun readLoop(): Unit =
        withContext(Dispatchers.IO) {
            val stream = channel?.inputStream ?: error("not_connected")
            try {
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val buffer = StringBuilder()
                val chunk = CharArray(8192)
                while (currentCoroutineContext().isActive) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = chunk[i]
                        if (c == '\n') {
                            if (buffer.isNotBlank()) {
                                val p = JSONObject(buffer.toString())
                                val id = p.optString("id")
                                if (id.isNotEmpty()) pending.remove(id)?.complete(p) else events(p)
                            }
                            buffer.setLength(0)
                        } else {
                            buffer.append(c)
                            require(buffer.length <= 24 * 1024 * 1024) { "frame_too_large" }
                        }
                    }
                }
                error("SSH connection closed")
            } finally {
                pending.values.forEach {
                    it.completeExceptionally(IllegalStateException("disconnected"))
                }
                pending.clear()
            }
        }

    suspend fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 20000,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val id = ids.incrementAndGet().toString()
            val deferred = CompletableDeferred<JSONObject>()
            pending[id] = deferred
            try {
                synchronized(this@BridgeClient) {
                    val w = writer ?: error("not_connected")
                    w.write(
                        JSONObject()
                            .put("id", id)
                            .put("method", method)
                            .put("params", params)
                            .toString() + "\n"
                    )
                    w.flush()
                }
                val p =
                    try {
                        withTimeout(timeoutMs) { deferred.await() }
                    } catch (e: TimeoutCancellationException) {
                        throw java.io.IOException("RPC応答がありません: $method", e)
                    }
                p.optJSONObject("error")?.let { error(it.optString("message")) }
                p.optJSONObject("result") ?: JSONObject()
            } finally {
                pending.remove(id)
            }
        }

    fun close() {
        runCatching { channel?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { ssh?.close() }
        channel = null
        ssh = null
        writer = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
