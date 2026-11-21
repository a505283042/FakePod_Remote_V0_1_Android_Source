package com.fakepod.remote

/** Phone-only navigation. No classification/folder navigation command crosses the wire. */
internal class LibraryIndependentBrowse {
    private data class View(var scope:Int=0,var mode:Int=0,var group:Int?=null,var folder:String?=null,var parent:String?=null,var revision:Long=1)
    private var rowsIndex:LibraryIndex?=null
    private var rowsKey=""
    private var cachedRows=emptyList<LibraryRow>()
    private val views=mutableMapOf<Int,View>()
    private fun view(source:Int)=views.getOrPut(source){View()}
    fun category(source:Int,mode:Int){if(mode !in 0..3)return;view(source).apply{scope=0;this.mode=mode;group=null;folder=null;parent=null;revision++}}
    fun scope(source:Int,scope:Int){if(scope !in 0..2)return;view(source).apply{this.scope=scope;mode=0;group=null;folder=null;parent=null;revision++}}
    fun back(source:Int){view(source).apply{if(group!=null)group=null else if(folder!=null)folder=null else parent=null;revision++}}
    fun open(source:Int,row:LibraryRow,index:LibraryIndex){view(source).apply {
        if(scope==0){if(row.type in 1..3 && row.group>=0 && row.group<index.groups[row.type-1].size.toLong()){mode=row.type;group=row.group.toInt()}}
        else {
            val path=index.tracks.getOrNull(row.position.toInt())?.path?:return
            val directories=directories(source,path)
            if(scope==2 && parent==null)parent=directories.getOrNull(0) else folder=directories.getOrNull(scope-1)
        }
        revision++
    }}
    fun decorate(state:LibraryState,index:LibraryIndex?):LibraryState {
        val v=view(state.source)
        if(v.group!=null && index!=null && v.group !in index.groups[v.mode-1].indices){v.group=null;v.revision++}
        return state.copy(scope=v.scope,mode=if(v.group!=null)4 else v.mode,detailType=if(v.group!=null)v.mode else 0,
            detailGroup=v.group?.toLong()?:0xffffffffL,folderView=if(v.scope==0)0 else if(v.folder!=null)0 else if(v.scope==1)1 else if(v.parent==null)3 else 2,
            searching=false,revision=v.revision,counts=state.counts.take(4)+0)
    }
    fun rows(source:Int,index:LibraryIndex):List<LibraryRow> {
        val key="$source:${view(source).revision}"
        if(rowsIndex===index && rowsKey==key)return cachedRows
        cachedRows=buildRows(source,index);rowsIndex=index;rowsKey=key;return cachedRows
    }
    private fun buildRows(source:Int,index:LibraryIndex):List<LibraryRow> {
        val v=view(source)
        if(v.scope==0){
            if(v.mode in 1..3 && v.group==null)return index.groups[v.mode-1].map {g->LibraryRow(g.id,v.mode+2,v.mode,0,g.id.toLong(),0xffffffffL,g.title,g.artist,g.tracks.size)}
            val ids=if(v.group!=null)index.groups[v.mode-1].getOrNull(v.group!!)?.tracks.orEmpty() else index.tracks.indices.toList()
            return ids.mapIndexed {i,id->val t=index.tracks[id];LibraryRow(i,if(v.group==null)1 else 6,if(v.group==null)0 else v.mode,i.toLong(),v.group?.toLong()?:0xffffffffL,id.toLong(),t.title,t.artist,0)}
        }
        if(v.folder!=null)return index.tracks.filter { t->
            val dirs=directories(source,t.path);dirs.getOrNull(v.scope-1)?.equals(v.folder,ignoreCase=source==0)==true && (v.scope==1 || dirs.size==2)
        }.mapIndexed {i,t->LibraryRow(i,2,0,i.toLong(),0xffffffffL,t.id.toLong(),t.title,t.artist,0)}
        val options=linkedMapOf<String,Triple<String,Int,Int>>()
        index.tracks.forEach {t->val dirs=directories(source,t.path)
            val path=if(v.scope==1)dirs.getOrNull(0) else if(v.parent==null && dirs.size==2)dirs.getOrNull(0) else if(dirs.getOrNull(0)?.equals(v.parent,ignoreCase=source==0)==true && dirs.size==2)dirs.getOrNull(1) else null
            if(path!=null){val key=if(source==0)path.lowercase(java.util.Locale.ROOT) else path;val old=options[key];options[key]=Triple(old?.first?:path,old?.second?:t.id,(old?.third?:0)+1)}
        }
        return options.entries.mapIndexed {i,(_,entry)->LibraryRow(i,if(v.scope==1)7 else 9,0,entry.second.toLong(),0xffffffffL,0xffffffffL,entry.first.trimEnd('/').substringAfterLast('/'),"",entry.third)}
    }
    fun playArguments(source:Int,row:LibraryRow):Triple<Long,Long,Long> {
        val v=view(source)
        return Triple(row.track,if(v.scope!=0)(v.scope+3).toLong() else if(v.group!=null)v.mode.toLong() else 0L,v.group?.toLong()?:0xffffffffL)
    }
    companion object {
        fun directories(source:Int,path:String):List<String> {
            val root=if(source==0)"/sdcard/MUSIC/" else "/"
            if(!path.startsWith(root,ignoreCase=source==0))return emptyList()
            val relative=path.substring(root.length);val parts=relative.split('/')
            if(parts.any{it.isEmpty() || it=="." || it==".."})return emptyList()
            return (1 until parts.size).map {root+parts.take(it).joinToString("/")+"/"}
        }
    }
}
