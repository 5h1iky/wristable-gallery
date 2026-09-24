package com.wanlin.gallery.data

import java.io.File

/** 文件浏览器条目：目录或任意后缀文件 */
data class FileNode(
    val file: File,
    val isDir: Boolean
) {
    /** 后缀（大写，无后缀为 FILE） */
    val ext: String
        get() = if (isDir) "DIR" else file.extension.uppercase().ifEmpty { "FILE" }
}
