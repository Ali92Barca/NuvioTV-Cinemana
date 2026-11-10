package com.nuvio.tv.core.util

import java.security.MessageDigest

/** Byte-for-byte compatible with the persisted lowercase SHA-256 keys, without Formatter/ICU. */
internal fun identityDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    return CharArray(bytes.size * 2) { index ->
        val byte = bytes[index / 2].toInt() and 0xff
        digits[if (index % 2 == 0) byte ushr 4 else byte and 0x0f]
    }.concatToString()
}
