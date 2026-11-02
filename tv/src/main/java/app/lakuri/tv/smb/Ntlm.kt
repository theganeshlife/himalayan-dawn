package app.lakuri.tv.smb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** NTLMv2 sign-in (MS-NLMP), wrapped in SPNEGO for SMB2 session setup. A blank [user] signs in as a guest. */
internal class Ntlm(private val user: String, private val password: String, private val domain: String) {
    private val anonymous = user.isEmpty()

    /** Key for signing SMB messages; null for guest sign-in. */
    var sessionKey: ByteArray? = null
        private set

    fun negotiate(): ByteArray {
        val b = le(32)
        b.put(SIGNATURE).putInt(1).putInt(flags())
        return b.array() // empty domain and workstation fields
    }

    private fun flags() = CLIENT_FLAGS or (if (anonymous) ANONYMOUS else 0)

    fun authenticate(challenge: ByteArray, clientChallenge: ByteArray = random(8), now: Long = filetimeNow()): ByteArray {
        val c = ByteBuffer.wrap(challenge).order(ByteOrder.LITTLE_ENDIAN)
        if (challenge.size < 48 || !challenge.copyOf(8).contentEquals(SIGNATURE) || c.getInt(8) != 2) {
            throw SmbException("The computer sent an unexpected sign-in reply")
        }
        val serverFlags = c.getInt(20)
        val serverChallenge = challenge.copyOfRange(24, 32)
        val tiLen = c.getShort(40).toInt() and 0xFFFF
        val tiOff = c.getInt(44)
        val targetInfo = if (tiLen > 0 && tiOff + tiLen <= challenge.size) challenge.copyOfRange(tiOff, tiOff + tiLen) else ByteArray(0)
        val flags = (serverFlags and flags()) or NEGOTIATE_UNICODE or (if (anonymous) ANONYMOUS else 0)

        val lm: ByteArray
        val nt: ByteArray
        if (anonymous) {
            lm = ByteArray(1)
            nt = ByteArray(0)
        } else {
            val r = v2Response(serverChallenge, clientChallenge, avTimestamp(targetInfo) ?: now, targetInfo)
            nt = r.nt
            lm = if (avTimestamp(targetInfo) != null) ByteArray(24) else r.lm
            sessionKey = r.sessionBaseKey
        }
        val dom = utf16(domain)
        val usr = utf16(user)
        val ws = ByteArray(0)
        val payload = ByteArrayOutputStream()
        val head = le(64)
        head.put(SIGNATURE).putInt(3)
        fun field(at: Int, data: ByteArray) {
            head.putShort(at, data.size.toShort()).putShort(at + 2, data.size.toShort()).putInt(at + 4, 64 + payload.size())
            payload.write(data)
        }
        field(28, dom); field(36, usr); field(44, ws); field(12, lm); field(20, nt); field(52, ByteArray(0))
        head.putInt(60, flags)
        return head.array() + payload.toByteArray()
    }

    class V2(val nt: ByteArray, val lm: ByteArray, val sessionBaseKey: ByteArray)

    fun v2Response(serverChallenge: ByteArray, clientChallenge: ByteArray, time: Long, targetInfo: ByteArray): V2 {
        val key = ntowfv2(password, user, domain)
        val temp = le(28 + targetInfo.size + 4).apply {
            put(1).put(1).put(ByteArray(6)).putLong(time).put(clientChallenge).putInt(0).put(targetInfo).putInt(0)
        }.array()
        val proof = Crypto.hmacMd5(key, serverChallenge, temp)
        val lm = Crypto.hmacMd5(key, serverChallenge, clientChallenge) + clientChallenge
        return V2(proof + temp, lm, Crypto.hmacMd5(key, proof))
    }

    private fun avTimestamp(ti: ByteArray): Long? {
        val b = ByteBuffer.wrap(ti).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 4 <= ti.size) {
            val id = b.getShort(i).toInt() and 0xFFFF
            val len = b.getShort(i + 2).toInt() and 0xFFFF
            if (id == 0) return null
            if (id == 7 && len == 8 && i + 12 <= ti.size) return b.getLong(i + 4)
            i += 4 + len
        }
        return null
    }

    companion object {
        private val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
        private const val NEGOTIATE_UNICODE = 0x00000001
        private const val ANONYMOUS = 0x00000800
        // Unicode, request target, sign, NTLM, always sign, extended session security, target info, 128-bit, 56-bit.
        private const val CLIENT_FLAGS = 0x00000001 or 0x00000004 or 0x00000010 or 0x00000200 or 0x00008000 or
            0x00080000 or 0x00800000 or 0x20000000 or (0x80000000).toInt()

        fun ntowfv2(password: String, user: String, domain: String): ByteArray =
            Crypto.hmacMd5(Crypto.md4(utf16(password)), utf16(user.uppercase() + domain))

        fun utf16(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE)

        private fun filetimeNow() = (System.currentTimeMillis() + 11_644_473_600_000L) * 10_000L

        private val rng = SecureRandom()
        fun random(n: Int) = ByteArray(n).also(rng::nextBytes)

        private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    }
}

/** Just enough SPNEGO (RFC 4178) to offer NTLM and carry its messages. */
internal object Spnego {
    private val SPNEGO_OID = byteArrayOf(0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02)
    private val NTLM_OID = byteArrayOf(0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0a)

    fun init(ntlm: ByteArray): ByteArray =
        tlv(0x60, SPNEGO_OID + tlv(0xa0, tlv(0x30, tlv(0xa0, tlv(0x30, NTLM_OID)) + tlv(0xa2, tlv(0x04, ntlm)))))

    fun response(ntlm: ByteArray): ByteArray = tlv(0xa1, tlv(0x30, tlv(0xa2, tlv(0x04, ntlm))))

    /** The NTLM message inside a server's SPNEGO reply (or a raw one). */
    fun extractNtlm(blob: ByteArray): ByteArray {
        val sig = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
        for (i in 0..blob.size - sig.size) {
            if ((sig.indices).all { blob[i + it] == sig[it] }) return blob.copyOfRange(i, blob.size)
        }
        throw SmbException("The computer doesn't offer NTLM sign-in")
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val len = body.size
        val lenBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + body
    }
}
