package com.leon.synthez.core

private val GOLDEN = 0x9E37_79B9_7F4A_7C15uL.toLong()
private val FNV_OFFSET = 0xCBF2_9CE4_8422_2325uL.toLong()
private const val FNV_PRIME = 0x0000_0100_0000_01B3L
private val MIX_1 = 0xBF58_476D_1CE4_E5B9uL.toLong()
private val MIX_2 = 0x94D0_49BB_1331_11EBuL.toLong()

/**
 * Identifies state, interactions and animations across frames.
 *
 * IDs are derived, never stored as strings: a region's children mix the
 * region's ID with a tag and a running counter, so the same calls in the same
 * order yield the same IDs every frame.
 */
@JvmInline
value class SZID(val raw: Long) {
    /** A child of this ID: `tag` names the kind, `index` the position. */
    fun child(tag: Int, index: Int): SZID =
        SZID(mix(raw + (((tag.toLong() and 0xFF) shl 32) or (index.toLong() and 0xFFFF_FFFFL)) * GOLDEN))

    /** A named part of this ID, such as a slider's thumb. */
    fun part(name: String): SZID = SZID(mix(raw xor of(name).raw))

    override fun toString(): String = "SZID(${raw.toULong().toString(16)})"

    companion object {
        val ROOT = SZID(0x5359_4E54_4845_5A00L)

        /** A global ID from a string, independent of where it is used. */
        fun of(key: String): SZID {
            // FNV-1a over UTF-8.
            var hash = FNV_OFFSET
            for (byte in key.encodeToByteArray()) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= FNV_PRIME
            }
            return SZID(mix(hash))
        }

        /** A global ID from an integer, such as a row's model identifier. */
        fun of(value: Long): SZID = SZID(mix(value xor GOLDEN))

        fun of(value: Int): SZID = of(value.toLong())

        /** SplitMix64's finalizer. */
        internal fun mix(value: Long): Long {
            var z = value + GOLDEN
            z = (z xor (z ushr 30)) * MIX_1
            z = (z xor (z ushr 27)) * MIX_2
            return z xor (z ushr 31)
        }
    }
}

/**
 * Resolves an explicit key a caller passes to pin state to something other
 * than call position: a [String], an [Int], a [Long] or an [SZID].
 */
fun szKey(key: Any): SZID = when (key) {
    is SZID -> key
    is String -> SZID.of(key)
    is Int -> SZID.of(key)
    is Long -> SZID.of(key)
    else -> throw IllegalArgumentException("A key is a String, an Int, a Long or an SZID, not ${key::class.java.name}")
}
