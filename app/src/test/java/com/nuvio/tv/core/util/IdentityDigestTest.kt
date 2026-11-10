package com.nuvio.tv.core.util

import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityDigestTest {
    @Test fun preservesExistingKeysAcrossLocalesAndUnicodeInputs() {
        val random = Random(17)
        val fixtures = listOf("", "subscription-fixture", "مناعة", "Ali Kara", "https://fixture.invalid/مسار") +
            List(256) { CharArray(random.nextInt(1, 128)) { random.nextInt(32, 0xd700).toChar() }.concatToString() }
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("ar-IQ"), Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                fixtures.forEach { value ->
                    val previous = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    assertEquals(previous, identityDigest(value))
                }
            }
        } finally { Locale.setDefault(original) }
    }
}
