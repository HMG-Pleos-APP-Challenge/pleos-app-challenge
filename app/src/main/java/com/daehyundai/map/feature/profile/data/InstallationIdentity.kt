package com.daehyundai.map.feature.profile.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.UUID

/** Corrupt identity must be repaired, never silently replaced with a different account. */
class InstallationIdentity(private val file:File) {
    fun getOrCreate():String=synchronized(lock) {
        if(file.exists()) return@synchronized file.readText(Charsets.UTF_8).trim().also { value ->
            require(value.startsWith("u-") && "u-${UUID.fromString(value.removePrefix("u-"))}"==value) { "사용자 ID 파일 확인 필요" }
        }
        val id="u-${UUID.randomUUID()}"
        val parent=requireNotNull(file.parentFile);check(parent.isDirectory || parent.mkdirs())
        val temp=File.createTempFile("identity-",".tmp",parent)
        try {
            FileOutputStream(temp).use { it.write((id+"\n").toByteArray(Charsets.UTF_8));it.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { temp.delete() }
        id
    }
    companion object { private val lock=Any() }
}
