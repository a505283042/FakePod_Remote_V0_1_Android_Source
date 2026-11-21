package android.system
object Os {fun rename(from:String,to:String){java.nio.file.Files.move(java.io.File(from).toPath(),java.io.File(to).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)}}
