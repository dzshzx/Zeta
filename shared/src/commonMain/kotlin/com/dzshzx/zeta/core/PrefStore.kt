package com.dzshzx.zeta.core

/**
 * 存设置的键值文件，接口照 Android 的 SharedPreferences：手机上就是它（app 的 SharedPrefStore），桌面上是用户目录下的一个文件。 [Settings]
 * 和各个清单存储经它读写。
 */
interface PrefStore {
    fun getString(key: String, default: String?): String?

    fun getStringSet(key: String, default: Set<String>?): Set<String>?

    fun getInt(key: String, default: Int): Int

    fun getLong(key: String, default: Long): Long

    fun getFloat(key: String, default: Float): Float

    fun getBoolean(key: String, default: Boolean): Boolean

    fun contains(key: String): Boolean

    /** 全部存着的项（只读的一份）。 */
    val all: Map<String, *>

    fun edit(): Editor

    /** 一次改几项：[apply] 先改内存、异步写盘，[commit] 同步写盘，写成了是 true。 */
    interface Editor {
        fun putString(key: String, value: String?): Editor

        fun putStringSet(key: String, value: Set<String>?): Editor

        fun putInt(key: String, value: Int): Editor

        fun putLong(key: String, value: Long): Editor

        fun putFloat(key: String, value: Float): Editor

        fun putBoolean(key: String, value: Boolean): Editor

        fun remove(key: String): Editor

        fun apply()

        fun commit(): Boolean
    }
}
