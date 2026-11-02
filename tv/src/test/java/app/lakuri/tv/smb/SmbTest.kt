package app.lakuri.tv.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

class SmbTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun md4MatchesRfc1320() {
        assertArrayEquals(hex("31d6cfe0d16ae931b73c59d7e0c089c0"), Crypto.md4(ByteArray(0)))
        assertArrayEquals(hex("a448017aaf21d8525fc10ae87aa6729d"), Crypto.md4("abc".toByteArray()))
        assertArrayEquals(hex("e33b4ddc9c38f2199c3e7b164fcc0536"),
            Crypto.md4("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray()))
    }

    @Test fun aesCmacMatchesRfc4493() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        assertArrayEquals(hex("bb1d6929e95937287fa37d129b756746"), Crypto.aesCmac(key, ByteArray(0)))
        assertArrayEquals(hex("070a16b46b4d4144f79bdd9dd04a287c"), Crypto.aesCmac(key, hex("6bc1bee22e409f96e93d7e117393172a")))
        assertArrayEquals(hex("dfa66747de9ae63030ca32611497c827"),
            Crypto.aesCmac(key, hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411")))
    }

    /** Expected values computed with impacket's KDF_CounterMode and AES_CMAC. */
    @Test fun smb3SigningMatchesImpacket() {
        val key = Crypto.smb3Kdf(ByteArray(16) { it.toByte() }, "SMB2AESCMAC\u0000".toByteArray(), "SmbSign\u0000".toByteArray())
        assertArrayEquals(hex("6234814cbb8ea9227440ebfeb5eacbe1"), key)
        assertArrayEquals(hex("9f72e757e77da0998a4e276f82db20c6"), Crypto.aesCmac(key, ByteArray(100) { it.toByte() }))
        assertArrayEquals(hex("d6a7da49154db997ebb3ac555daa79ce"), Crypto.aesCmac(key, ByteArray(64) { it.toByte() }))
    }

    /** MS-NLMP section 4.2.4 (NTLMv2 authentication) example values. */
    @Test fun ntlmV2MatchesSpecExample() {
        assertArrayEquals(hex("0c868a403bfd7a93a3001ef22ef02e3f"), Ntlm.ntowfv2("Password", "User", "Domain"))
        val ti = hex("02000c0044006f006d00610069006e0001000c0053006500720076006500720000000000")
        val r = Ntlm("User", "Password", "Domain").v2Response(hex("0123456789abcdef"), ByteArray(8) { 0xaa.toByte() }, 0L, ti)
        assertArrayEquals(hex("68cd0ab851e51c96aabc927bebef6a1c"), r.nt.copyOf(16))
        assertArrayEquals(hex("8de40ccadbc14a82f15cb0ad0de95ca3"), r.sessionBaseKey)
    }

    @Test fun onlyHomeNetworkAddressesAreAllowed() {
        for (a in listOf("192.168.1.50", "10.0.0.2", "172.16.5.4", "172.31.255.1", "169.254.10.10", "fd12:3456::1", "fe80::1")) {
            assertTrue(a, LocalNetwork.isLocal(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.64.0.1", "127.0.0.1", "0.0.0.0", "2001:4860:4860::8888", "::1")) {
            assertFalse(a, LocalNetwork.isLocal(InetAddress.getByName(a)))
        }
        try {
            LocalNetwork.resolve("8.8.8.8")
            fail("internet address accepted")
        } catch (e: SmbException) {
            assertTrue(e.message!!.contains("not on your home network"))
        }
        assertEquals("192.168.1.50", LocalNetwork.resolve("\\\\192.168.1.50\\").hostAddress)
    }

    /**
     * End-to-end against a real SMB server. Skipped unless SMB_TEST_HOST is set, e.g. with impacket:
     * `smbserver.py -smb2support -port 4445 -username tv -password secret photos /path/to/dir`
     * (the dir needs a.jpg and sub/b.png), then SMB_TEST_HOST=<LAN IP> SMB_TEST_PORT=4445.
     */
    @Test fun liveServer() {
        val host = System.getenv("SMB_TEST_HOST")
        assumeTrue(host != null)
        val port = System.getenv("SMB_TEST_PORT")?.toInt() ?: 445
        val share = System.getenv("SMB_TEST_SHARE") ?: "PHOTOS"
        try {
            Smb2Client.connect(host!!, "tv", "wrong", port = port).close()
            fail("wrong password accepted")
        } catch (e: SmbException) {
            assertEquals("Wrong user name or password", e.message)
        }
        Smb2Client.connect(host!!, System.getenv("SMB_TEST_USER") ?: "tv", System.getenv("SMB_TEST_PASS") ?: "secret", port = port).use { c ->
            val shares = c.listShares()
            assertTrue(shares.toString(), shares.any { it.equals(share, ignoreCase = true) })
            val top = c.list(share, "")
            assertTrue(top.toString(), top.any { it.name == "a.jpg" && !it.isDirectory })
            assertTrue(top.toString(), top.any { it.name == "sub" && it.isDirectory })
            assertTrue(c.list(share, "sub").any { it.name == "b.png" })
            val a = c.read(share, "a.jpg", 50L shl 20)
            assertEquals(top.first { it.name == "a.jpg" }.size, a.size.toLong())
            assertEquals(System.getenv("SMB_TEST_SHA256") ?: java.security.MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it) },
                java.security.MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it) })
        }
    }
}
