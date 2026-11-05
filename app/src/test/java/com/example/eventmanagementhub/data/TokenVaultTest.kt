package com.example.eventmanagementhub.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenVaultTest {
    private class FakeCipher(var failDecrypt: Boolean = false) : TokenCipher {
        override fun encrypt(plain: String) = "enc(" + plain.reversed() + ")"
        override fun decrypt(encoded: String): String {
            if (failDecrypt) throw IllegalStateException("key lost")
            return encoded.removePrefix("enc(").removeSuffix(")").reversed()
        }
    }

    private var slot: String? = null
    private fun vault(c: TokenCipher) = TokenVault(c, { slot }, { slot = it })

    @Test fun roundTrip_storesCiphertextNotPlaintext() = runTest {
        val v = vault(FakeCipher())
        v.save("aaa.bbb.ccc")
        assertFalse(slot!!.contains("aaa.bbb.ccc"))
        assertTrue(slot!!.startsWith(TokenVault.PREFIX))
        assertEquals("aaa.bbb.ccc", v.load())
    }

    @Test fun decryptFailure_returnsNullAndClears() = runTest {
        val c = FakeCipher()
        val v = vault(c)
        v.save("tok")
        c.failDecrypt = true
        assertNull(v.load())
        assertEquals("", slot)
        c.failDecrypt = false
        assertNull(v.load())
    }

    @Test fun legacyPlaintext_isReturnedAndReSavedEncrypted() = runTest {
        slot = "legacy.jwt.token"
        val v = vault(FakeCipher())
        assertEquals("legacy.jwt.token", v.load())
        assertTrue(slot!!.startsWith(TokenVault.PREFIX))
        assertFalse(slot!!.contains("legacy.jwt.token"))
        assertEquals("legacy.jwt.token", v.load())
    }

    @Test fun emptyOrClearedSlot_isNull() = runTest {
        val v = vault(FakeCipher())
        assertNull(v.load())
        v.save("x"); v.clear()
        assertNull(v.load())
    }
}
