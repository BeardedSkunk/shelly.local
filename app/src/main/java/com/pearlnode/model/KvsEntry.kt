package com.pearlnode.model

/**
 * One entry of a Gen2+ device's key-value store (`KVS.GetMany`).
 *
 * A KVS value may be any JSON: an object, an array, or a bare string/number/
 * boolean. [value] always holds text that is ready to display -- for objects and
 * arrays that is their JSON source, for primitives their plain content without
 * the surrounding quotes. [isStructured] tells the UI whether [value] can be
 * broken apart into several lines.
 */
data class KvsEntry(
    val key: String,
    val value: String,
    val isStructured: Boolean,
)
