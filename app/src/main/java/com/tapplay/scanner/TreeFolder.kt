package com.tapplay.scanner

/**
 * Pure tree-node abstraction over a SAF document (design: recursive scan).
 * Implementations may resolve [children] lazily — [TreeFlatten] accesses them
 * only while descending into folders.
 */
interface TreeFolder {
    val name: String
    val isFile: Boolean
    val uri: String
    val children: List<TreeFolder>
}
