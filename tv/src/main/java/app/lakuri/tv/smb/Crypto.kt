package app.lakuri.tv.smb

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The few primitives SMB2 and NTLM need that the platform doesn't provide (MD4, AES-CMAC), plus helpers. */
internal object Crypto {
    fun hmacMd5(key: ByteArray, vararg data: ByteArray): ByteArray = mac("HmacMD5", key, *data)

    fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray = mac("HmacSHA256", key, *data)

    private fun mac(alg: String, key: ByteArray, vararg data: ByteArray): ByteArray {
        val m = Mac.getInstance(alg)
        m.init(SecretKeySpec(key, alg))
        data.forEach { m.update(it) }
        return m.doFinal()
    }

    /** SP 800-108 counter-mode KDF with HMAC-SHA256, as SMB 3 uses it; returns a 128-bit key. */
    fun smb3Kdf(key: ByteArray, label: ByteArray, context: ByteArray): ByteArray =
        hmacSha256(key, byteArrayOf(0, 0, 0, 1), label, byteArrayOf(0), context, byteArrayOf(0, 0, 0, 128.toByte())).copyOf(16)

    /** AES-128-CMAC (RFC 4493). */
    fun aesCmac(key: ByteArray, msg: ByteArray): ByteArray {
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val k1 = dbl(aes.doFinal(ByteArray(16)))
        val k2 = dbl(k1)
        val n = maxOf(1, (msg.size + 15) / 16)
        val complete = msg.isNotEmpty() && msg.size % 16 == 0
        val last = ByteArray(16)
        val tailStart = (n - 1) * 16
        if (complete) {
            for (i in 0 until 16) last[i] = (msg[tailStart + i].toInt() xor k1[i].toInt()).toByte()
        } else {
            val rem = msg.size - tailStart
            for (i in 0 until rem) last[i] = msg[tailStart + i]
            last[rem] = 0x80.toByte()
            for (i in 0 until 16) last[i] = (last[i].toInt() xor k2[i].toInt()).toByte()
        }
        var x = ByteArray(16)
        for (b in 0 until n - 1) {
            for (i in 0 until 16) x[i] = (x[i].toInt() xor msg[b * 16 + i].toInt()).toByte()
            x = aes.doFinal(x)
        }
        for (i in 0 until 16) x[i] = (x[i].toInt() xor last[i].toInt()).toByte()
        return aes.doFinal(x)
    }

    private fun dbl(b: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val v = b[i].toInt() and 0xFF
            out[i] = ((v shl 1) or carry).toByte()
            carry = v ushr 7
        }
        if (carry != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
        return out
    }

    /** MD4 (RFC 1320), needed only for the NT password hash. */
    fun md4(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 8 + i] = (bitLen ushr (8 * i)).toByte()

        var a = 0x67452301; var b = 0xefcdab89.toInt(); var c = 0x98badcfe.toInt(); var d = 0x10325476
        val x = IntArray(16)
        for (block in msg.indices step 64) {
            for (i in 0 until 16) {
                val o = block + i * 4
                x[i] = (msg[o].toInt() and 0xFF) or ((msg[o + 1].toInt() and 0xFF) shl 8) or
                    ((msg[o + 2].toInt() and 0xFF) shl 16) or ((msg[o + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d
            fun f(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)
            fun g(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
            fun h(x: Int, y: Int, z: Int) = x xor y xor z
            for (i in intArrayOf(0, 4, 8, 12)) {
                a = Integer.rotateLeft(a + f(b, c, d) + x[i], 3)
                d = Integer.rotateLeft(d + f(a, b, c) + x[i + 1], 7)
                c = Integer.rotateLeft(c + f(d, a, b) + x[i + 2], 11)
                b = Integer.rotateLeft(b + f(c, d, a) + x[i + 3], 19)
            }
            for (i in 0 until 4) {
                a = Integer.rotateLeft(a + g(b, c, d) + x[i] + 0x5a827999, 3)
                d = Integer.rotateLeft(d + g(a, b, c) + x[i + 4] + 0x5a827999, 5)
                c = Integer.rotateLeft(c + g(d, a, b) + x[i + 8] + 0x5a827999, 9)
                b = Integer.rotateLeft(b + g(c, d, a) + x[i + 12] + 0x5a827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = Integer.rotateLeft(a + h(b, c, d) + x[i] + 0x6ed9eba1, 3)
                d = Integer.rotateLeft(d + h(a, b, c) + x[i + 8] + 0x6ed9eba1, 9)
                c = Integer.rotateLeft(c + h(d, a, b) + x[i + 4] + 0x6ed9eba1, 11)
                b = Integer.rotateLeft(b + h(c, d, a) + x[i + 12] + 0x6ed9eba1, 15)
            }
            a += aa; b += bb; c += cc; d += dd
        }
        val out = ByteArray(16)
        intArrayOf(a, b, c, d).forEachIndexed { i, v -> for (j in 0 until 4) out[i * 4 + j] = (v ushr (8 * j)).toByte() }
        return out
    }
}
