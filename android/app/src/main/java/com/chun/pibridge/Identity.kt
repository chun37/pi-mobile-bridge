package com.chun.pibridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.StringWriter
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter

class Identity(private val context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "identity.aesgcm")

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pibridge.wrap", null) as? SecretKey)?.let {
            return it
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                    "pibridge.wrap",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun privatePem(): String {
        val key = wrappingKey()
        if (file.exists()) {
            val bytes = file.readBytes()
            require(bytes.size > 28) { "Invalid encrypted identity" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val out = StringWriter()
        PemWriter(out).use {
            it.writeObject(
                PemObject("PRIVATE KEY", PrivateKeyInfoFactory.createPrivateKeyInfo(priv).encoded)
            )
        }
        val pem = out.toString()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val temp = java.io.File(file.parentFile, "identity.tmp")
        temp.writeBytes(c.iv + c.doFinal(pem.toByteArray()))
        check(temp.renameTo(file))
        return pem
    }

    @Synchronized
    fun publicKey(): String {
        val pem = privatePem()
        val info =
            org.bouncycastle.openssl.PEMParser(java.io.StringReader(pem)).use {
                it.readObject() as org.bouncycastle.asn1.pkcs.PrivateKeyInfo
            }
        val priv =
            org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(info)
                as Ed25519PrivateKeyParameters
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { d ->
            val type = "ssh-ed25519".toByteArray()
            val pub = priv.generatePublicKey().encoded
            d.writeInt(type.size)
            d.write(type)
            d.writeInt(pub.size)
            d.write(pub)
        }
        java.io
            .File(context.noBackupFilesDir, "identity.pub")
            .writeText(
                "ssh-ed25519 " +
                    Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP) +
                    " pibridge-android"
            )
        return java.io.File(context.noBackupFilesDir, "identity.pub").readText()
    }
}
