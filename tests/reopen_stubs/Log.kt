package android.util
object Log {fun w(tag:String,message:String,error:Throwable):Int {println("$tag: $message (${error.javaClass.simpleName})");return 0}}
