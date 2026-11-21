"""Execute the actual dialog selection expressions with the real library client.

Requires kotlinc on PATH, or --compiler-cp '<compiler-jars>/*' --stdlib <stdlib.jar>.
Android widgets are modeled by a probe that requests row ID on setSelection,
matching the customer AbsListView stack. Android runtime rendering is not tested.
"""
from pathlib import Path
import argparse,os,re,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
a=argparse.ArgumentParser();a.add_argument('--compiler-cp');a.add_argument('--stdlib');a.add_argument('--dialog',type=Path,default=root/'app/src/main/java/com/fakepod/remote/MusicLibraryDialog.kt');args=a.parse_args()
dialog=args.dialog.read_text()
getid=re.search(r'override fun getItemId\(position: Int\)=(.*)',dialog).group(1)
selection=re.search(r'if\(changed[^\n]*\)list.setSelection\(0\)',dialog).group(0)
helpers=(root/'tests/LibraryReopenCheck.kt').read_text().split('private fun runChecks(){')[0]
probe='''
private object AdapterView {const val INVALID_ROW_ID=Long.MIN_VALUE}
private class SelectionProbe {
 var visible:List<LibraryRow> = emptyList()
 var selections=0
 fun getItemId(position:Int):Long = GET_ID
 fun render(rows:List<LibraryRow>){
  visible=rows
  val changed=true
  val list=object {fun setSelection(position:Int){selections++;getItemId(position)}}
  SELECTION
 }
}
'''.replace('GET_ID',getid).replace('SELECTION',selection)
checks='''
private fun runChecks(){
 val root=Files.createTempDirectory("fakepod-empty-ui").toFile();val context=Context(root)
 val local=state(0);val nas=state(1);writeCache(root,local);writeCache(root,nas)
 val wire=ArrayDeque<ByteArray>();val client=MusicLibraryClient(context,{}, {wire.addLast(it);true})
 client.onConnection(true);client.attach{};reply(client,wire.removeFirst(),local)
 await("initial Local"){client.canOperate}
 client.detach()
 val probe=SelectionProbe();client.attach{probe.render(client.rows)}
 reply(client,wire.removeFirst(),local)
 await("reattach"){client.canOperate}
 // Switching clears rows before asynchronous cache read. The actual UI expressions
 // run synchronously in changed(), inside the same onFrame stack as the real app.
 client.selectSource(1);reply(client,wire.removeFirst(),nas)
 await("NAS cache remains live after empty render"){client.canOperate && client.rows.firstOrNull()?.title=="NAS"}
 client.selectSource(0);reply(client,wire.removeFirst(),local)
 await("Local cache after NAS"){client.canOperate && client.rows.firstOrNull()?.title=="LOCAL"}
 check(wire.isEmpty()){ "Cache hit must not download catalog pages" }
 check(!client.message.contains("异常"))
 probe.render(emptyList());val calls=probe.selections
 probe.render(emptyList());check(probe.selections==calls)
 check(probe.getItemId(0)==AdapterView.INVALID_ROW_ID)
 check(probe.getItemId(-1)==AdapterView.INVALID_ROW_ID)
 probe.render(client.rows)
 check(probe.getItemId(0)==client.rows[0].display.toLong())
 check(probe.getItemId(99)==AdapterView.INVALID_ROW_ID)
 client.detach()
 println("PASS: actual dialog empty-selection and row-ID expressions; real client Local/NAS cached switches complete without retry or download")
}
fun main(){try{runChecks();kotlin.system.exitProcess(0)}catch(t:Throwable){t.printStackTrace();kotlin.system.exitProcess(1)}}
'''
with tempfile.TemporaryDirectory() as tmp:
 out=Path(tmp);fixture=out/'EmptySelectionCheck.kt';fixture.write_text(helpers+probe+checks)
 sources=list((root/'tests/reopen_stubs').glob('*.kt'))+[root/'app/src/main/java/com/fakepod/remote'/n for n in ['MusicLibraryClient.kt','MusicLibraryModel.kt','LibraryIndependentBrowse.kt','LibrarySourceChoice.kt','RemotePowerPolicy.kt']]+[fixture]
 jar=out/'check.jar'
 if args.compiler_cp:
  if not args.stdlib:a.error('--stdlib required with --compiler-cp')
  cmd=['java','-cp',args.compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',args.stdlib,'-d',str(jar)]
  runtime=str(jar)+os.pathsep+args.stdlib
 else:
  cmd=['kotlinc','-include-runtime','-d',str(jar)];runtime=str(jar)
 subprocess.run(cmd+list(map(str,sources)),check=True)
 subprocess.run(['java','-cp',runtime,'com.fakepod.remote.EmptySelectionCheckKt'],check=True)
