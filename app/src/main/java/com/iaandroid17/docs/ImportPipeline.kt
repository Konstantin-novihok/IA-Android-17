package com.iaandroid17.docs

import java.io.IOException
import java.util.Locale

enum class DocumentKind { TEXT, PDF, ZIP, DOCX, XLSX, PPTX, ODF, LEGACY_OFFICE, IMAGE, AUDIO, VIDEO, UNKNOWN }
data class DocumentSource(val displayName:String,val archivePath:String?=null,val page:Int?=null,val sheet:String?=null,val slide:Int?=null,val timestampMs:Long?=null) {
 fun marker()=buildString { append("[Источник: ").append(displayName); archivePath?.let{append(" | внутри: ").append(it)}; page?.let{append(" | страница: ").append(it)}; sheet?.let{append(" | лист: ").append(it)}; slide?.let{append(" | слайд: ").append(it)}; timestampMs?.let{append(" | время: ").append(it).append(" мс")}; append("]") }
}
data class ImportBudget(val maxDepth:Int=3,val maxFiles:Int=500,val maxTotalUncompressedBytes:Long=80L*1024*1024,val maxSingleFileBytes:Int=12*1024*1024,val maxExtractedCharacters:Int=3_000_000) {
 init { require(maxDepth in 0..8); require(maxFiles in 1..10_000); require(maxTotalUncompressedBytes in 1..(1024L*1024*1024)); require(maxSingleFileBytes>0&&maxExtractedCharacters>0) }
}
object FormatRouter {
 fun kind(fileName:String):DocumentKind=when(fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
 "txt","md","markdown","csv","tsv","json","xml","log","yaml","yml","ini","toml","cfg","conf","html","htm","rtf","tex","srt","vtt","sql","kt","java","py","js","ts","c","cpp","h","hpp","cs","go","rs","sh","bat","ps1","gradle","kts","properties"->DocumentKind.TEXT
 "pdf"->DocumentKind.PDF;"zip"->DocumentKind.ZIP;"docx"->DocumentKind.DOCX;"xlsx"->DocumentKind.XLSX;"pptx"->DocumentKind.PPTX
 "odt","ods","odp"->DocumentKind.ODF;"doc","xls","ppt"->DocumentKind.LEGACY_OFFICE
 "png","jpg","jpeg","webp","bmp","tif","tiff","heic"->DocumentKind.IMAGE
 "wav","mp3","m4a","aac","flac","ogg","opus"->DocumentKind.AUDIO
 "mp4","mkv","mov","webm","avi","m4v"->DocumentKind.VIDEO
 else->DocumentKind.UNKNOWN
 }
 fun requireSupportedText(fileName:String):DocumentKind { val k=kind(fileName); if(k==DocumentKind.UNKNOWN||k==DocumentKind.LEGACY_OFFICE) throw IOException("Формат файла не поддерживается текстовым импортёром: $fileName"); return k }
}
object Provenance { fun wrap(source:DocumentSource,text:String)=source.marker()+"\n"+text.trim() }
