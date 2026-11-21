package com.fakepod.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class MusicLibraryClient(context: Context,private val transferMode: (Boolean)->Unit = {},private val send: (ByteArray)->Boolean) {
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val dir=File(context.filesDir,"music_library_v1").apply { mkdirs() }
    private val browse=LibraryIndependentBrowse()
    private var catalogState: LibraryState?=null
    var state: LibraryState?=null;private set
    var index: LibraryIndex?=null;private set
    var rows: List<LibraryRow> = emptyList();private set
    var message="连接 Wi-Fi 后同步曲库";private set
    var connected=false;private set
    var listener: (() -> Unit)?=null
    private var opened=false
    private var seq=0L
    private var pending: LibraryAssembly?=null
    private var cachedKey=""
    private var rowsKey=""
    private var transfer: Transfer?=null
    private var workEpoch=0L
    private var loading=false
    private var opening=false
    private var autoSync=true
    // A cached preview is display-only until the current device catalog is validated.
    private var previewState: LibraryState?=null
    private var responseFailures=0
    private val recoverResponse=Runnable {
        if(opened && connected && autoSync && !busy)
            request(0,source=if(localOnly)0 else 255)
    }
    private fun resetRecovery(){responseFailures=0;main.removeCallbacks(recoverResponse);autoSync=true}
    var localOnly=false;private set
    private val browsePrefs=context.getSharedPreferences("music_library_browse",Context.MODE_PRIVATE)
    private val sourceChoice=LibrarySourceChoice(browsePrefs.getInt("manual_source",-1),browsePrefs.getInt("last_wifi_source",-1))
    private var pendingSource=255
    private var rememberedCacheKey=""
    val sourceMode get()=if(localOnly)0 else sourceChoice.source
    private var queuedSource: Int?=null
    private data class Transfer(val state: LibraryState,val section: Int,val out: ByteArrayOutputStream=ByteArrayOutputStream(),
        val sections: MutableList<ByteArray> = mutableListOf(),var record: Long=0,var offset: Long=0)
    private val timeout=Runnable {
        if(pending!=null){transferMode(false);pending=null;transfer=null;opening=false;autoSync=false
            message=if(localOnly)"蓝牙曲库请求超时，请确认已刷入配套固件，再点同步" else "曲库请求超时，请确认已刷入 R146 配套固件，再点同步";changed()}
    }
    // Coalesce rapid track notifications; the state reply remains authoritative.
    private val playbackSync=object: Runnable {override fun run(){
        if(!opened || !connected)return
        if(busy){main.postDelayed(this,100);return}
        request(0,source=if(localOnly)0 else 255)
    }}
    fun onPlaybackChanged() {
        main.post {main.removeCallbacks(playbackSync);if(opened && connected)main.postDelayed(playbackSync,80)}
    }
    private val poll=object: Runnable {override fun run(){
        if(opened && connected && pending==null && !loading && !opening)request(0,source=if(localOnly)0 else 255)
        if(opened)main.postDelayed(this,if(state?.ready==true)RemotePowerPolicy.LIBRARY_POLL_MS else 1000L)
    }}
    private fun changed(){listener?.invoke()}
    fun onTransportProgress() {
        if(pending!=null){main.removeCallbacks(timeout);main.postDelayed(timeout,6000)}
    }
    fun onTransportFailure() {
        if(pending==null)return
        transferMode(false);main.removeCallbacks(timeout);pending=null;transfer=null;opening=false
        queuedSource=null;autoSync=false
        message="曲库传输中断，已保留缓存；请重新打开曲库重试";changed()
    }
    fun onConnection(value: Boolean,bleLocalOnly: Boolean=false) {
        main.removeCallbacks(playbackSync);main.removeCallbacks(recoverResponse);responseFailures=0;previewState=null
        localOnly=value && bleLocalOnly
        if(localOnly && state?.source!=0){rows=emptyList();rowsKey=""}
        transferMode(false)
        connected=value;main.removeCallbacks(timeout);pending=null;transfer=null;opening=false;loading=false;workEpoch++
        queuedSource=null
        autoSync=value
        message=if(value)"${if(localOnly)"蓝牙" else "Wi-Fi"} 已连接，打开曲库即可校验缓存" else "离线缓存 · 连接后重新校验才可选歌"
        if(state?.source!=sourceMode && sourceMode!=255){rows=emptyList();rowsKey=""}
        changed();if(opened){if(value)request(0,source=if(localOnly)0 else 255) else loadLast()}
    }
    fun attach(callback: ()->Unit) {
        listener=callback;opened=true;resetRecovery();main.removeCallbacks(poll);main.postDelayed(poll,RemotePowerPolicy.LIBRARY_POLL_MS)
        if(connected)request(0,source=if(localOnly)0 else 255) else loadLast()
        changed()
    }
    fun detach(){
        transferMode(false);opened=false;listener=null
        main.removeCallbacks(poll);main.removeCallbacks(playbackSync);main.removeCallbacks(timeout);main.removeCallbacks(recoverResponse)
        pending=null;transfer=null;opening=false;loading=false;queuedSource=null;previewState=null;workEpoch++
    }
    val busy get()=pending!=null || transfer!=null || loading || opening
    val hasCurrentIndex get()=index!=null && cachedKey==state?.cacheKey && (sourceMode==255 || state?.source==sourceMode)
    val canOperate get()=connected && previewState==null && catalogState?.ready==true && catalogState?.cacheKey==cachedKey && hasCurrentIndex && !busy && state?.music==true && state?.ready==true && rowsKey==state?.viewKey && cachedKey==state?.cacheKey
    fun refresh(){resetRecovery();if(connected && pending==null && !loading)request(0,source=if(localOnly)0 else 255) else changed()}
    fun selectSource(source: Int) {
        if(localOnly && source!=0){message="蓝牙仅支持本地曲库，NAS 需连接 Wi-Fi";changed();return}
        if(source !in 0..1)return
        if(!connected){message="离线时保留上次确认的来源；连接设备后才能切换";changed();return}
        resetRecovery()
        if(busy){queuedSource=source;changed();return}
        request(9,source=source)
    }
    private fun confirmSource(source:Int) {
        if(localOnly || source !in 0..1)return
        sourceChoice.confirm(source)
        if(browsePrefs.getInt("last_wifi_source",-1)!=source)
            browsePrefs.edit().putInt("last_wifi_source",source).apply()
        if(state?.source!=source){previewState=null;rows=emptyList();rowsKey=""}
    }

    fun openDevice(source: Int) {
        if(localOnly && source!=0)return
        if(!connected || pending!=null || loading)return
        opening=true;autoSync=true;transfer=null;request(6,source=source)
    }
    private fun rebuildBrowse() {
        val raw=previewState?:catalogState?:state?:return
        val current=index.takeIf { cachedKey==raw.cacheKey }
        var next=browse.decorate(raw,current)
        rows=if(current!=null && cachedKey==raw.cacheKey)browse.rows(raw.source,current) else emptyList()
        next=next.copy(counts=next.counts.take(4)+rows.size)
        state=next;rowsKey=next.viewKey
        changed()
    }
    fun scope(value: Int){val s=state?:return;browse.scope(s.source,value);rebuildBrowse()}
    fun category(value: Int){val s=state?:return;browse.category(s.source,value);rebuildBrowse()}
    fun back(){val s=state?:return;browse.back(s.source);rebuildBrowse()}
    fun activate(row: LibraryRow){
        val s=state?:return
        if(!row.isTrack){index?.let{browse.open(s.source,row,it)};rebuildBrowse();return}
        if(canOperate){val (track,type,group)=browse.playArguments(s.source,row);request(8,track,type,group)}
    }
    private fun request(op: Int,a: Long=0,b: Long=0,c: Long=0,source: Int=state?.source?:255) {
        if(!connected || pending!=null)return
        seq=(seq+1) and 0xffffffffL;if(seq==0L)seq=1
        pending=LibraryAssembly(seq,op or 0x80);pendingSource=source
        if(!send(LibraryWire.request(op or 0x80,source,seq,state,a,b,c).also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(28,state?.identity?.toInt()?:0) })){transferMode(false);pending=null;opening=false;message="${if(localOnly)"蓝牙" else "Wi-Fi"} 指令未发送";changed();return}
        main.removeCallbacks(timeout);main.postDelayed(timeout,6000)
        // Routine state polls must not toggle the visible list into a busy state.
        if(op!=0)changed()
    }
    fun onFrame(frame: ByteArray) {
        val p=pending?:return
        if(frame.size<16 || LibraryWire.u32(frame,4)!=p.id)return
        var stage="assembly"
        try {
            val complete=p.accept(frame)?:return
            main.removeCallbacks(timeout);pending=null
            val (code,data)=complete
            val op=p.op and 0x7f
            val requestedSource=pendingSource
            if(!opened){transferMode(false);transfer=null;opening=false;return}
            // A user switch waits for the previous request, then requires its own ACK.
            val selected=queuedSource
            if(selected!=null){
                queuedSource=null;transferMode(false);transfer=null;opening=false;loading=false;workEpoch++
                request(9,source=selected);return
            }
            stage="state"
            val next=LibraryState.read(data)
            stage="source"
            if(requestedSource!=255 && next.source!=requestedSource)throw IllegalStateException("Source mismatch")
            if(code==0 && !localOnly && (op==9 || op==0 && requestedSource==255))confirmSource(next.source)
            if(op!=9 && requestedSource!=255 && next.source!=sourceMode){
                transferMode(false);transfer=null;opening=false;request(0,source=if(localOnly)0 else 255);return
            }
            if(op==9 && code!=0){
                opening=false;message="设备未确认来源切换，保留原曲库来源";changed();return
            }
            stage="apply-state"
            if(next.ready || previewState?.source!=next.source)previewState=null
            val old=catalogState;catalogState=next;state=browse.decorate(next,index.takeIf { cachedKey==next.cacheKey })
            if(code!=0) {
                transferMode(false);transfer=null;opening=false
                message=when(code){1->"设备正在加载曲库…";2->"设备列表已变化，重新同步";3->"请更新配套固件：当前需要独立曲库浏览协议";else->"设备未执行操作，请确认音乐模式和曲目格式"}
                if(code==2){rowsKey="";if(localOnly && next.source!=0)openDevice(0) else request(0,source=if(localOnly)0 else 255)}else {if(code==1)showWaitingCache(next);changed()}
                return
            }
            if(op==9 || op==0 && requestedSource==255){
                if(!next.ready){opening=false;if(op==9)openDevice(next.source) else request(0,source=next.source);showWaitingCache(next);return}
            }
            if(op==6){opening=false;if(!next.ready){showWaitingCache(next);changed();return}}
            if(old?.cacheKey!=next.cacheKey || old?.generation!=next.generation)rowsKey=""
            stage=if(op==1)"page" else "cache-sync"
            if(op==1){consumePage(data,next);return}
            sync(next)
        } catch(e: Exception){
            Log.w("FakePodLibrary","response failed stage=$stage op=${p.op and 127} request=${p.id} requestedSource=$pendingSource deviceSource=${catalogState?.source}",e)
            transferMode(false);main.removeCallbacks(timeout);pending=null;transfer=null;opening=false
            main.removeCallbacks(recoverResponse)
            responseFailures++
            autoSync=responseFailures<=2
            message=if(autoSync)"曲库响应异常，保留缓存并自动重试（$responseFailures/2）"
                else "曲库响应连续异常，已保留缓存；请点同步重试"
            if(autoSync)main.postDelayed(recoverResponse,300L*responseFailures)
            changed()
        }
    }
    private fun sync(s: LibraryState) {
        if(localOnly && s.source!=0){message="蓝牙仅支持本地曲库";changed();return}
        if(!autoSync){changed();return}
        if(!s.ready){showWaitingCache(s);changed();return}
        previewState=null
        if(!loading && pending==null && transfer==null && queuedSource!=null) {
            val selected=queuedSource!!;queuedSource=null;request(9,source=selected);return
        }
        if(cachedKey!=s.cacheKey){loadOrDownload(s);return}
        responseFailures=0;main.removeCallbacks(recoverResponse)
        rememberValidatedSource(s)
        rebuildBrowse()
        message="独立浏览 · ${rows.size} 项";changed()
    }
    private fun showWaitingCache(device: LibraryState) {
        if(device.ready || device.source!=sourceMode)return
        message="设备正在加载曲库…"
        if(previewState?.source==device.source && cachedKey==previewState?.cacheKey && index!=null){
            previewState=previewState!!.copy(ready=false,music=device.music,track=device.track,playingSource=device.playingSource)
            rebuildBrowse();return
        }
        if(loading)return
        loading=true;val epoch=workEpoch
        io.execute {
            val cached=runCatching {
                val hint=LibraryState.read(File(dir,"last-${device.source}.state").readBytes())
                require(hint.source==device.source)
                hint to readCache(hint)
            }.getOrNull()
            main.post {
                if(epoch!=workEpoch)return@post
                loading=false
                val current=catalogState
                if(!opened || !connected || current==null || current.source!=device.source || sourceMode!=device.source)return@post
                if(current.ready){sync(current);return@post}
                if(cached!=null){
                    index=cached.second;cachedKey=cached.first.cacheKey
                    previewState=cached.first.copy(ready=false,music=current.music,track=current.track,playingSource=current.playingSource)
                    rebuildBrowse()
                    message="已显示缓存 · 等待设备曲库就绪"
                }
                changed()
            }
        }
    }
    private fun loadOrDownload(s: LibraryState) {
        if(loading)return
        loading=true;message="校验本地曲库缓存…";changed();val epoch=workEpoch
        io.execute {
            val loaded=runCatching {readCache(s)}.getOrNull()
            main.post {
                if(epoch!=workEpoch)return@post
                loading=false
                if(!opened || !connected)return@post
                if(state?.cacheKey!=s.cacheKey){state?.let(::sync);return@post}
                if(loaded!=null){index=loaded;cachedKey=s.cacheKey;state?.let(::sync)}
                else {transfer=Transfer(s,0);page()}
            }
        }
    }
    private fun page() {
        val t=transfer?:return
        transferMode(true)
        message="同步曲库 ${t.section+1}/4 · ${t.record}/${t.state.counts[t.section]}"
        request(1,t.section.toLong(),t.record,t.offset)
    }
    private fun consumePage(data: ByteArray,s: LibraryState) {
        val t=transfer?:return
        if(s.cacheKey!=t.state.cacheKey || s.generation!=t.state.generation){
            transferMode(false);transfer=null;rowsKey="";sync(s);return
        }
        require(data.size>=61)
        val rec=LibraryWire.u32(data,52);val off=LibraryWire.u32(data,56);val done=data[60].toInt()!=0
        require(rec<=s.counts[t.section] && (rec>t.record || rec==t.record && off>=t.offset))
        require(done || rec>t.record || off>t.offset)
        require(!done || rec==s.counts[t.section].toLong() && off==0L)
        require(t.out.size()+data.size-61<=32*1024*1024)
        t.out.write(data,61,data.size-61);t.record=rec;t.offset=off
        if(!done){page();return}
        require(t.sections.sumOf { it.size.toLong() } + t.out.size() <= 32L*1024*1024)
        t.sections.add(t.out.toByteArray())
        if(t.section<3){transfer=Transfer(t.state,t.section+1,sections=t.sections);page();return}
        transferMode(false);transfer=null;loading=true;message="验证曲目与分类关联…";changed();val epoch=workEpoch
        io.execute {
            val result=runCatching {val parsed=LibraryIndex.parse(t.sections,t.state.counts);saveCache(t.state,t.sections);parsed}
            main.post {
                if(epoch!=workEpoch)return@post
                loading=false
                if(result.isSuccess && state?.cacheKey==s.cacheKey){index=result.getOrThrow();cachedKey=s.cacheKey;if(opened)state?.let(::sync)}
                else {autoSync=false;message="曲库保存或校验失败，保留旧缓存";changed()}
            }
        }
    }
    private fun file(s: LibraryState)=File(dir,"${s.cacheKey}.bin")
    private fun readCache(s: LibraryState): LibraryIndex {
        require(file(s).length() in 1..(32L*1024*1024+1024))
        DataInputStream(file(s).inputStream().buffered()).use { input ->
            require(input.readUTF()==s.cacheKey)
            val expected=ByteArray(32);input.readFully(expected)
            val payload=input.readBytes();require(payload.size<=32*1024*1024)
            require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(expected))
            val parts=DataInputStream(payload.inputStream()).use { p->
                List(4){val n=p.readInt();require(n in 0..32*1024*1024 && n<=p.available());ByteArray(n).also(p::readFully)}.also {require(p.available()==0)}
            }
            return LibraryIndex.parse(parts,s.counts)
        }
    }
    private fun saveCache(s: LibraryState,parts: List<ByteArray>) {
        val payload=ByteArrayOutputStream().also { out->DataOutputStream(out).use {d->parts.forEach {d.writeInt(it.size);d.write(it)}} }.toByteArray()
        val target=file(s);val temp=File(dir,target.name+".tmp")
        java.io.FileOutputStream(temp).use {f->val d=DataOutputStream(f);d.writeUTF(s.cacheKey);d.write(MessageDigest.getInstance("SHA-256").digest(payload));d.write(payload);d.flush();f.fd.sync()}
        // android.system.Os.rename is an atomic POSIX replacement on this private directory.
        android.system.Os.rename(temp.path,target.path)
        // Keep latest state pointer independently; it is only an offline hint, never authorization.
        saveLastState(s)
    }
    private fun rememberValidatedSource(s: LibraryState) {
        if(rememberedCacheKey==s.cacheKey)return
        rememberedCacheKey=s.cacheKey
        io.execute {runCatching {saveLastState(s)}}
    }
    private fun saveLastState(s: LibraryState) {
        val bytes=encodeState(s)
        // Separate pointers prevent a BLE-local visit from replacing the NAS cache hint.
        for(name in listOf("last-${s.source}.state","last.state")){
            val pointer=File(dir,"$name.tmp");pointer.writeBytes(bytes)
            android.system.Os.rename(pointer.path,File(dir,name).path)
        }
    }
    private fun encodeState(s: LibraryState): ByteArray = java.nio.ByteBuffer.allocate(52).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put(s.source.toByte());put(s.scope.toByte());put(s.mode.toByte());put(s.detailType.toByte());put(s.folderView.toByte());put(if(s.searching)1.toByte() else 0.toByte());put(1.toByte());put(0.toByte())
        putInt(s.generation.toInt());putInt(s.identity.toInt());putInt(s.revision.toInt());putInt(s.track.toInt());putInt(s.playingSource);putInt(s.detailGroup.toInt());s.counts.forEach {putInt(it)}
    }.array()
    private fun loadLast() {
        val target=sourceMode
        if(index!=null && hasCurrentIndex){rebuildBrowse();return}
        if(loading)return
        val epoch=workEpoch;loading=true
        io.execute {
            val cached=runCatching {
                val specific=File(dir,"last-$target.state")
                val pointer=if(target in 0..1 && specific.exists())specific else File(dir,"last.state")
                val s=LibraryState.read(pointer.readBytes())
                require(target==255 || s.source==target)
                s to readCache(s)
            }.getOrNull()
            main.post {
                if(epoch!=workEpoch)return@post
                loading=false
                if(connected || sourceMode!=target)return@post
                if(cached!=null){
                    catalogState=cached.first;state=cached.first
                    index=cached.second;cachedKey=cached.first.cacheKey;rebuildBrowse()
                }else if(state?.source!=target && target!=255){rows=emptyList();rowsKey=""}
                changed()
            }
        }
    }
}
