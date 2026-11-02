package app.lakuri.tv.smb

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The only gate for network connections: the app may talk to computers on the home network and
 * nothing else. Every connection must use an address returned by [resolve].
 */
object LocalNetwork {
    /** True for private LAN addresses: 10/8, 172.16/12, 192.168/16, link-local, and IPv6 unique-local (fc00::/7). */
    fun isLocal(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> a.isSiteLocalAddress || a.isLinkLocalAddress
        is Inet6Address -> a.isSiteLocalAddress || a.isLinkLocalAddress || (a.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    /** Resolves [host] (an IP address or a name) to a home-network address, or throws if it isn't one. */
    fun resolve(host: String): InetAddress {
        val name = host.trim().removePrefix("\\\\").removePrefix("smb://").trimEnd('/', '\\')
        if (name.isEmpty()) throw SmbException("Enter the computer's address, for example 192.168.1.50")
        val all = try {
            InetAddress.getAllByName(name)
        } catch (e: UnknownHostException) {
            throw SmbException("Couldn't find \"$name\" on the network. Try its IP address, for example 192.168.1.50")
        }
        return all.firstOrNull(::isLocal)
            ?: throw SmbException("$name is not on your home network. Only local addresses such as 192.168.x.x or 10.x.x.x are allowed.")
    }

    /**
     * Computers with file sharing (port 445) open on the TV's own subnet. Only the TV's local
     * IPv4 networks are checked, at most the 254 neighbours of each.
     */
    fun findSmbServers(port: Int = 445, timeoutMs: Int = 400): List<InetAddress> {
        val candidates = LinkedHashSet<InetAddress>()
        for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                val own = ia.address as? Inet4Address ?: continue
                if (!isLocal(own)) continue
                val base = own.address.copyOf().also { it[3] = 0 }
                for (i in 1..254) {
                    val a = InetAddress.getByAddress(base.copyOf().also { it[3] = i.toByte() })
                    if (a != own) candidates += a
                }
            }
        }
        val pool = Executors.newFixedThreadPool(48)
        val found = candidates.map { a ->
            pool.submit<InetAddress?> {
                try {
                    Socket().use { it.connect(InetSocketAddress(a, port), timeoutMs); a }
                } catch (e: Exception) {
                    null
                }
            }
        }.mapNotNull { it.get() }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.SECONDS)
        return found
    }
}
