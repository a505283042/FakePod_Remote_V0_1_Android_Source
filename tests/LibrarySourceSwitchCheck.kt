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
 val root=Files.createTempDirectory("fakepod-switch").toFile(); val context=Context(root)
 val local=state(0); val nas=state(1);writeCache(root,local);writeCache(root,nas)
 val dir=File(root,"music_library_v1")
 File(dir,"last-0.state").writeBytes(stateBytes(local));File(dir,"last-1.state").writeBytes(stateBytes(nas))
 val wire=ArrayDeque<ByteArray>();val client=MusicLibraryClient(context,{}, {wire.addLast(it);true})
 fun corrupt(q:ByteArray){
  val data=stateBytes(nas)
  val bad=ByteBuffer.allocate(16+data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
   put(6.toByte());put(1.toByte());put(0.toByte());put(q[2]);putInt(LibraryWire.u32(q,4).toInt())
   putShort(data.size.toShort());putShort(0);putInt((LibraryWire.crc(data) xor 1L).toInt());put(data)
  }.array();client.onFrame(bad)
 }
 fun recovery()=(client.javaClass.getDeclaredField("recoverResponse").apply{isAccessible=true}.get(client) as Runnable).run()
 client.onConnection(true);client.attach{}
 reply(client,wire.removeFirst(),local)
 await("Local cache"){client.rows.firstOrNull()?.title=="LOCAL" && client.canOperate}
 client.selectSource(1)
 val waiting=nas.copy(ready=false,generation=0,identity=0,counts=List(5){0})
 reply(client,wire.removeFirst(),waiting)
 await("cached NAS preview"){client.rows.firstOrNull()?.title=="NAS" && client.busy}
 check(client.sourceMode==1 && client.state?.ready==false && !client.canOperate)
 // A preview cannot authorize a play or mutate the source.
 val requests=wire.size;client.activate(client.rows.first());check(wire.size==requests)
 check((wire.first()[2].toInt() and 127)==6)
 reply(client,wire.removeFirst(),waiting,1)
 check(client.rows.first().title=="NAS" && !client.canOperate)
 client.refresh();reply(client,wire.removeFirst(),nas)
 await("NAS ready"){client.rows.firstOrNull()?.title=="NAS" && client.canOperate}
 check(wire.none{(it[2].toInt() and 127)==1})
 println("PASS: source confirmed -> cached target preview while loading -> validated without catalog download; no play from preview")
 client.selectSource(0);reply(client,wire.removeFirst(),local)
 await("Back to Local"){client.rows.firstOrNull()?.title=="LOCAL" && client.canOperate}
 client.selectSource(1);corrupt(wire.removeFirst())
 check(client.message.contains("自动重试"))
 recovery();check((wire.first()[2].toInt() and 127)==0 && (wire.first()[3].toInt() and 255)==255)
 reply(client,wire.removeFirst(),nas)
 await("automatic recovery"){client.rows.firstOrNull()?.title=="NAS" && client.canOperate}
 println("PASS: malformed ACK recovers through read-only current-source probe without reopening or reissuing source/play")
 client.refresh();corrupt(wire.removeFirst());recovery();corrupt(wire.removeFirst());recovery();corrupt(wire.removeFirst())
 check(client.message.contains("连续异常"));recovery();check(wire.isEmpty())
 client.refresh();reply(client,wire.removeFirst(),nas)
 await("manual retry resets budget"){client.canOperate}
 println("PASS: at most two automatic retries; explicit refresh recovers")
 client.refresh();corrupt(wire.removeFirst());client.detach();recovery();check(wire.isEmpty())
 client.attach{};reply(client,wire.removeFirst(),nas)
 await("reopen"){client.canOperate}
 client.onConnection(true,true);reply(client,wire.removeFirst(),local)
 await("BLE local only"){client.sourceMode==0 && client.rows.firstOrNull()?.title=="LOCAL" && client.canOperate}
 client.selectSource(1);check(wire.isEmpty())
 println("PASS: detach cancels recovery; reopen and BLE Local isolation preserved")
 client.onConnection(true,false);reply(client,wire.removeFirst(),nas.copy(identity=999))
 await("changed identity downloads instead of trusting preview"){wire.isNotEmpty()}
 check(!client.canOperate && (wire.first()[2].toInt() and 127)==1)
 check(File(dir,"${nas.cacheKey}.bin").exists())
 println("PASS: changed catalog identity requires download; previous cached catalog remains intact")
 client.detach()
}

fun main(){try{runChecks();kotlin.system.exitProcess(0)}catch(t:Throwable){t.printStackTrace();kotlin.system.exitProcess(1)}}
