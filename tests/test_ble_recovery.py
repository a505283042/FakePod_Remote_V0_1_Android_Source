"""Host regression of actual BLE teardown methods + real TCP transport, not Android GATT hardware."""
from pathlib import Path
import subprocess,tempfile,sys
root=Path(__file__).resolve().parents[1]
build=Path(sys.argv[1] if len(sys.argv)>1 else '.build_tools').resolve()
s=(root/'app/src/main/java/com/fakepod/remote/FakePodBleClient.kt').read_text()
def method(sig):
 a=s.index(sig);b=s.index('{',a)+1;depth=1
 while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
 return s[a:b]
head='''package com.fakepod.remote
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
class HandlerStub {fun post(block:()->Unit){block()};fun removeCallbacks(task:Runnable){}}
class AssemblerStub {fun reset(){}}
class LibraryStub {fun onConnection(value:Boolean){}}
class RecoveryHarness(val wifiTransport:FakePodWifiTransport) {
 var remoteActive=false;var wifiReady=false
 private val mainHandler=HandlerStub();private val bleLibraryPump=Runnable{};private val reconnectRunnable=Runnable{};private val scanTimeout=Runnable{}
 private val bleLibraryFrames=mutableListOf<ByteArray>();private val bleLibraryAssembler=AssemblerStub();private val musicLibrary=LibraryStub()
 private var requestedBlePriority:Int?=null;private var bleTransferActive=false;private var commandCharacteristic:Any?=null;private var eventCharacteristic:Any?=null
 private var writeInFlight=false;private var lastCommand:Int?=null;private var lastVolumeTarget:Int?=null;private val wifiProvisionFrames=mutableListOf<ByteArray>()
 private fun resetMetadataAssembly(){}
 private var reconnectEnabled=true;private var scanning=false;private var pendingDevice:Any?=null
 private val prefs=android.content.SharedPreferences();private val PREF_ADDRESS="address"
 enum class State{DISCONNECTED};data class Snapshot(val address:String?=null);private val snapshot=Snapshot()
 private fun publish(state:State,message:String,address:String?){}
 private fun closeGatt(){resetGattSession()}
 fun adapterOff(){handleBluetoothAdapterOff()}
 fun sessionFailure(){resetGattSession()}
'''
body=method('private fun resetGattSession()')+'\n'+method('private fun handleBluetoothAdapterOff()')+'\n}\n'
test='''
private fun awaitCondition(label:String,predicate:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!predicate()){check(System.nanoTime()<end){label};Thread.sleep(10)}}
fun main(){
 val server=ServerSocket(46321,8,InetAddress.getByName("127.0.0.1"));val alive=AtomicBoolean(true);val sessions=AtomicInteger();val frames=AtomicInteger();val sockets=CopyOnWriteArrayList<Socket>()
 Thread {while(alive.get())try {val socket=server.accept();sockets.add(socket);sessions.incrementAndGet()
  Thread {try {val out=socket.getOutputStream();while(alive.get()&&!socket.isClosed){out.write(byteArrayOf(0xA5.toByte(),2,1,1));out.flush();Thread.sleep(50)}}catch(_:Exception){}}.apply{isDaemon=true;start()}
 }catch(_:Exception){} }.apply{isDaemon=true;start()}
 val transport=FakePodWifiTransport(android.content.Context(),object:FakePodWifiTransport.Listener {
  override fun onWifiConnected(address:String){}
  override fun onWifiDisconnected(reason:String){}
  override fun onWifiEvent(payload:ByteArray){frames.incrementAndGet()}
 })
 val harness=RecoveryHarness(transport)
 try {
  transport.setDiscoveryEnabled(false)
  harness.adapterOff();Thread.sleep(150);check(sessions.get()==0&&!transport.isConnected())
  harness.remoteActive=true;harness.adapterOff();awaitCondition("adapter-off did not resume paused Wi-Fi"){transport.isConnected()&&frames.get()>1}
  check(sessions.get()==1)
  harness.wifiReady=true;val before=frames.get();harness.sessionFailure();awaitCondition("Wi-Fi stopped during BLE teardown"){frames.get()>before+1};check(sessions.get()==1&&transport.isConnected())
  transport.stop();transport.setDiscoveryEnabled(false);harness.sessionFailure();Thread.sleep(150);check(sessions.get()==1&&!transport.isConnected())
  harness.wifiReady=false;harness.sessionFailure();awaitCondition("GATT error did not resume Wi-Fi"){transport.isConnected()&&sessions.get()==2}
  harness.remoteActive=false;transport.stop();harness.sessionFailure();Thread.sleep(150);check(!transport.isConnected()&&sessions.get()==2)
  println("PASS: production adapter-off/GATT-reset restore paused Wi-Fi; healthy Wi-Fi retained; no Wi-Fi takeover or background reconnect")
 } finally {transport.stop();alive.set(false);server.close();sockets.forEach{it.close()}}
}
'''
# Both disconnect and error paths reach the tested session reset, while READY still pauses discovery.
assert 'wifiTransport.setDiscoveryEnabled(false)' in method('private fun markReady(')
assert 'resetGattSession()' in s[s.index('newState == BluetoothProfile.STATE_DISCONNECTED'):]
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'RecoveryCheck.kt';p.write_text(head+body+test)
 sources=[str(root/'tests/power_transport'/n) for n in ['Context.kt','Log.kt','Network.kt','SystemClock.kt']]
 sources += [str(root/'app/src/main/java/com/fakepod/remote'/n) for n in ['RemotePowerPolicy.kt','WifiFrameReader.kt','FakePodWifiTransport.kt']]+[str(p)]
 jar=Path(d)/'test.jar'
 subprocess.run(['java','-cp',str(build/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(build/'kotlin-stdlib.jar')+':'+str(build/'annotations.jar'),'-d',str(jar),*sources],check=True)
 subprocess.run(['java','-cp',str(jar)+':'+str(build/'kotlin-stdlib.jar'),'com.fakepod.remote.RecoveryCheckKt'],check=True)
