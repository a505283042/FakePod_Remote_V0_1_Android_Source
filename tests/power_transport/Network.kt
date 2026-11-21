package android.net
import java.net.*
class Network {
 fun bindSocket(s: Socket){}
 fun bindSocket(s: DatagramSocket){}
}
class NetworkCapabilities {
 companion object {const val TRANSPORT_WIFI=1}
 fun hasTransport(t: Int)=true
}
class LinkAddress(val address: InetAddress,val prefixLength: Int)
class LinkProperties {val linkAddresses=emptyList<LinkAddress>()}
class ConnectivityManager {
 val activeNetwork=Network()
 val allNetworks get()=arrayOf(activeNetwork)
 fun getNetworkCapabilities(n: Network)=NetworkCapabilities()
 fun getLinkProperties(n: Network)=LinkProperties()
}
