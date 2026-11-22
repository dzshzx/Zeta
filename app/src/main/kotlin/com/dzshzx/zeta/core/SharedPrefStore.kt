package com.dzshzx.zeta.core

import android.content.Context
import android.content.SharedPreferences

/** 手机上的 [PrefStore]：应用私有的 SharedPreferences 文件 [name]（shared_prefs/<name>.xml）。 */
class SharedPrefStore(private val prefs: SharedPreferences) : PrefStore {
    constructor(
        context: Context,
        name: String,
    ) : this(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        prefs.getStringSet(key, default)

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun contains(key: String): Boolean = prefs.contains(key)

    override val all: Map<String, *>
        get() = prefs.all

    override fun edit(): PrefStore.Editor = Editor(prefs.edit())

    private class Editor(private val editor: SharedPreferences.Editor) : PrefStore.Editor {
        override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }

        override fun putStringSet(key: String, value: Set<String>?) = apply {
            editor.putStringSet(key, value)
        }

        override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }

        override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }

        override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }

        override fun putBoolean(key: String, value: Boolean) = apply {
            editor.putBoolean(key, value)
        }

        override fun remove(key: String) = apply { editor.remove(key) }

        override fun apply() = editor.apply()

        override fun commit(): Boolean = editor.commit()
    }
}
