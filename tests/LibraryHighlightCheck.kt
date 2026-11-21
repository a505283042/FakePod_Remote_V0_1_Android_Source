package com.fakepod.remote

private const val NONE = 0xffffffffL

private fun state(mode: Int=0,track: Long=2,source: Int=0) = LibraryState(
    source,0,mode,0,0,false,true,true,1,1,1,track,source,NONE,listOf(4,2,2,2,if(mode==0)4 else 2)
)
private fun catalog(reverse: Boolean=false): LibraryIndex {
    val tracks=List(4) { id -> LibraryTrack(id,"曲目 $id","Artist ${id%2}","同名专辑","/音乐/$id.flac",16,2024,0,1) }
    val groups=List(3) { type -> List(2) { id ->
        val members=if((id==0) xor reverse)listOf(0,2) else listOf(1,3)
        LibraryGroup(id,if(type==2)"2020年代" else "同名分组","Artist $id",2020,members)
    } }
    return LibraryIndex(tracks,groups)
}

fun main() {
    val index=catalog()
    val trackRows=requireNotNull(LibraryIndex.cachedRows(state(),index))
    val initial=LibraryPlaybackHighlight.from(true,state(),index)
    check(trackRows.filter(initial::contains).map { it.track }==listOf(2L))
    for(type in 1..3) {
        val rows=requireNotNull(LibraryIndex.cachedRows(state(mode=type),index))
        check(rows.filter(initial::contains).map { it.group }==listOf(0L))
        check(rows.indexOfFirst(initial::contains)==0) // Shared scroll target and highlight.
        check(initial.category(type,0,NONE)) // Previously omitted parent category level.
        check(initial.category(4,type,0))
        check(!initial.category(4,type,1)) // Same titles must not match the wrong group.
        check(initial.containsGroup(type,0))
        check(!initial.containsGroup(type,NONE))
    }
    val next=LibraryPlaybackHighlight.from(true,state(track=3),index)
    check(next!=initial)
    for(type in 1..3)check(next.containsGroup(type,1) && !next.containsGroup(type,0))
    check(!next.contains(trackRows[2]) && next.contains(trackRows[3]))
    // Replacing the catalog with identical row text/track id must still change membership.
    val replacement=LibraryPlaybackHighlight.from(true,state(),catalog(reverse=true))
    check(replacement!=initial && replacement.containsGroup(1,1))
    check(LibraryPlaybackHighlight.from(true,state(source=1),index)!=initial)
    for(invalid in listOf(state(track=NONE),state(track=4),state().copy(playingSource=1),
        state().copy(ready=false),state().copy(music=false))) {
        val empty=LibraryPlaybackHighlight.from(true,invalid,index)
        check(empty.track==null && trackRows.none(empty::contains))
        for(type in 1..3)check(!empty.category(type,0,NONE))
    }
    check(LibraryPlaybackHighlight.from(false,state(),index).track==null)
    check(LibraryPlaybackHighlight.from(true,state(),null).track==null)
    check(LibraryPlaybackHighlight.from(true,state(track=0),index).contains(trackRows[0]))
    check(!initial.category(0,0,NONE))
    check(!initial.containsGroup(0,0) && !initial.containsGroup(4,0))
    // One track may be in the featured artist and album-artist groups together.
    val collaborators=catalog().let { base -> base.copy(groups=base.groups.mapIndexed { type,section ->
        if(type==0)section.map { group -> group.copy(tracks=(group.tracks+2).distinct()) } else section
    }) }
    val collaborative=LibraryPlaybackHighlight.from(true,state(),collaborators)
    check(collaborative.containsGroup(1,0) && collaborative.containsGroup(1,1))
    val first=index.groupsForTrack(2)
    check(first===index.groupsForTrack(2)) // No repeated full-catalog membership scan.
    check(index.filterRows(trackRows,"   ",0)===trackRows) // No blank-search copy.
    check(index.filterRows(trackRows," ARTIST 0 ",2).map { it.track }==listOf(0L,2L))
    check(index.filterRows(trackRows,"2020年代",4).size==4)
    check(index.filterRows(trackRows,"曲目 3",1).single().track==3L)
    check(index.filterRows(trackRows,"缺失",0).isEmpty())
    val groups=requireNotNull(LibraryIndex.cachedRows(state(mode=1),index))
    check(index.filterRows(groups,"曲目 2",1).single().group==0L)
    println("PASS: track + artist/album/decade rows + parent controls; skips; index replacement; source isolation; invalid/offline state; search; membership reuse")
}
