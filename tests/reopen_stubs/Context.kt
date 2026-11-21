package android.content
import java.io.File
import java.util.concurrent.ConcurrentHashMap
open class Context(val filesDir:File) {
 companion object {const val MODE_PRIVATE=0;private val stores=ConcurrentHashMap<String,SharedPreferences>()}
 fun getSharedPreferences(name:String,mode:Int):SharedPreferences=stores.getOrPut("${filesDir.absolutePath}/$name"){SharedPreferences()}
}
class SharedPreferences {
 private val values=ConcurrentHashMap<String,Int>()
 fun getInt(key:String,fallback:Int)=values[key]?:fallback
 fun edit()=Editor()
 inner class Editor {fun putInt(key:String,value:Int):Editor {values[key]=value;return this};fun apply(){}}
}
