package com.lyrenne.desktop.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the cookie jar that keeps a signed-in session alive.
 *
 * Google rotates the session cookies (SIDCC, the *SIDTS pair) continuously, and the stored
 * snapshot has to move with them or the account silently drops to anonymous after a few weeks.
 * The merge is small enough to look obviously right and has two ways to be quietly catastrophic:
 * write a blanked value over a good cookie and the session dies on the spot, drop the rotated
 * value and nothing was gained. Neither shows up as a failure anywhere else in the build, since
 * the symptom is a login that stops working later, on someone else's machine.
 */
class CookieMergeTest {

    @Test
    fun `rotated cookies replace the stored value, attributes are dropped`() {
        val merged = AuthManager.mergeCookies(
            "SID=old; SAPISID=keep; __Secure-3PSIDTS=stale",
            listOf(
                "__Secure-3PSIDTS=fresh; Path=/; Domain=.youtube.com; Secure; HttpOnly; SameSite=none",
                "SID=new; Path=/"
            )
        )
        assertEquals("SID=new; SAPISID=keep; __Secure-3PSIDTS=fresh", merged)
    }

    @Test
    fun `names we do not already hold are ignored`() {
        val merged = AuthManager.mergeCookies(
            "SID=a",
            listOf("YSC=whatever; Path=/", "__Secure-YEC=alsonew")
        )
        assertEquals("SID=a", merged)
    }

    @Test
    fun `a deletion never overwrites a live cookie`() {
        val merged = AuthManager.mergeCookies(
            "SID=live; SAPISID=live",
            listOf(
                "SID=; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                "SAPISID=\"\"; Path=/"
            )
        )
        assertEquals("SID=live; SAPISID=live", merged)
    }

    @Test
    fun `values containing an equals sign survive the round trip`() {
        val merged = AuthManager.mergeCookies(
            "SIDCC=old",
            listOf("SIDCC=AKEyXzX==padding; Path=/")
        )
        assertEquals("SIDCC=AKEyXzX==padding", merged)
    }
}
