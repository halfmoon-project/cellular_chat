package com.cellularchat.app.core

import java.io.File
import org.json.JSONObject

/** Shared-fixture support: locate shared/vectors robustly and parse hex. */
object Vectors {
    private val directory: File by lazy { locate() }

    fun json(name: String): JSONObject =
        JSONObject(File(directory, name).readText())

    private fun locate(): File {
        val start = System.getProperty("user.dir") ?: "."
        var current: File? = File(start).absoluteFile
        while (current != null) {
            val candidate = File(current, "shared/vectors")
            if (candidate.isDirectory) return candidate
            current = current.parentFile
        }
        error("could not locate shared/vectors from ${System.getProperty("user.dir")}")
    }
}

fun hex(value: String): ByteArray {
    require(value.length % 2 == 0) { "odd-length hex: $value" }
    val out = ByteArray(value.length / 2)
    for (i in out.indices) {
        out[i] = value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
