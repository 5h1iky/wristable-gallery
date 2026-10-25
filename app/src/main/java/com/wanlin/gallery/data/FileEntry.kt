package com.wanlin.gallery.data

import java.io.File

/** 文件浏览器条目：目录或任意后缀文件 */
data class FileNode(
    val file: File,
    val isDir: Boolean,
    /** 目录子项数（后台线程预统计，避免列表绑定时主线程做文件 IO） */
    val childCount: Int = 0
) {
    /** 后缀（大写，无后缀为 FILE） */
    val ext: String
        get() = if (isDir) "DIR" else file.extension.uppercase().ifEmpty { "FILE" }
}
