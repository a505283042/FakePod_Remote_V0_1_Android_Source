package com.fakepod.remote

/** Floods of worker messages must replace, rather than accumulate, the pending UI state. */
fun main() {
    val state = NasScanProgress()
    state.reset("连接中", 0)
    for (n in 0..4250) state.offer("读取歌曲信息 $n/4250 · 标签：/Music/$n.mp3", n.toLong())
    val latest = state.snapshot()
    check(latest.done == 4250L && latest.total == 4250L && latest.message.endsWith("4250.mp3"))
    state.offer("SMB 连接中断，正在重连 1/2…", 5000)
    check(state.snapshot().done == 4250L && state.snapshot().total == 4250L)
    state.offer("SMB 连接中断，正在重连 1/2…", 8000)
    check(state.snapshot().since == 5000L) // Repeated text must not erase waiting time.
    state.reset("正在上传…", 9000)
    check(state.snapshot().done == null && state.snapshot().total == null)
    state.offer("扫描失败：network", 9100)
    check(state.snapshot().message == "扫描失败：network")
    state.offer("扫描完成：共 4250 首", 9200)
    check(state.snapshot().message.startsWith("扫描完成"))
    println("Scan progress coalescing: latest state, reconnect counts, elapsed time and reset PASS")
}
