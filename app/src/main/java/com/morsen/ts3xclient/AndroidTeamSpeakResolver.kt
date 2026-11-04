package com.morsen.ts3xclient

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import com.morsen.ts3xclient.protocol.TeamSpeakAddressResolver
import com.morsen.ts3xclient.protocol.TeamSpeakEndpointCache
import org.xbill.DNS.Lookup
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory

/** Use the active Android network for name discovery, including VPN/Private DNS on API 29+. */
internal class AndroidTeamSpeakResolver(context: Context) : TeamSpeakAddressResolver.Backend {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network: Network? = connectivity?.activeNetwork
    // A VPN switch, DNS change or Private DNS change must not reuse another route's answer.
    private val networkKey = network?.let { active ->
        val links = connectivity?.getLinkProperties(active)
        val privateDns = if (Build.VERSION.SDK_INT >= 28) {
            "${links?.isPrivateDnsActive}:${links?.privateDnsServerName}"
        } else ""
        "${active.networkHandle}:${links?.dnsServers}:$privateDns"
    }

    companion object {
        private val endpoints = TeamSpeakEndpointCache(128, 60_000L)
    }

    fun resolve(host: String, port: Int): TeamSpeakAddressResolver.Resolved {
        networkKey?.let { key ->
            endpoints.get(key, host, port)?.let { cached ->
                ConnectionDiagnostics.event("address.cache.hit", "port=$port method=${cached.method}")
                return cached
            }
        }
        return TeamSpeakAddressResolver(this).resolve(host, port).also { resolved ->
            networkKey?.let { endpoints.put(it, host, port, resolved) }
        }
    }

    fun invalidate(host: String, port: Int) {
        networkKey?.let { endpoints.invalidate(it, host, port) }
    }

    fun rememberSuccessful(host: String, port: Int, resolved: TeamSpeakAddressResolver.Resolved,
                           endpoint: InetSocketAddress) {
        networkKey?.let { endpoints.put(it, host, port, resolved.preferring(endpoint)) }
    }

    override fun addresses(host: String): List<InetAddress> =
        (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()

    override fun services(name: String): List<TeamSpeakAddressResolver.Service> {
        val records = if (Build.VERSION.SDK_INT >= 29) {
            val message = Message(nativeSrvQuery(name))
            if (message.header.rcode != Rcode.NOERROR) return emptyList()
            message.getSectionArray(Section.ANSWER).toList()
        } else {
            // API 26-28 has no public generic DNS query API. Use this network's
            // advertised DNS servers, without substituting a public resolver.
            val servers = network?.let { connectivity?.getLinkProperties(it)?.dnsServers }.orEmpty()
            var lastError: IOException? = null
            var answer: List<org.xbill.DNS.Record>? = null
            for (server in servers.take(2)) {
                try {
                    val resolver = SimpleResolver(server.hostAddress)
                    resolver.setTimeout(1)
                    val lookup = Lookup("$name.", Type.SRV)
                    lookup.setResolver(resolver)
                    lookup.setCache(null)
                    val found = lookup.run()
                    if (lookup.result == Lookup.TRY_AGAIN) throw IOException("SRV query timed out")
                    answer = found?.toList().orEmpty()
                    break
                } catch (error: IOException) {
                    lastError = error
                }
            }
            answer ?: throw (lastError ?: IOException("Active network DNS servers unavailable"))
        }
        return records.filterIsInstance<SRVRecord>().map {
            TeamSpeakAddressResolver.Service(it.priority, it.weight, it.port, it.target.toString())
        }
    }

    @TargetApi(29)
    private fun nativeSrvQuery(name: String): ByteArray {
        val pending = CompletableFuture<ByteArray>()
        val cancellation = CancellationSignal()
        // The raw DNS-message overload supports SRV; the hostname overload's
        // SDK annotation only permits A/AAAA query constants.
        val question = org.xbill.DNS.Record.newRecord(
            org.xbill.DNS.Name.fromString("$name."), Type.SRV, DnsResolver.CLASS_IN)
        val query = Message.newQuery(question).toWire()
        DnsResolver.getInstance().rawQuery(
            network, query, DnsResolver.FLAG_EMPTY,
            Executor { it.run() }, cancellation,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    pending.complete(answer)
                }
                override fun onError(error: DnsResolver.DnsException) {
                    pending.completeExceptionally(error)
                }
            }
        )
        try {
            return pending.get(1500, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("SRV query interrupted", error)
        } catch (error: ExecutionException) {
            throw IOException("SRV query failed", error.cause)
        } catch (error: TimeoutException) {
            throw IOException("SRV query timed out", error)
        } finally {
            cancellation.cancel()
        }
    }

    override fun tsdns(server: InetSocketAddress, query: String): String =
        TeamSpeakAddressResolver.queryTsdns(network?.socketFactory ?: SocketFactory.getDefault(), server, query)
}
