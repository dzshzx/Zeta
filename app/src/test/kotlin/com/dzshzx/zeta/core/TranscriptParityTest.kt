package com.dzshzx.zeta.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TranscriptReducer] 和它的 Go 版（helper/transcript）归约出一样的条目：两边跑 helper/testdata/transcript 下同样的对照用例
 * （写法见 helper/transcript/parity_test.go 开头），条目照同一种
 * JSON（[chatItemJson]，也是核心推给客户端的写法）写出来比。期望结果由这里生成：改了归约器或加了用例后 `ZETA_RECORD_TRANSCRIPT=1`
 * 跑一次这个测试（重写各个 <名字>.items.json，这一次算失败），再不带它跑一次、跑 Go 的测试核对。
 */
class TranscriptParityTest {
    private val dir = File("../helper/testdata/transcript")
    private val pretty = Json { prettyPrint = true }

    @Test
    fun matchesTheRecordedItems() {
        val cases =
            dir.listFiles { f -> f.name.endsWith(".ops.jsonl") }.orEmpty().sortedBy { it.name }
        assertTrue("${dir.absolutePath} 下没有对照用例", cases.isNotEmpty())
        val record = System.getenv("ZETA_RECORD_TRANSCRIPT") == "1"
        for (case in cases) {
            // 写出来再读回去比：同一个数在用例里和写出的文件里写法可能不同（2.50 写成 2.5）
            val text =
                pretty.encodeToString(
                    JsonElement.serializer(),
                    JsonArray(run(case).items.map(::chatItemJson)),
                ) + "\n"
            val expected = File(dir, case.name.removeSuffix(".ops.jsonl") + ".items.json")
            if (record) {
                expected.writeText(text)
                continue
            }
            assertTrue("没有 ${expected.name}：ZETA_RECORD_TRANSCRIPT=1 跑一次生成", expected.isFile)
            assertEquals(
                case.name,
                Json.parseToJsonElement(expected.readText()),
                Json.parseToJsonElement(text),
            )
        }
        // 录制时不算通过：变量留在环境里的话，核对就什么也没核对
        check(!record) { "已重写 ${cases.size} 个期望结果；去掉 ZETA_RECORD_TRANSCRIPT 再跑一次核对" }
    }

    private fun run(case: File): TranscriptReducer {
        var reducer = TranscriptReducer()
        case.readLines().forEachIndexed { index, line ->
            val op = Json.parseToJsonElement(line) as JsonObject
            fun flag(key: String) = op.bool(key) == true
            when (op.str("op")) {
                "prefix" -> {
                    check(index == 0) { "${case.name}：prefix 只能在第一行" }
                    reducer = TranscriptReducer(op.str("value").orEmpty())
                }
                "stream" -> reducer.applyStream(op.obj("event")!!, op.long("now")!!)
                "assistant" -> reducer.applyAssistant(op.obj("message")!!, flag("history"))
                "user" ->
                    reducer.applyUser(
                        op.obj("message")!!,
                        flag("replay"),
                        sessionFile = op.str("sessionFile"),
                        uuid = op.str("uuid"),
                        toolUseResult = op.obj("toolUseResult"),
                    )
                "addUser" ->
                    reducer.addUser(
                        op.str("text").orEmpty(),
                        files = op["files"]!!.jsonArray.map { it.jsonPrimitive.content },
                        uuid = op.str("uuid"),
                    )
                "notice" -> reducer.addNotice(op.str("text").orEmpty(), flag("error"))
                "shell" -> reducer.applyShell(op.str("text").orEmpty())
                "finish" -> reducer.finishTurn(flag("keepRunningTools"))
                else -> error("${case.name}:${index + 1}：不认识的调用 $op")
            }
        }
        return reducer
    }
}
