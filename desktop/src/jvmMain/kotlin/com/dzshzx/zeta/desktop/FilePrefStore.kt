package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.PrefStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 桌面上的 [PrefStore]：一个 JSON 文件 [file]，每项记着类型（`{"t":"s","v":…}`），读回时和写进去的类型一样。全部内容在内存里，
 * 改完整份重写（先写临时文件再换名，写一半断电也不坏）；[PrefStore.Editor.apply] 交给写盘线程，[PrefStore.Editor.commit] 当场写。
 */
class FilePrefStore(private val file: File) : PrefStore {
    private val lock = Any()
    private val values: MutableMap<String, Any> = load()

    override fun getString(key: String, default: String?): String? =
        synchronized(lock) { values[key] as? String } ?: default

    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        synchronized(lock) { (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() }
            ?: default

    override fun getInt(key: String, default: Int): Int =
        synchronized(lock) { values[key] as? Int } ?: default

    override fun getLong(key: String, default: Long): Long =
        synchronized(lock) { values[key] as? Long } ?: default

    override fun getFloat(key: String, default: Float): Float =
        synchronized(lock) { values[key] as? Float } ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        synchronized(lock) { values[key] as? Boolean } ?: default

    override fun contains(key: String): Boolean = synchronized(lock) { key in values }

    override val all: Map<String, *>
        get() = synchronized(lock) { HashMap(values) }

    override fun edit(): PrefStore.Editor = Editor()

    private inner class Editor : PrefStore.Editor {
        private val changes = LinkedHashMap<String, Any?>()

        private fun put(key: String, value: Any?) = apply { changes[key] = value }

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, value: Set<String>?) = put(key, value?.toSet())

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun apply() {
            val snapshot = change()
            writer.execute { write(snapshot) }
        }

        override fun commit(): Boolean = write(change())

        /** 改内存里的那份，返回改后的整份（写盘用）。 */
        private fun change(): Map<String, Any> =
            synchronized(lock) {
                for ((key, value) in changes) if (value == null) values.remove(key)
                else values[key] = value
                HashMap(values)
            }
    }

    private fun load(): MutableMap<String, Any> {
        val root =
            runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
                ?: return HashMap()
        val loaded = HashMap<String, Any>()
        for ((key, element) in root) decode(element)?.let { loaded[key] = it }
        return loaded
    }

    /** 整份写进 [file]；写不成返回 false。 */
    private fun write(snapshot: Map<String, Any>): Boolean =
        synchronized(file) {
            runCatching {
                file.parentFile?.mkdirs()
                val text = buildJsonObject {
                    for ((key, value) in snapshot) put(key, encode(value))
                }
                val temp = File(file.path + ".tmp")
                temp.writeText(text.toString())
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
                .isSuccess
        }

    private companion object {
        /** 各个文件共用一个写盘线程：apply 的先后就是写盘的先后。 */
        val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "zeta-prefs").apply { isDaemon = true }
        }

        fun encode(value: Any): JsonObject =
            when (value) {
                is String -> typed("s", JsonPrimitive(value))
                is Int -> typed("i", JsonPrimitive(value))
                is Long -> typed("l", JsonPrimitive(value))
                is Float -> typed("f", JsonPrimitive(value))
                is Boolean -> typed("b", JsonPrimitive(value))
                is Set<*> ->
                    typed(
                        "set",
                        buildJsonArray {
                            value.filterIsInstance<String>().forEach { add(JsonPrimitive(it)) }
                        },
                    )
                else -> error("设置里存不了 ${value::class.java.name}")
            }

        fun typed(type: String, value: JsonElement) = buildJsonObject {
            put("t", JsonPrimitive(type))
            put("v", value)
        }

        fun decode(element: JsonElement): Any? {
            val o = element as? JsonObject ?: return null
            val type = (o["t"] as? JsonPrimitive)?.contentOrNull
            val value = o["v"]
            val primitive = value as? JsonPrimitive
            return when (type) {
                "s" -> primitive?.contentOrNull
                "i" -> primitive?.intOrNull
                "l" -> primitive?.longOrNull
                "f" -> primitive?.doubleOrNull?.toFloat()
                "b" -> primitive?.booleanOrNull
                "set" ->
                    (value as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.toSet()
                else -> null
            }
        }
    }
}
