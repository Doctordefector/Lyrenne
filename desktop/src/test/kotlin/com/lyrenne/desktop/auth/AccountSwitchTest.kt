package com.lyrenne.desktop.auth

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the file moves behind account switching (issue #11).
 *
 * credentials.json is the active account and data/accounts holds the rest. A switch has to swap
 * the two without ever losing one, and without an account being listed twice. None of this
 * touches the network: the cookies below carry no SAPISID, so the post-switch refresh has
 * nothing to sign and returns straight away.
 */
class AccountSwitchTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: File

    private fun creds(name: String, id: String?) = AuthCredentials(
        cookie = "SID=$name",
        visitorData = "",
        dataSyncId = "",
        accountInfo = AccountInfo(name = name, email = "$name@example.com", channelHandle = ""),
        accountId = id,
    )

    private fun read(file: File) = json.decodeFromString<AuthCredentials>(file.readText())

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lyrenne-accounts").toFile()
        AuthManager.storageDirOverride = dir
    }

    @After
    fun tearDown() {
        AuthManager.storageDirOverride = null
        dir.deleteRecursively()
    }

    @Test
    fun `switching swaps the active and saved accounts and back again`() = runBlocking {
        File(dir, "credentials.json").writeText(json.encodeToString(creds("alice", "aaaaaaaaaaaaaaaa")))
        File(dir, "accounts").mkdirs()
        File(dir, "accounts/bbbbbbbbbbbbbbbb.json").writeText(json.encodeToString(creds("bob", "bbbbbbbbbbbbbbbb")))
        AuthManager.loadFromDisk()

        assertEquals("aaaaaaaaaaaaaaaa", AuthManager.authState.value.accountId)
        assertEquals(listOf("bob"), AuthManager.savedAccounts.value.map { it.accountInfo?.name })

        assertTrue(AuthManager.switchTo("bbbbbbbbbbbbbbbb").isSuccess)
        assertEquals("bob", read(File(dir, "credentials.json")).accountInfo?.name)
        assertEquals("bbbbbbbbbbbbbbbb", AuthManager.authState.value.accountId)
        assertTrue(AuthManager.authState.value.isLoggedIn)
        assertEquals(listOf("alice"), AuthManager.savedAccounts.value.map { it.accountInfo?.name })
        assertFalse(File(dir, "accounts/bbbbbbbbbbbbbbbb.json").exists())

        assertTrue(AuthManager.switchTo("aaaaaaaaaaaaaaaa").isSuccess)
        assertEquals("alice", read(File(dir, "credentials.json")).accountInfo?.name)
        assertEquals(listOf("bob"), AuthManager.savedAccounts.value.map { it.accountInfo?.name })
    }

    @Test
    fun `an expired saved account is refused rather than switched into`() = runBlocking {
        File(dir, "credentials.json").writeText(json.encodeToString(creds("alice", "aaaaaaaaaaaaaaaa")))
        File(dir, "accounts").mkdirs()
        File(dir, "accounts/cccccccccccccccc.json")
            .writeText(json.encodeToString(creds("carol", "cccccccccccccccc").copy(sessionExpired = true)))
        AuthManager.loadFromDisk()

        assertTrue(AuthManager.savedAccounts.value.single().sessionExpired)
        assertTrue(AuthManager.switchTo("cccccccccccccccc").isFailure)
        assertEquals("alice", read(File(dir, "credentials.json")).accountInfo?.name)
    }

    @Test
    fun `credentials from before switching get an id once, and keep it`() {
        File(dir, "credentials.json").writeText(json.encodeToString(creds("legacy", null)))
        AuthManager.loadFromDisk()

        val id = read(File(dir, "credentials.json")).accountId
        assertNotNull(id)
        assertEquals(id, AuthManager.authState.value.accountId)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(id!!))

        AuthManager.loadFromDisk()
        assertEquals(id, read(File(dir, "credentials.json")).accountId)
    }

    @Test
    fun `removing a saved account deletes only that account`() = runBlocking {
        File(dir, "credentials.json").writeText(json.encodeToString(creds("alice", "aaaaaaaaaaaaaaaa")))
        File(dir, "accounts").mkdirs()
        File(dir, "accounts/bbbbbbbbbbbbbbbb.json").writeText(json.encodeToString(creds("bob", "bbbbbbbbbbbbbbbb")))
        AuthManager.loadFromDisk()

        AuthManager.removeSaved("bbbbbbbbbbbbbbbb")
        assertTrue(AuthManager.savedAccounts.value.isEmpty())
        assertTrue(File(dir, "credentials.json").exists())
    }

    @Test
    fun `a saved copy of the active account under another id is dropped, not listed twice`() {
        File(dir, "credentials.json").writeText(
            json.encodeToString(creds("alice", "aaaaaaaaaaaaaaaa").copy(dataSyncId = "gaia1||"))
        )
        File(dir, "accounts").mkdirs()
        val stale = File(dir, "accounts/1111111111111111.json")
        stale.writeText(json.encodeToString(creds("alice", "1111111111111111").copy(dataSyncId = "gaia1")))
        AuthManager.loadFromDisk()

        assertTrue(AuthManager.savedAccounts.value.isEmpty())
        assertFalse(stale.exists())
    }

    @Test
    fun `a brand channel sharing its owner's email is a different account`() {
        val owner = creds("alice", "aaaaaaaaaaaaaaaa").copy(dataSyncId = "gaia1")
        val brand = creds("alice", "bbbbbbbbbbbbbbbb").copy(dataSyncId = "gaia2")
        assertFalse(AuthManager.sameAccount(owner, brand))
        // Without a DATASYNC_ID on one side, the email is what identifies it.
        assertTrue(AuthManager.sameAccount(owner, brand.copy(dataSyncId = "", accountId = null)))
    }

    @Test
    fun `signing out keeps the other accounts`() {
        File(dir, "credentials.json").writeText(json.encodeToString(creds("alice", "aaaaaaaaaaaaaaaa")))
        File(dir, "accounts").mkdirs()
        File(dir, "accounts/bbbbbbbbbbbbbbbb.json").writeText(json.encodeToString(creds("bob", "bbbbbbbbbbbbbbbb")))
        AuthManager.loadFromDisk()

        AuthManager.logout()
        assertFalse(File(dir, "credentials.json").exists())
        assertFalse(AuthManager.authState.value.isLoggedIn)
        assertTrue(File(dir, "accounts/bbbbbbbbbbbbbbbb.json").exists())
    }

    @Test
    fun `restoring a backup first keeps whoever is signed in`() {
        File(dir, "credentials.json").writeText(json.encodeToString(creds("alice", "aaaaaaaaaaaaaaaa")))
        AuthManager.loadFromDisk()

        AuthManager.stashActiveBeforeRestore()
        assertEquals("alice", read(File(dir, "accounts/aaaaaaaaaaaaaaaa.json")).accountInfo?.name)
    }

    @Test
    fun `the id prefers the account's own id over the sign-in cookie`() {
        val a = AuthManager.deriveAccountId("gaia123||", "SAPISID=one")
        val b = AuthManager.deriveAccountId("gaia123", "SAPISID=two")
        assertEquals(a, b)
        val c = AuthManager.deriveAccountId("", "SAPISID=one; SID=x")
        val d = AuthManager.deriveAccountId("", "SAPISID=two; SID=x")
        assertTrue(c != d)
    }
}
