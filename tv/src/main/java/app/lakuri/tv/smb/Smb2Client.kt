package app.lakuri.tv.smb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SmbException(message: String, val status: Int = 0) : IOException(message)

/** One file or folder in a shared folder. */
data class SmbEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * A small read-only SMB2/3 client: enough to sign in, list shares and folders, and read files.
 * Speaks SMB 2.0.2 to 3.0.2 with message signing (no encryption). It only connects to addresses
 * from [LocalNetwork.resolve].
 */
class Smb2Client private constructor(private val socket: Socket, private val server: String) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536 + 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 65536 + 1024)
    private var messageId = 0L
    private var sessionId = 0L
    private var dialect = 0
    private var signingRequired = false
    private var signingKey: ByteArray? = null
    private var maxRead = 65536
    private val trees = HashMap<String, Int>()

    companion object {
        private const val NEGOTIATE = 0
        private const val SESSION_SETUP = 1
        private const val TREE_CONNECT = 3
        private const val CREATE = 5
        private const val CLOSE = 6
        private const val READ = 8
        private const val WRITE = 9
        private const val QUERY_DIRECTORY = 0x0E

        const val STATUS_SUCCESS = 0
        private const val STATUS_PENDING = 0x00000103
        private const val STATUS_BUFFER_OVERFLOW = 0x80000005.toInt()
        private const val STATUS_NO_MORE_FILES = 0x80000006.toInt()
        private const val STATUS_MORE_PROCESSING = 0xC0000016.toInt()
        private const val STATUS_END_OF_FILE = 0xC0000011.toInt()

        private val DIALECTS = intArrayOf(0x0202, 0x0210, 0x0300, 0x0302)
        private val SUCCESS_ONLY = intArrayOf(STATUS_SUCCESS)

        /**
         * Connects and signs in. [host] must resolve to a home-network address. A blank [user] signs in
         * as a guest; "DOMAIN\user" or "user@domain" are accepted.
         */
        fun connect(host: String, user: String, password: String, timeoutMs: Int = 8000, port: Int = 445): Smb2Client {
            val address: InetAddress = LocalNetwork.resolve(host)
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                socket.soTimeout = 20_000
                socket.tcpNoDelay = true
            } catch (e: IOException) {
                socket.close()
                throw SmbException("Couldn't reach ${address.hostAddress}. Check the computer is on and file sharing is turned on.")
            }
            val client = Smb2Client(socket, address.hostAddress ?: host)
            try {
                client.negotiate()
                client.signIn(user, password)
            } catch (e: Exception) {
                client.close()
                throw e
            }
            return client
        }

        fun describe(status: Int): String = when (status) {
            0xC000006D.toInt(), 0xC000006A.toInt(), 0xC0000064.toInt() -> "Wrong user name or password"
            0xC0000022.toInt() -> "Access denied"
            0xC00000CC.toInt() -> "There's no shared folder with that name"
            0xC0000034.toInt(), 0xC000003A.toInt() -> "Folder or file not found"
            0xC0000072.toInt() -> "That account is disabled"
            0xC0000071.toInt() -> "That account's password has expired"
            0xC000015B.toInt() -> "That account isn't allowed to sign in over the network"
            else -> "The computer refused the request (error 0x%08X)".format(status)
        }
    }

    private class Reply(val status: Int, val data: ByteArray) {
        val body: ByteBuffer = ByteBuffer.wrap(data, 64, data.size - 64).slice().order(ByteOrder.LITTLE_ENDIAN)
        val header: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun bytes(offsetFromHeader: Int, length: Int) = data.copyOfRange(offsetFromHeader, offsetFromHeader + length)
    }

    // ---- Transport ----

    private fun call(command: Int, body: ByteArray, treeId: Int = 0, ok: IntArray = SUCCESS_ONLY): Reply {
        val id = messageId++
        val msg = ByteBuffer.allocate(64 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        msg.put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
        msg.putShort(64)
        msg.putShort(if (dialect == 0 || dialect == 0x0202) 0 else 1) // credit charge
        msg.putInt(0)
        msg.putShort(command.toShort())
        msg.putShort(64) // credits requested
        msg.putInt(0) // flags
        msg.putInt(0) // next command
        msg.putLong(id)
        msg.putInt(0xFEFF) // process id
        msg.putInt(treeId)
        msg.putLong(sessionId)
        msg.put(ByteArray(16))
        msg.put(body)
        val bytes = msg.array()
        val key = signingKey
        if (key != null && command != NEGOTIATE && command != SESSION_SETUP) sign(bytes, key)
        output.write(0)
        output.write(bytes.size ushr 16)
        output.write(bytes.size ushr 8)
        output.write(bytes.size)
        output.write(bytes)
        output.flush()

        while (true) {
            if (input.readUnsignedByte() != 0) throw SmbException("Unexpected data from the computer")
            val len = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            val data = ByteArray(len)
            input.readFully(data)
            if (len < 64) throw SmbException("Unexpected data from the computer")
            if (data[0] == 0xFD.toByte()) throw SmbException("This shared folder needs encryption, which isn't supported. Turn off \"Encrypt data access\" for it.")
            if (data[0] != 0xFE.toByte()) throw SmbException("This computer doesn't speak SMB2. Turn on SMB2 or later in its file sharing settings.")
            val h = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val status = h.getInt(8)
            val flags = h.getInt(16)
            if (h.getLong(24) != id) continue // e.g. an oplock break, which we never ask for
            if (status == STATUS_PENDING && flags and 0x2 != 0) continue // interim reply; the real one follows
            if (command == SESSION_SETUP) sessionId = h.getLong(40)
            if (status !in ok) throw SmbException(describe(status), status)
            return Reply(status, data)
        }
    }

    private fun sign(msg: ByteArray, key: ByteArray) {
        msg[16] = (msg[16].toInt() or 0x08).toByte() // SMB2_FLAGS_SIGNED
        val sig = if (dialect >= 0x0300) Crypto.aesCmac(key, msg) else Crypto.hmacSha256(key, msg)
        sig.copyInto(msg, 48, 0, 16)
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    // ---- Session ----

    private fun negotiate() {
        val b = le(36 + DIALECTS.size * 2)
        b.putShort(36).putShort(DIALECTS.size.toShort()).putShort(1).putShort(0).putInt(0)
        b.put(Ntlm.random(16)).putLong(0)
        DIALECTS.forEach { b.putShort(it.toShort()) }
        val r = call(NEGOTIATE, b.array())
        dialect = r.body.getShort(4).toInt() and 0xFFFF
        if (dialect !in DIALECTS) throw SmbException("This computer needs a newer SMB version than this TV supports (dialect 0x%04X)".format(dialect))
        signingRequired = r.body.getShort(2).toInt() and 0x2 != 0
        maxRead = r.body.getInt(32).coerceIn(4096, 65536)
    }

    private fun signIn(rawUser: String, password: String) {
        var user = rawUser.trim()
        var domain = ""
        if ('\\' in user) { domain = user.substringBefore('\\'); user = user.substringAfter('\\') }
        else if ('@' in user) { domain = user.substringAfter('@'); user = user.substringBefore('@') }
        val ntlm = Ntlm(user, password, domain)

        val first = call(SESSION_SETUP, sessionSetup(Spnego.init(ntlm.negotiate())), ok = intArrayOf(STATUS_MORE_PROCESSING))
        val challenge = Spnego.extractNtlm(securityBuffer(first))
        val done = call(SESSION_SETUP, sessionSetup(Spnego.response(ntlm.authenticate(challenge))))
        val sessionFlags = done.body.getShort(2).toInt() and 0xFFFF
        if (sessionFlags and 0x4 != 0) throw SmbException("This computer needs encryption, which isn't supported. Turn off \"Encrypt data access\".")
        val guest = sessionFlags and 0x3 != 0
        val key = ntlm.sessionKey
        if (signingRequired && !guest && key != null) {
            signingKey = if (dialect >= 0x0300) {
                Crypto.smb3Kdf(key, "SMB2AESCMAC\u0000".toByteArray(Charsets.US_ASCII), "SmbSign\u0000".toByteArray(Charsets.US_ASCII))
            } else {
                key.copyOf(16)
            }
        }
    }

    private fun sessionSetup(token: ByteArray): ByteArray {
        val b = le(24 + token.size)
        b.putShort(25).put(0).put(1).putInt(0).putInt(0).putShort((64 + 24).toShort()).putShort(token.size.toShort()).putLong(0)
        b.put(token)
        return b.array()
    }

    private fun securityBuffer(r: Reply): ByteArray {
        val off = r.body.getShort(4).toInt() and 0xFFFF
        val len = r.body.getShort(6).toInt() and 0xFFFF
        return r.bytes(off, len)
    }

    private fun tree(share: String): Int = trees.getOrPut(share.lowercase()) {
        val path = Ntlm.utf16("\\\\$server\\$share")
        val b = le(8 + path.size)
        b.putShort(9).putShort(0).putShort((64 + 8).toShort()).putShort(path.size.toShort()).put(path)
        val r = call(TREE_CONNECT, b.array())
        if (r.body.getInt(4) and 0x8000 != 0) throw SmbException("This shared folder needs encryption, which isn't supported")
        r.header.getInt(36)
    }

    // ---- Files ----

    private class Handle(val tree: Int, val fileId: ByteArray, val size: Long)

    private fun open(share: String, path: String, directory: Boolean, access: Int = 0x00120089): Handle {
        val t = tree(share)
        val name = Ntlm.utf16(path.replace('/', '\\').trim('\\'))
        val b = le(56 + maxOf(name.size, 1))
        b.putShort(57).put(0).put(0).putInt(2).putLong(0).putLong(0)
        b.putInt(access).putInt(0).putInt(0x7).putInt(1) // share read/write/delete, FILE_OPEN
        b.putInt(if (directory) 0x1 else 0x40) // directory / non-directory file
        b.putShort((64 + 56).toShort()).putShort(name.size.toShort()).putInt(0).putInt(0)
        b.put(name)
        val r = call(CREATE, b.array(), t)
        return Handle(t, r.bytes(64 + 64, 16), r.body.getLong(48))
    }

    private fun closeHandle(h: Handle) {
        val b = le(24)
        b.putShort(24).putShort(0).putInt(0).put(h.fileId)
        try { call(CLOSE, b.array(), h.tree) } catch (e: SmbException) { /* already gone */ }
    }

    /** Files and folders directly inside [path] of [share] (path "" = the share itself). */
    fun list(share: String, path: String): List<SmbEntry> {
        val h = open(share, path, directory = true, access = 0x00100081)
        val out = mutableListOf<SmbEntry>()
        try {
            val pattern = Ntlm.utf16("*")
            var first = true
            while (true) {
                val b = le(32 + pattern.size)
                b.putShort(33).put(0x01).put((if (first) 1 else 0).toByte()).putInt(0).put(h.fileId)
                b.putShort((64 + 32).toShort()).putShort(pattern.size.toShort()).putInt(65536).put(pattern)
                first = false
                val r = call(QUERY_DIRECTORY, b.array(), h.tree, intArrayOf(STATUS_SUCCESS, STATUS_NO_MORE_FILES))
                if (r.status == STATUS_NO_MORE_FILES) break
                val off = r.body.getShort(2).toInt() and 0xFFFF
                val len = r.body.getInt(4)
                val buf = ByteBuffer.wrap(r.data, off, len).slice().order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i + 64 <= len) {
                    val next = buf.getInt(i)
                    val size = buf.getLong(i + 40)
                    val attrs = buf.getInt(i + 56)
                    val nameLen = buf.getInt(i + 60)
                    val name = String(r.data, off + i + 64, nameLen, Charsets.UTF_16LE)
                    if (name != "." && name != ".." && attrs and 0x6 == 0) { // skip hidden and system files
                        out += SmbEntry(name, attrs and 0x10 != 0, size)
                    }
                    if (next == 0) break
                    i += next
                }
            }
        } finally {
            closeHandle(h)
        }
        return out
    }

    /** The whole file at [path]; refuses files larger than [maxBytes]. */
    fun read(share: String, path: String, maxBytes: Long): ByteArray {
        val h = open(share, path, directory = false)
        try {
            if (h.size > maxBytes) throw SmbException("File too large")
            val out = ByteArrayOutputStream(h.size.toInt())
            var offset = 0L
            while (offset < h.size) {
                val want = minOf(maxRead.toLong(), h.size - offset).toInt()
                val b = le(49)
                b.putShort(49).put(0x50).put(0).putInt(want).putLong(offset).put(h.fileId)
                b.putInt(0).putInt(0).putInt(0).putShort(0).putShort(0).put(0)
                val r = call(READ, b.array(), h.tree, intArrayOf(STATUS_SUCCESS, STATUS_END_OF_FILE))
                if (r.status == STATUS_END_OF_FILE) break
                val dataOff = r.body.get(2).toInt() and 0xFF
                val dataLen = r.body.getInt(4)
                if (dataLen <= 0) break
                out.write(r.data, dataOff, dataLen)
                offset += dataLen
            }
            return out.toByteArray()
        } finally {
            closeHandle(h)
        }
    }

    // ---- Share list (srvsvc NetrShareEnum over DCE/RPC) ----

    /** Names of the ordinary shared folders on this computer (not C$, ADMIN$, IPC$ or printers). */
    fun listShares(): List<String> {
        val pipe = open("IPC$", "srvsvc", directory = false, access = 0x0012019F)
        try {
            pipeWrite(pipe, Rpc.bind())
            Rpc.checkBindAck(pipeRead(pipe))
            pipeWrite(pipe, Rpc.netrShareEnumRequest(server))
            val stub = ByteArrayOutputStream()
            while (true) {
                val frag = pipeRead(pipe)
                stub.write(Rpc.responseStub(frag))
                if (frag[3].toInt() and 0x02 != 0) break // last fragment
            }
            return Rpc.parseShares(stub.toByteArray())
        } finally {
            closeHandle(pipe)
        }
    }

    private fun pipeWrite(h: Handle, data: ByteArray) {
        val b = le(48 + data.size)
        b.putShort(49).putShort((64 + 48).toShort()).putInt(data.size).putLong(0).put(h.fileId)
        b.putInt(0).putInt(0).putShort(0).putShort(0).putInt(0).put(data)
        call(WRITE, b.array(), h.tree)
    }

    private fun pipeRead(h: Handle): ByteArray {
        val all = ByteArrayOutputStream()
        while (true) {
            val b = le(49)
            b.putShort(49).put(0x50).put(0).putInt(maxRead).putLong(0).put(h.fileId)
            b.putInt(0).putInt(0).putInt(0).putShort(0).putShort(0).put(0)
            val r = call(READ, b.array(), h.tree, intArrayOf(STATUS_SUCCESS, STATUS_BUFFER_OVERFLOW))
            val dataOff = r.body.get(2).toInt() and 0xFF
            all.write(r.data, dataOff, r.body.getInt(4))
            if (r.status == STATUS_SUCCESS) return all.toByteArray()
        }
    }

    override fun close() {
        try { socket.close() } catch (e: IOException) { }
    }
}

