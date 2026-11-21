package com.fakepod.remote

/** Durable device-confirmed source; browsing categories remain phone-owned. */
internal class LibrarySourceChoice(legacySource:Int = -1, lastConfirmedSource:Int = -1) {
    var source:Int = lastConfirmedSource.takeIf { it in 0..1 } ?: legacySource.takeIf { it in 0..1 } ?: 255
        private set
    fun confirm(reportedSource:Int):Boolean {
        if(reportedSource !in 0..1)return false
        val changed=source!=reportedSource;source=reportedSource;return changed
    }
}
