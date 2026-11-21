package com.fakepod.remote
private const val NONE=0xffffffffL
fun main(){
 val paths=listOf("/sdcard/MUSIC/A/one.flac","/sdcard/MUSIC/A/B/two.flac","/sdcard/MUSIC/A/B/deep/three.flac","/sdcard/MUSIC/root.flac")
 val tracks=paths.mapIndexed{i,p->LibraryTrack(i,"Song$i","Artist${i%2}","Album$i",p,16,2024,0,1)}
 val groups=List(3){listOf(LibraryGroup(0,"one","",0,listOf(0,2)),LibraryGroup(1,"two","",0,listOf(1,3)))}
 val index=LibraryIndex(tracks,groups)
 val base=LibraryState(0,0,0,0,0,false,true,true,1,7,200,1,0,NONE,listOf(4,2,2,2,4))
 val browser=LibraryIndependentBrowse()
 browser.category(0,2);val albums=browser.rows(0,index);check(albums.size==2)
 check(browser.rows(0,index)===albums)
 // Simulate the device repeatedly changing category, detail, search and revision.
 for(deviceMode in 0..4){val s=browser.decorate(base.copy(mode=deviceMode,detailType=1,detailGroup=1,scope=2,searching=true,revision=deviceMode.toLong()),index)
  check(s.mode==2 && s.scope==0 && !s.searching);check(browser.rows(0,index)===albums)
 }
 browser.open(0,albums[0],index);val detail=browser.rows(0,index);check(detail.map{it.track}==listOf(0L,2L))
 val args=browser.playArguments(0,detail[1]);check(args==Triple(2L,2L,0L))
 val s=browser.decorate(base.copy(track=2),index)
 check(s.mode==4 && s.detailType==2 && s.detailGroup==0L)
 check(LibraryPlaybackHighlight.from(true,s,index).contains(detail[1]))
 // Another source has its own independent navigation and ID namespace.
 browser.category(1,3);check(browser.decorate(base.copy(source=1),index).mode==3)
 check(browser.decorate(base,index).detailGroup==0L)
 check(!LibraryPlaybackHighlight.from(true,s.copy(playingSource=1),index).contains(detail[1]))
 browser.back(0);check(browser.decorate(base,index).mode==2)
 browser.scope(0,1);val folders=browser.rows(0,index);check(folders.size==1)
 browser.open(0,folders[0],index);check(browser.rows(0,index).map{it.track}==listOf(0L,1L,2L))
 check(browser.playArguments(0,browser.rows(0,index)[0]).second==4L)
 browser.scope(0,2);val parents=browser.rows(0,index);check(parents.size==1)
 browser.open(0,parents[0],index);val children=browser.rows(0,index);check(children.size==1)
 browser.open(0,children[0],index);check(browser.rows(0,index).map{it.track}==listOf(1L))
 check(browser.playArguments(0,browser.rows(0,index)[0]).second==5L)
 browser.back(0);check(browser.rows(0,index).size==1);browser.back(0);check(browser.rows(0,index).size==1)
 check(LibraryIndependentBrowse.directories(0,"/sdcard/MUSIC/A/../bad.flac").isEmpty())
 println("PASS: device/APP category isolation, per-source navigation, detail play context, playback highlight, cached rows, L1 recursive/L2 direct folders")
}
