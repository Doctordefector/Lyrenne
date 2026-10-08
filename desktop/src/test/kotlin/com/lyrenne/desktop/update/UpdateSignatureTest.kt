package com.lyrenne.desktop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** The updater accepts exactly what signPortableZip produces, and nothing tampered. */
class UpdateSignatureTest {

    @Test
    fun `signature over the zip digest verifies, and a changed byte does not`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(pair.public.encoded)
        val zip = File.createTempFile("update", ".zip").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        try {
            val sig = Signature.getInstance("Ed25519").run {
                initSign(pair.private); update(AutoUpdater.sha256(zip)); sign()
            }
            val sigB64 = Base64.getEncoder().encodeToString(sig)
            assertTrue(AutoUpdater.verifySignature(zip, sigB64, pub))

            zip.writeBytes(zip.readBytes().also { it[1234] = 0x55 })
            assertFalse(AutoUpdater.verifySignature(zip, sigB64, pub))
            assertFalse(AutoUpdater.verifySignature(zip, "not base64!", pub))
        } finally {
            zip.delete()
        }
    }

    /** Catches a public key pasted from a different pair than the one releases are signed with. */
    @Test
    fun `embedded public key matches the local signing key`() {
        val keyFile = File(System.getenv("LYRENNE_SIGNING_KEY") ?: "${System.getProperty("user.home")}/.lyrenne/update-signing.key")
        org.junit.Assume.assumeTrue("no signing key on this machine", keyFile.exists())
        val private = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyFile.readText().trim()))
        )
        val zip = File.createTempFile("update", ".zip").apply { writeText("release") }
        try {
            val sig = Signature.getInstance("Ed25519").run { initSign(private); update(AutoUpdater.sha256(zip)); sign() }
            assertTrue(AutoUpdater.verifySignature(zip, Base64.getEncoder().encodeToString(sig), AutoUpdater.UPDATE_PUBLIC_KEY))
        } finally {
            zip.delete()
        }
    }

    @Test
    fun `version comes from the generated resource`() {
        assertTrue(AutoUpdater.CURRENT_VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
        val gradle = File("build.gradle.kts").takeIf { it.exists() } ?: File("desktop/build.gradle.kts")
        val declared = Regex("""val lyrenneVersion = "([^"]+)"""").find(gradle.readText())?.groupValues?.get(1)
        assertEquals(declared, AutoUpdater.CURRENT_VERSION)
    }
}
