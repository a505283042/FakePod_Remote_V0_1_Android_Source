package com.fakepod.remote

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Virtualized list; the phone never constructs or owns a playback queue. */
internal class MusicLibraryDialog(private val activity: Activity,private val client: MusicLibraryClient): Dialog(activity) {
    private val bg=Color.rgb(10,12,16)
    private val fg=Color.rgb(245,247,250)
    private val secondary=Color.rgb(164,171,184)
    private val accent=Color.rgb(126,167,255)
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var search: EditText
    private lateinit var scope: Button
    private lateinit var category: Button
    private lateinit var source: Button
    private lateinit var back: Button
    private lateinit var list: ListView
    private lateinit var filter: Button
    private var field=0
    private var visible=emptyList<LibraryRow>()
    private var viewIdentity=""
    private var initialPlayingFocus=true
    private var filteredBase: List<LibraryRow>?=null
    private var filteredIndex: LibraryIndex?=null
    private var filteredQuery=""
    private var filteredField=-1
    private var highlight=LibraryPlaybackHighlight()
    private var highlightIndex: LibraryIndex?=null
    private var locateSerial=0L
    private var renderedOperable: Boolean?=null
    private var lastLocateKey=""
    private val main=Handler(Looper.getMainLooper())
    private val filterTask=Runnable {filterRows()}
    private val adapter=object: BaseAdapter() {
        override fun getCount()=visible.size
        override fun getItem(position: Int)=visible.getOrNull(position)
        override fun getItemId(position: Int)=visible.getOrNull(position)?.display?.toLong() ?: AdapterView.INVALID_ROW_ID
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View {
            val box=convertView as? LinearLayout ?: LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12))
                addView(TextView(activity).apply {setTextColor(fg);textSize=16f;maxLines=2},LinearLayout.LayoutParams(-1,-2))
                addView(TextView(activity).apply {setTextColor(secondary);textSize=12f;maxLines=2},LinearLayout.LayoutParams(-1,-2))
            }
            val r=visible[position]
            val playing=highlight.contains(r)
            (box.getChildAt(0) as TextView).apply {
                text=(if(playing)"▶  " else "")+r.title+(if(!r.isTrack)"  ›" else "")
                setTextColor(if(playing)accent else fg)
                setTypeface(null,if(playing)Typeface.BOLD else Typeface.NORMAL)
            }
            box.setBackgroundColor(if(playing)Color.rgb(29,49,82) else Color.TRANSPARENT)
            val t=if(r.isTrack)client.index?.tracks?.getOrNull(r.track.toInt()) else null
            (box.getChildAt(1) as TextView).text=if(t!=null)listOf(t.artist,t.album,t.years).filter {it.isNotBlank()}.joinToString(" · ")
                else listOf(r.subtitle,if(r.count>0)"${r.count} 项" else "").filter {it.isNotBlank()}.joinToString(" · ")
            // A pending state acknowledgement does not make a cached song unavailable.
            box.alpha=1f
            return box
        }
    }
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=14f)=TextView(activity).apply {text=value;textSize=size;setTextColor(fg)}
    private fun button(value: String,action: ()->Unit)=Button(activity).apply {
        text=value;isAllCaps=false;textSize=13f;setTextColor(fg);minWidth=0;minimumWidth=0
        background=GradientDrawable().apply {setColor(Color.rgb(27,31,40));cornerRadius=dp(10).toFloat()}
        setOnClickListener {action()}
    }
    private fun choices(anchor: View,values: List<String>,pick: (Int)->Unit) {
        PopupMenu(activity,anchor).apply {values.forEachIndexed {i,v->menu.add(0,i,i,v)};setOnMenuItemClickListener {pick(it.itemId);true};show()}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(18),dp(16),dp(12));setBackgroundColor(bg)}
        val titleRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        heading=label("音乐曲库",22f).apply {setTypeface(null,Typeface.BOLD)}
        titleRow.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        val more=button("⋯") {}
        more.contentDescription="更多操作"
        more.setOnClickListener {
            PopupMenu(activity,more).apply {
                menu.add(0,0,0,"定位当前歌曲").isEnabled=client.canOperate
                menu.add(0,1,1,"同步曲库").isEnabled=client.connected && !client.busy
                setOnMenuItemClickListener {
                    if(it.itemId==0)locatePlaying(true) else client.refresh()
                    true
                }
                show()
            }
        }
        titleRow.addView(more,LinearLayout.LayoutParams(dp(48),dp(40)))
        root.addView(titleRow)
        status=label("",12f).apply {setTextColor(secondary);setPadding(0,dp(6),0,dp(12))};root.addView(status)
        val controls=LinearLayout(activity)
        source=button("来源") {choices(source,listOf("TF 本地","NAS 曲库")){i->if(client.connected)client.selectSource(i)}}
        scope=button("总列表") {choices(scope,listOf("总列表","一级列表","二级列表")){client.scope(it)}}
        category=button("歌曲") {choices(category,listOf("歌曲","歌手","专辑","年代")){i->
            client.category(i)
        }}
        listOf(source,scope,category).forEach {v->controls.addView(v,LinearLayout.LayoutParams(0,dp(44),1f).apply {setMargins(dp(2),0,dp(2),0)})}
        root.addView(controls)
        val queryRow=LinearLayout(activity).apply {setPadding(0,dp(10),0,dp(8))}
        search=EditText(activity).apply {hint="搜索歌名、歌手、专辑、年代";setSingleLine(true);textSize=14f;setTextColor(fg);setHintTextColor(secondary)
            addTextChangedListener(object: TextWatcher {override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int){}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int){initialPlayingFocus=false;main.removeCallbacks(filterTask);locateSerial++;main.postDelayed(filterTask,180)}
                override fun afterTextChanged(s: Editable?){} })}
        queryRow.addView(search,LinearLayout.LayoutParams(0,dp(48),1f))
        filter=button("综合") {choices(filter,listOf("综合","歌名","歌手","专辑","年代")){i->field=i;filter.text=listOf("综合","歌名","歌手","专辑","年代")[i];filterRows()}}
        queryRow.addView(filter,LinearLayout.LayoutParams(dp(68),dp(44)));root.addView(queryRow)
        list=ListView(activity).apply {dividerHeight=dp(1);setBackgroundColor(bg);adapter=this@MusicLibraryDialog.adapter
            emptyView=null;setOnItemClickListener {_,_,p,_->
                val r=visible[p]
                if(!r.isTrack || client.canOperate)client.activate(r)
                else Toast.makeText(activity,"离线可浏览，连接并校验后才可选歌",Toast.LENGTH_SHORT).show()
            }}
        list.setOnScrollListener(object: AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?,state: Int) {
                if(state==AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL){initialPlayingFocus=false;locateSerial++}
            }
            override fun onScroll(view: AbsListView?,first: Int,count: Int,total: Int) {}
        })
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(activity).apply {setPadding(0,dp(8),0,0)}
        back=button("上一级") {client.back()}
        footer.addView(back,LinearLayout.LayoutParams(0,dp(44),1f))
        footer.addView(button("返回") {dismiss()},LinearLayout.LayoutParams(0,dp(44),1f));root.addView(footer)
        root.addView(label("两端独立浏览，按当前播放歌曲定位。",11f).apply {setTextColor(secondary);setPadding(0,dp(8),0,0)})
        setCancelable(true)
        setContentView(root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,(activity.resources.displayMetrics.heightPixels*0.92).toInt())
        client.attach {render()}
    }
    private fun render() {
        if(!::status.isInitialized)return
        val s=client.state
        val sourceName=if(client.sourceMode==255)"待确认" else when(client.sourceMode){0->"本地";1->"NAS";else->"待确认"}
        status.text="当前：$sourceName · ${client.message}"
        source.text=if(client.localOnly)"本地 · 蓝牙" else if(client.sourceMode==255)"确认来源…" else "$sourceName ▾"
        scope.text=listOf("总列表","一级列表","二级列表").getOrElse(s?.scope?:0){"总列表"}+(if(client.connected)" ▾" else " · 离线")
        val cat=s?.let {if(it.mode==4)it.detailType else it.mode}?:0
        val directoryScope=s?.scope in listOf(1,2)
        category.text=if(directoryScope)"歌曲" else listOf("歌曲","歌手","专辑","年代").getOrElse(cat){"歌曲"}+" ▾"
        val nextHighlight=LibraryPlaybackHighlight.from(client.connected && client.hasCurrentIndex,s,client.index)
        val highlightChanged=nextHighlight!=highlight || highlightIndex!==client.index
        highlight=nextHighlight;highlightIndex=client.index
        val parentHighlighted=!directoryScope && s!=null && highlight.category(s.mode,s.detailType,s.detailGroup)
        scope.isEnabled=client.hasCurrentIndex && !client.busy;category.isEnabled=client.hasCurrentIndex && !client.busy && s?.scope==0
        category.background=GradientDrawable().apply {
            setColor(if(parentHighlighted)Color.rgb(35,60,100) else Color.rgb(27,31,40))
            cornerRadius=dp(10).toFloat()
        }
        category.setTextColor(if(parentHighlighted)accent else if(category.isEnabled)fg else secondary)
        category.setTypeface(null,if(parentHighlighted)Typeface.BOLD else Typeface.NORMAL)
        category.alpha=if(category.isEnabled)1f else 0.45f
        source.isEnabled=client.connected && !client.busy && !client.localOnly
        val hasParent=s?.let {
            it.searching || it.mode==4 || it.scope!=0 && it.folderView in listOf(0,2)
        }==true
        back.isEnabled=hasParent && client.hasCurrentIndex && !client.busy
        back.setTextColor(if(back.isEnabled)fg else secondary)
        back.alpha=if(back.isEnabled)1f else 0.45f
        heading.text="音乐曲库"+(if(!client.connected)" · 离线浏览" else if(s?.searching==true)" · 设备搜索结果" else "")
        // Playback/status revisions are not list changes; do not reset the list to row 0 on each poll.
        val id=if(s!=null)
            "${s.cacheKey}-${s.generation}-${s.revision}-${s.scope}-${s.mode}-${s.detailType}-${s.detailGroup}-${s.folderView}-${s.searching}"
        else "empty"
        val changed=id!=viewIdentity;viewIdentity=id
        val rowsChanged=filterRows(forceRedraw=changed || highlightChanged)
        // Source changes can clear the list before its cached index is loaded.
        if(changed && visible.isNotEmpty())list.setSelection(0)
        val locateKey=if(client.connected && s!=null && s.source==s.playingSource && s.music && s.ready && !s.searching && search.text.isBlank())
            "${s.source}:${s.track}:$id" else ""
        val playbackChanged=locateKey.isNotEmpty() && locateKey!=lastLocateKey
        if(client.canOperate && search.text.isBlank() && (initialPlayingFocus || changed || playbackChanged || rowsChanged)) {
            // A target may become available only after rows/index finish loading.
            // Do not mark an unsuccessful lookup as already positioned.
            if(locatePlaying(false)){lastLocateKey=locateKey;initialPlayingFocus=false}
        }
    }
    private fun locatePlaying(showHint: Boolean): Boolean {
        val position=if(client.connected && client.canOperate)visible.indexOfFirst(highlight::contains) else -1
        val serial=++locateSerial
        if(position>=0) {
            val targetRows=visible
            val targetHighlight=highlight
            val targetIndex=client.index
            val targetView=viewIdentity
            val targetQuery=search.text.toString()
            list.post {
                // Rapid skips, source/category changes and user scrolling invalidate old work.
                if(isShowing && serial==locateSerial && visible===targetRows && highlight==targetHighlight &&
                    viewIdentity==targetView && client.index===targetIndex && search.text.toString()==targetQuery &&
                    LibraryPlaybackHighlight.from(client.connected && client.hasCurrentIndex,client.state,client.index)==targetHighlight &&
                    visible.getOrNull(position)?.let(highlight::contains)==true)
                    list.setSelectionFromTop(position,dp(12))
            }
            return true
        }
        if(showHint)Toast.makeText(activity,"当前播放歌曲不在此列表或搜索结果中",Toast.LENGTH_SHORT).show()
        return false
    }
    private fun filterRows(forceRedraw: Boolean=false): Boolean {
        if(!::search.isInitialized)return false
        val base=client.rows
        val query=search.text.toString()
        var rowsChanged=false
        if(base!==filteredBase || client.index!==filteredIndex || query!=filteredQuery || field!=filteredField) {
            val next=client.index?.filterRows(base,query,field) ?: if(query.isBlank())base else emptyList()
            rowsChanged=next!=visible
            if(rowsChanged)visible=next
            filteredBase=base;filteredIndex=client.index;filteredQuery=query;filteredField=field
        }
        val operable=!client.connected || client.canOperate
        if(forceRedraw || rowsChanged || operable!=renderedOperable) {
            renderedOperable=operable
            adapter.notifyDataSetChanged()
        }
        if(base.isNotEmpty() && visible.isEmpty())status.text=client.message+" · 没有匹配结果"
        return rowsChanged
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed(){dismiss()}
    override fun dismiss(){locateSerial++;main.removeCallbacks(filterTask);client.detach();super.dismiss()}
}
