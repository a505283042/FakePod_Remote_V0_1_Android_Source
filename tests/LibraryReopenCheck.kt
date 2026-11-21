package com.fakepod.remote
import android.content.Context
import android.os.Handler
import java.io.*
import java.nio.*
import java.nio.file.Files
import java.security.MessageDigest

private fun state(source:Int)=LibraryState(source,0,0,0,0,false,true,true,1,100L+source,1,0,source,0xffffffffL,listOf(1,1,1,1,1))
private fun stateBytes(s:LibraryState):ByteArray=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
 put(s.source.toByte());put(0.toByte());put(0.toByte());put(0.toByte());put(0.toByte());put(0.toByte());put(if(s.ready)1.toByte() else 0.toByte());put(1.toByte())
 putInt(s.generation.toInt());putInt(s.identity.toInt());putInt(1);putInt(0);putInt(s.playingSource);putInt(-1);s.counts.forEach{putInt(it)}
}.array()
private fun writeCache(root:File,s:LibraryState){
 fun section(track:Boolean)=ByteArrayOutputStream().also { out->
  fun n(v:Int){repeat(4){out.write((v ushr (8*it)) and 255)}}
  fun str(v:String){val b=v.toByteArray();n(b.size);out.write(b)}
  n(0)
  if(track){str(if(s.source==0)"LOCAL" else "NAS");str("Artist");str("Album");str(if(s.source==0)"/sdcard/MUSIC/a.flac" else "/a.flac");n(16);n(2024);n(0);n(1)}
  else {str("Group");str("");n(2020);n(1);n(0)}
 }.toByteArray()
 val parts=listOf(section(true),section(false),section(false),section(false))
 val payload=ByteArrayOutputStream().also {out->DataOutputStream(out).use{d->parts.forEach{d.writeInt(it.size);d.write(it)}}}.toByteArray()
 val dir=File(root,"music_library_v1").apply{mkdirs()}
 DataOutputStream(File(dir,"${s.cacheKey}.bin").outputStream()).use{it.writeUTF(s.cacheKey);it.write(MessageDigest.getInstance("SHA-256").digest(payload));it.write(payload)}
}
private fun reply(client:MusicLibraryClient,q:ByteArray,s:LibraryState,code:Int=0){
 val data=stateBytes(s)
 val frame=ByteBuffer.allocate(16+data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
  put(6.toByte());put(1.toByte());put(code.toByte());put(q[2]);putInt(LibraryWire.u32(q,4).toInt())
  putShort(data.size.toShort());putShort(0);putInt(LibraryWire.crc(data).toInt());put(data)
 }.array()
 client.onFrame(frame)
}
private fun await(label:String,predicate:()->Boolean){val end=System.nanoTime()+3_000_000_000;while(true){Handler.drain();if(predicate())return;check(System.nanoTime()<end){label};Thread.sleep(5)}}
private fun runChecks(){
 val root=Files.createTempDirectory("fakepod-reopen").toFile();val context=Context(root)
 val local=state(0);val nas=state(1);writeCache(root,local);writeCache(root,nas)
 // Deliberately leave a legacy global pointer on Local. NAS is an existing cache hit.
 File(root,"music_library_v1/last.state").writeBytes(stateBytes(local))
 val wire=ArrayDeque<ByteArray>()
 val client=MusicLibraryClient(context,{}, {wire.addLast(it);true})
 client.onConnection(true);client.attach{}
 check((wire.last()[3].toInt() and 255)==255)
 reply(client,wire.removeFirst(),nas) // Initial source confirmation.
 await("NAS cache hit") {client.rows.firstOrNull()?.title=="NAS" && client.canOperate}
 await("NAS source pointer") {File(root,"music_library_v1/last-1.state").exists()}
 check(context.getSharedPreferences("music_library_browse",0).getInt("last_wifi_source",-1)==1)
 // A failed or unacknowledged switch must never replace the confirmed NAS source.
 client.selectSource(0);check(client.sourceMode==1)
 reply(client,wire.removeFirst(),local,4);check(client.sourceMode==1 && client.state?.source==1)
 client.selectSource(0);val stale=wire.removeFirst();client.detach();client.attach{}
 reply(client,stale,local);check(client.sourceMode==1)
 check((wire.first()[3].toInt() and 255)==255)
 reply(client,wire.removeFirst(),nas);await("NAS reattach") {client.rows.firstOrNull()?.title=="NAS" && client.canOperate}
 // A successful APP switch and a later device switch both become authoritative.
 client.selectSource(0);check(client.sourceMode==1)
 reply(client,wire.removeFirst(),local);await("confirmed Local") {client.rows.firstOrNull()?.title=="LOCAL" && client.sourceMode==0}
 client.detach();client.attach{};reply(client,wire.removeFirst(),nas.copy(ready=false))
 check(client.sourceMode==1 && !client.canOperate)
 check((wire.first()[2].toInt() and 127)==0 && (wire.first()[3].toInt() and 255)==1)
 reply(client,wire.removeFirst(),nas.copy(ready=false),1)
 check(client.sourceMode==1 && client.rows.none{it.title=="LOCAL"} && !client.canOperate) // Loading never falls back to Local.
 client.detach();client.attach{};reply(client,wire.removeFirst(),nas)
 await("device changed to NAS") {client.rows.firstOrNull()?.title=="NAS" && client.sourceMode==1 && client.canOperate}
 // BLE temporarily forces Local, without changing the saved Wi-Fi choice.
 client.onConnection(true,true);reply(client,wire.removeFirst(),local)
 await("BLE Local") {client.rows.firstOrNull()?.title=="LOCAL" && client.sourceMode==0}
 client.onConnection(true,false);reply(client,wire.removeFirst(),nas)
 await("Wi-Fi returns to NAS") {client.rows.firstOrNull()?.title=="NAS" && client.sourceMode==1}
 client.detach()
 // Simulate another process using the same durable settings/cache. A newer global
 // Local hint must not displace the independently saved NAS source pointer.
 File(root,"music_library_v1/last.state").writeBytes(stateBytes(local))
 val reopened=MusicLibraryClient(context,{}, {true});check(reopened.sourceMode==1);reopened.attach{}
 await("offline NAS pointer") {reopened.rows.firstOrNull()?.title=="NAS"}
 check(reopened.state?.source==1);reopened.detach()
 println("PASS: real client confirmed switches, failed ACK, authoritative device change, late reply, BLE/Wi-Fi, saved NAS and offline reopen")
}
fun main(){try{runChecks();kotlin.system.exitProcess(0)}catch(t:Throwable){t.printStackTrace();kotlin.system.exitProcess(1)}}