/** Minimal DCE/RPC (connection-oriented, NDR) for listing shares with srvsvc NetrShareEnum. */
internal object Rpc {
    private fun uuid(s: String, major: Int, minor: Int): ByteArray {
        val h = s.replace("-", "")
        val raw = ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(ByteBuffer.wrap(raw, 0, 4).int).putShort(ByteBuffer.wrap(raw, 4, 2).short).putShort(ByteBuffer.wrap(raw, 6, 2).short)
        b.put(raw, 8, 8).putShort(major.toShort()).putShort(minor.toShort())
        return b.array()
    }

    private val SRVSVC = uuid("4b324fc8-1670-01d3-1278-5a47bf6ee188", 3, 0)
    private val NDR = uuid("8a885d04-1ceb-11c9-9fe8-08002b104860", 2, 0)

    private fun header(type: Int, length: Int, callId: Int) = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(5).put(0).put(type.toByte()).put(0x03).putInt(0x10).putShort(length.toShort()).putShort(0).putInt(callId)
    }

    fun bind(): ByteArray {
        val b = header(11, 16 + 12 + 4 + 40, 1)
        b.putShort(4280).putShort(4280).putInt(0)
        b.put(1).put(0).putShort(0) // one presentation context
        b.putShort(0).put(1).put(0).put(SRVSVC).put(NDR)
        return b.array()
    }

    fun checkBindAck(pdu: ByteArray) {
        if (pdu.size < 24 || pdu[2].toInt() != 12) throw SmbException("Couldn't list shared folders")
        val b = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN)
        val portLen = b.getShort(24).toInt() and 0xFFFF
        var i = 26 + portLen
        i = (i + 3) and 3.inv()
        if (i + 6 > pdu.size || b.getShort(i + 4).toInt() != 0) throw SmbException("Couldn't list shared folders")
    }

    fun netrShareEnumRequest(server: String): ByteArray {
        val stub = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        val name = "\\\\$server\u0000"
        stub.putInt(0x00020000).putInt(name.length).putInt(0).putInt(name.length).put(Ntlm.utf16(name))
        while (stub.position() % 4 != 0) stub.put(0)
        stub.putInt(1) // Level
        stub.putInt(1).putInt(0x00020004) // union switch, pointer to SHARE_INFO_1_CONTAINER
        stub.putInt(0).putInt(0) // EntriesRead, Buffer = null
        stub.putInt(-1) // PreferedMaximumLength
        stub.putInt(0) // ResumeHandle = null
        val body = stub.array().copyOf(stub.position())
        val b = header(0, 24 + body.size, 2)
        b.putInt(body.size).putShort(0).putShort(15).put(body) // alloc hint, context 0, opnum 15
        return b.array()
    }

    fun responseStub(pdu: ByteArray): ByteArray {
        if (pdu.size < 24) throw SmbException("Couldn't list shared folders")
        if (pdu[2].toInt() == 3) throw SmbException("Couldn't list shared folders (the computer refused)")
        if (pdu[2].toInt() != 2) throw SmbException("Couldn't list shared folders")
        val fragLen = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN).getShort(8).toInt() and 0xFFFF
        val authLen = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 0xFFFF
        return pdu.copyOfRange(24, minOf(fragLen, pdu.size) - (if (authLen > 0) authLen + 8 else 0))
    }

    fun parseShares(stub: ByteArray): List<String> {
        val b = ByteBuffer.wrap(stub).order(ByteOrder.LITTLE_ENDIAN)
        b.int; b.int // level, switch
        if (b.int == 0) return emptyList() // container pointer
        val count = b.int
        if (b.int == 0 || count == 0) return emptyList() // buffer pointer
        b.int // conformant max count
        val types = IntArray(count)
        val hasName = BooleanArray(count)
        val hasRemark = BooleanArray(count)
        for (i in 0 until count) {
            hasName[i] = b.int != 0
            types[i] = b.int
            hasRemark[i] = b.int != 0
        }
        fun string(): String {
            b.int; b.int
            val actual = b.int
            val bytes = ByteArray(actual * 2).also { b.get(it) }
            while (b.position() % 4 != 0) b.get()
            return String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
        }
        val out = mutableListOf<String>()
        for (i in 0 until count) {
            val name = if (hasName[i]) string() else ""
            if (hasRemark[i]) string()
            // Disk shares only, without the hidden admin shares (STYPE_SPECIAL) or names ending in $.
            if (name.isNotEmpty() && types[i] and 0xFFFF == 0 && types[i] and 0x80000000.toInt() == 0 && !name.endsWith("$")) out += name
        }
        return out.sortedBy { it.lowercase() }
    }
}
