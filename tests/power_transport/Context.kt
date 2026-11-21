package android.content
open class Context {
 companion object {const val MODE_PRIVATE=0}
 val applicationContext get()=this
 val preferences=SharedPreferences()
 fun <T> getSystemService(c: Class<T>): T = c.cast(android.net.ConnectivityManager())
 fun getSharedPreferences(n: String,m: Int)=preferences
}
class SharedPreferences {
 private val data=java.util.concurrent.ConcurrentHashMap<String,String>()
 init {data["last_address"]="127.0.0.1"}
 fun getString(k: String,d: String?)=data[k]?:d
 fun edit()=Editor()
 inner class Editor {fun putString(k: String,v: String): Editor {data[k]=v;return this};fun apply(){}}
}
