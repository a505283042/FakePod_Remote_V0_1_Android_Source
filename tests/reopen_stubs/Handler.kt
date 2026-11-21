package android.os
import java.util.concurrent.ConcurrentLinkedQueue
class Looper {companion object {fun getMainLooper()=Looper()}}
class Handler(looper:Looper){
 companion object {
  private val queue=ConcurrentLinkedQueue<Runnable>()
  fun drain(){repeat(1000){val task=queue.poll()?:return;task.run()};error("Unbounded main work")}
 }
 fun post(task:Runnable):Boolean {queue.add(task);return true}
 fun postDelayed(task:Runnable,delay:Long)=true // Timers stay pending; tests drive requests explicitly.
 fun removeCallbacks(task:Runnable){queue.removeIf {it===task}}
}
