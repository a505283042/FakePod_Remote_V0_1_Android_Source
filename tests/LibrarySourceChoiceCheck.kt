package com.fakepod.remote
fun main(){
 val source=LibrarySourceChoice();check(source.source==255)
 check(source.confirm(1)&&source.source==1);check(!source.confirm(1))
 check(source.confirm(0)&&source.source==0)
 check(LibrarySourceChoice(0,1).source==1)
 check(!source.confirm(255)&&source.source==0)
 check(LibrarySourceChoice(77).source==255)
 println("PASS: durable confirmed source, device changes override legacy choice, invalid reports ignored")
}
