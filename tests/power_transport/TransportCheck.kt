package com.fakepod.remote
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
fun awaitCondition(f:()->Boolean) { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!f()){check(System.nanoTime()<end);Thread.sleep(10)} }
fun main() {
 val server=ServerSocket(46321,8,InetAddress.getByName("127.0.0.1"))
 val alive=AtomicBoolean(true);val sessions=AtomicInteger();val frames=AtomicInteger();val events=AtomicInteger()
 val sockets=CopyOnWriteArrayList<Socket>()
 val accept=Thread {while(alive.get())try {
  val s=server.accept();sockets.add(s);sessions.incrementAndGet()
  Thread {try {val out=s.getOutputStream();while(alive.get()&&!s.isClosed){out.write(byteArrayOf(0xA5.toByte(),2,1,1));out.flush();Thread.sleep(50)}}catch(_:Exception){}}.apply{isDaemon=true;start()}
 }catch(_:Exception){} }.apply{isDaemon=true;start()}
 val transport=FakePodWifiTransport(android.content.Context(),object:FakePodWifiTransport.Listener {
  override fun onWifiConnected(address:String){events.incrementAndGet()}
  override fun onWifiDisconnected(reason:String){}
  override fun onWifiEvent(payload:ByteArray){frames.incrementAndGet()}
 })
 try {
  transport.setDiscoveryEnabled(false);transport.start();Thread.sleep(150);check(sessions.get()==0)
  transport.setDiscoveryEnabled(true);awaitCondition{transport.isConnected()&&frames.get()>1}
  check(sessions.get()==1)
  // Disable discovery without tearing down the healthy existing TCP session.
  transport.setDiscoveryEnabled(false);Thread.sleep(200);check(transport.isConnected()&&sessions.get()==1)
  transport.stop();check(!transport.isConnected());check(!transport.send(byteArrayOf(2)))
  repeat(4){
   transport.setDiscoveryEnabled(true);transport.start();awaitCondition{transport.isConnected()};transport.stop()
  }
  val stopped=sessions.get();Thread.sleep(300);check(sessions.get()==stopped);check(!transport.isConnected())
  println("PASS: discovery paused with BLE ownership; healthy TCP retained; stop/restart sessions isolated; no background reconnect")
 }finally{transport.stop();alive.set(false);server.close();sockets.forEach{it.close()}}
}
