package com.nuvio.tv.data.repository

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackIdentityIndexTest {
    @Test fun indexedLookupsKeepLinearOrderAndDuplicateAndMediaTypeSemantics() {
        val random = Random(17)
        repeat(100) {
            val rows = List(random.nextInt(0, 80)) {
                val native = "cee:${random.nextInt(10)}"
                PlaybackIdentity(native, "tt${random.nextInt(20)}", random.nextBoolean(),
                    List(random.nextInt(0, 7)) { "alias:${random.nextInt(12)}" }.toSet(), emptyMap())
            }
            val index = PlaybackIdentityIndex(rows)
            repeat(20) {
                val id = "cee:${random.nextInt(15)}"
                assertEquals(rows.firstOrNull { it.native == id }, index.native(id))
                for (kind in listOf(true, false))
                    assertEquals(rows.firstOrNull { it.native == id && it.series == kind }, index.native(id, kind))
                val alias = "alias:${random.nextInt(15)}"
                assertEquals(rows.filter { alias in it.aliases }, index.alias(alias))
                val aliases = setOf(alias, "alias:${random.nextInt(15)}")
                assertEquals(rows.filter { row -> row.aliases.any { it in aliases } }, index.matching(aliases))
            }
        }
    }
}
