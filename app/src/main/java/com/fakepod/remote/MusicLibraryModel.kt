package com.fakepod.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

/** Versioned wire model. No Android dependencies: also used by host validation. */
internal data class LibraryState(
    val source: Int, val scope: Int, val mode: Int, val detailType: Int,
    val folderView: Int, val searching: Boolean, val ready: Boolean, val music: Boolean,
    val generation: Long, val identity: Long, val revision: Long, val track: Long,
    val playingSource: Int, val detailGroup: Long, val counts: List<Int>
) {
    val cacheKey get() = "v1-$source-${identity.toString(16)}-${counts.take(4).joinToString("-")}"
    val viewKey get() = "$cacheKey-$generation-$revision"
    companion object {
        fun read(bytes: ByteArray): LibraryState {
            require(bytes.size >= 52)
            val r = LibraryReader(bytes)
            val source=r.u8(); val scope=r.u8(); val mode=r.u8(); val type=r.u8(); val folder=r.u8()
            val searching=r.u8()!=0; val ready=r.u8()!=0; val music=r.u8()!=0
            val gen=r.u32(); val id=r.u32(); val rev=r.u32(); val track=r.u32()
            val playing=r.int(); val group=r.u32(); val counts=List(5) { r.count(200_000) }
            require(source in 0..1 && scope in 0..2 && mode in 0..4 && type in 0..3 && folder in 0..3)
            return LibraryState(source,scope,mode,type,folder,searching,ready,music,gen,id,rev,track,playing,group,counts)
        }
    }
}
internal class LibraryReader(private val data: ByteArray) {
    var offset=0
    fun u8(): Int { require(offset<data.size); return data[offset++].toInt() and 255 }
    fun u32(): Long = (0..3).fold(0L) { v,i -> v or (u8().toLong() shl (8*i)) }
    fun int(): Int { val n=u32(); require(n<=Int.MAX_VALUE); return n.toInt() }
    fun count(max: Int): Int = int().also { require(it<=max) }
    fun str(): String {
        val n=count(1_048_576); require(n<=data.size-offset)
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val s=decoder.decode(ByteBuffer.wrap(data,offset,n)).toString(); offset+=n; return s
    }
    fun end() { require(offset==data.size) }
}
internal data class LibraryTrack(val id: Int,val title: String,val artist: String,val album: String,val path: String,
    val flags: Long,val release: Int,val original: Int,val format: Int) {
    val years get() = listOfNotNull(if(flags and 16L!=0L)release.toString() else null,if(flags and 32L!=0L)original.toString() else null).joinToString(" ")
}
internal data class LibraryGroup(val id: Int,val title: String,val artist: String,val decade: Int,val tracks: List<Int>)
internal data class LibraryRow(val display: Int,val action: Int,val type: Int,val position: Long,val group: Long,
    val track: Long,val title: String,val subtitle: String,val count: Int) {
    val isTrack get()=track!=0xffffffffL
}
internal data class LibraryIndex(val tracks: List<LibraryTrack>,val groups: List<List<LibraryGroup>>) {
    companion object {
        fun parse(sections: List<ByteArray>, counts: List<Int>): LibraryIndex {
            require(sections.size==4 && counts.size>=4)
            val r=LibraryReader(sections[0])
            val tracks=List(counts[0]) { i ->
                require(r.int()==i)
                LibraryTrack(i,r.str(),r.str(),r.str(),r.str(),r.u32(),r.count(9999),r.count(9999),r.count(255))
            }; r.end()
            val groups=(1..3).map { section ->
                val g=LibraryReader(sections[section])
                List(counts[section]) { i ->
                    require(g.int()==i)
                    val title=g.str(); val artist=g.str();val decade=g.count(9999);val n=g.count(tracks.size)
                    val members=List(n) { g.int().also { require(it in tracks.indices) } }
                    require(members.distinct().size==members.size)
                    LibraryGroup(i,title,artist,decade,members)
                }.also { g.end() }
            }
            return LibraryIndex(tracks,groups)
        }
        fun compactRows(bytes: ByteArray,s: LibraryState,index: LibraryIndex): List<LibraryRow> {
            val r=LibraryReader(bytes)
            val detail=s.mode==4
            return List(s.counts[4]) { i ->
                val pos=r.u32();val id=r.u32();require(id<index.tracks.size)
                val t=index.tracks[id.toInt()]
                LibraryRow(i,if(detail)6 else if(s.scope!=0)2 else 1,
                    if(detail)s.detailType else 0,pos,if(detail)s.detailGroup else 0xffffffffL,
                    id,t.title,t.artist,0)
            }.also {r.end()}
        }
        // Only derive views whose exact order is already exported by the device.
        fun cachedRows(s: LibraryState,index: LibraryIndex): List<LibraryRow>? {
            if(s.scope!=0 || s.searching)return null
            if(s.mode in 1..3) {
                return index.groups[s.mode-1].map {g->LibraryRow(g.id,s.mode+2,s.mode,0,g.id.toLong(),0xffffffffL,g.title,if(s.mode==2)g.artist else "",g.tracks.size)}
                    .takeIf {it.size==s.counts[4]}
            }
            val ids=if(s.mode==0)index.tracks.indices.toList() else if(s.mode==4 && s.detailType in 1..3)
                index.groups[s.detailType-1].getOrNull(s.detailGroup.toInt())?.tracks ?: return null else return null
            return ids.mapIndexed {i,id->val t=index.tracks[id];LibraryRow(i,if(s.mode==4)6 else 1,
                if(s.mode==4)s.detailType else 0,i.toLong(),if(s.mode==4)s.detailGroup else 0xffffffffL,id.toLong(),t.title,t.artist,0)}
                .takeIf {it.size==s.counts[4]}
        }
        fun rows(bytes: ByteArray,count: Int,tracks: Int): List<LibraryRow> {
            val r=LibraryReader(bytes)
            return List(count) { i ->
                require(r.int()==i)
                val a=r.u8();val type=r.u8();val pos=r.u32();val group=r.u32();val track=r.u32()
                require(a in 1..10 && type in 0..3 && (track==0xffffffffL || track<tracks))
                LibraryRow(i,a,type,pos,group,track,r.str(),r.str(),r.count(200_000))
            }.also { r.end() }
        }
    }
    // The catalog is immutable. Scan membership once per selected track, then reuse
    // the same snapshot for row rendering, parent controls and scroll positioning.
    private var membershipTrack = -1L
    private var membershipGroups: List<Set<Int>> = List(3) { emptySet() }
    fun groupsForTrack(track: Long): List<Set<Int>> {
        if(track !in 0L until tracks.size.toLong())return List(3) { emptySet() }
        if(track!=membershipTrack) {
            membershipGroups=groups.map { section ->
                section.asSequence().filter { track.toInt() in it.tracks }.map { it.id }.toSet()
            }
            membershipTrack=track
        }
        return membershipGroups
    }
    fun filterRows(rows: List<LibraryRow>,query: String,field: Int): List<LibraryRow> {
        val normalized=query.trim().lowercase(Locale.ROOT)
        if(normalized.isEmpty())return rows
        return rows.filter { matchesNormalized(it,normalized,field) }
    }
    fun matches(row: LibraryRow,query: String,field: Int): Boolean =
        matchesNormalized(row,query.trim().lowercase(Locale.ROOT),field)
    private fun matchesNormalized(row: LibraryRow,q: String,field: Int): Boolean {
        if(q.isEmpty())return true
        if(row.isTrack)return tracks.getOrNull(row.track.toInt())?.let { trackMatches(it,q,field) }==true
        val members=if(row.group!=0xffffffffL && row.type in 1..3)
            groups[row.type-1].getOrNull(row.group.toInt())?.tracks.orEmpty() else emptyList()
        if(members.isEmpty())return field==0 && (row.title+" "+row.subtitle).lowercase(Locale.ROOT).contains(q)
        return members.any { id -> tracks.getOrNull(id)?.let { trackMatches(it,q,field) }==true }
    }
    private fun trackMatches(t: LibraryTrack,q: String,field: Int): Boolean {
        val text=when(field) {
            1->t.title;2->t.artist;3->t.album;4->t.years+" "+decadeFor(t.id)
            else->"${t.title} ${t.artist} ${t.album} ${t.years} ${decadeFor(t.id)}"
        }
        return text.lowercase(Locale.ROOT).contains(q)
    }
    private val decadeByTrack: Map<Int,String> by lazy {
        val result=mutableMapOf<Int,String>();groups[2].forEach { g->g.tracks.forEach { result[it]=g.title } }; result
    }
    private fun decadeFor(id: Int)=decadeByTrack[id].orEmpty()
}
/** One authoritative highlight snapshot shared by all three UI levels. */
internal data class LibraryPlaybackHighlight(
    val source: Int?=null,val track: Long?=null,val groups: List<Set<Int>> = List(3) { emptySet() }
) {
    fun containsGroup(type: Int,group: Long): Boolean =
        type in 1..3 && group in 0L..Int.MAX_VALUE.toLong() && group.toInt() in groups[type-1]
    fun contains(row: LibraryRow): Boolean = track!=null &&
        if(row.isTrack)row.track==track else containsGroup(row.type,row.group)
    fun category(mode: Int,detailType: Int,detailGroup: Long): Boolean =
        if(mode==4)containsGroup(detailType,detailGroup)
        else mode in 1..3 && groups[mode-1].isNotEmpty()
    companion object {
        fun from(connected: Boolean,state: LibraryState?,index: LibraryIndex?): LibraryPlaybackHighlight {
            if(!connected || state==null || index==null || !state.ready || !state.music ||
                state.source!=state.playingSource || state.track !in 0L until index.tracks.size.toLong())
                return LibraryPlaybackHighlight()
            return LibraryPlaybackHighlight(state.source,state.track,index.groupsForTrack(state.track))
        }
    }
}
internal object LibraryWire {
    fun request(op: Int,source: Int,id: Long,state: LibraryState?,a: Long=0,b: Long=0,c: Long=0): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x30.toByte());put(1.toByte());put(op.toByte());put(source.toByte());putInt(id.toInt())
            putInt(state?.generation?.toInt()?:0);putInt(state?.revision?.toInt()?:0)
            putInt(a.toInt());putInt(b.toInt());putInt(c.toInt());putInt(0)
        }.array()
    fun u32(bytes: ByteArray,off: Int): Long = ByteBuffer.wrap(bytes,off,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    fun u16(bytes: ByteArray,off: Int): Int=(bytes[off].toInt() and 255) or ((bytes[off+1].toInt() and 255) shl 8)
    fun crc(bytes: ByteArray)=CRC32().apply { update(bytes) }.value
}
/** Strict contiguous assembly. A stale request or missing fragment never reaches the cache. */
internal class LibraryAssembly(val id: Long,val op: Int) {
    private var data: ByteArray?=null
    private var used=0
    private var checksum=0L
    private var result=-1
    fun accept(frame: ByteArray): Pair<Int,ByteArray>? {
        require(frame.size in 17..255 && frame[0].toInt()==6 && frame[1].toInt()==1)
        require(LibraryWire.u32(frame,4)==id && (frame[3].toInt() and 255)==op)
        val total=LibraryWire.u16(frame,8);val off=LibraryWire.u16(frame,10);val crc=LibraryWire.u32(frame,12)
        require(total in 52..2048 && off==used && off+frame.size-16<=total)
        if(data==null){require(off==0);data=ByteArray(total);checksum=crc;result=frame[2].toInt() and 255}
        require(data!!.size==total && crc==checksum && result==(frame[2].toInt() and 255))
        frame.copyInto(data!!,off,16);used+=frame.size-16
        if(used<total)return null
        require(LibraryWire.crc(data!!)==checksum)
        return result to data!!
    }
}
