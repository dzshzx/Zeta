package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoBoardTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private var seq = 0

    private fun step(
        name: String,
        input: String,
        result: String? = null,
        status: ToolStatus = if (result == null) ToolStatus.Running else ToolStatus.Success,
    ) = ToolStep("t${++seq}", name, json(input), status, result)

    private fun create(subject: String, id: String?, activeForm: String? = null) =
        ToolStep(
            "t${++seq}",
            "TaskCreate",
            buildJsonObject {
                put("subject", subject)
                put("description", "d")
                activeForm?.let { put("activeForm", it) }
            },
            if (id == null) ToolStatus.Running else ToolStatus.Success,
            id?.let { "Task #$it created successfully: $subject" },
        )

    private fun update(id: String, status: String? = null, subject: String? = null) =
        ToolStep(
            "t${++seq}",
            "TaskUpdate",
            buildJsonObject {
                put("taskId", id)
                status?.let { put("status", it) }
                subject?.let { put("subject", it) }
            },
            ToolStatus.Success,
            "Updated task #$id status",
        )

    private fun board(vararg steps: ToolStep, items: List<ChatItem> = emptyList()): TodoList? =
        TodoBoard.from(items + WorkItem("w", steps.toList()))

    @Test
    fun taskToolsBuildTheList() {
        val list =
            board(
                create("写代码", "1", "正在写代码"),
                create("跑测试", "2", "正在跑测试"),
                create("提交", "3"),
                update("1", "in_progress"),
                update("1", "completed"),
                update("2", "in_progress"),
            )!!
        assertEquals(listOf("写代码", "跑测试", "提交"), list.entries.map { it.content })
        assertEquals(
            listOf(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending),
            list.entries.map { it.status },
        )
        assertEquals(1, list.done)
        assertEquals("正在跑测试", list.current?.activeForm)
        assertEquals("提交", list.next?.content)
        // 没给 activeForm 时用 subject
        assertEquals("提交", list.entries[2].activeForm)
    }

    @Test
    fun createWithoutResultYetIsShownAsPending() {
        val list = board(create("一", "1"), create("二", id = null))!!
        assertEquals(listOf("一", "二"), list.entries.map { it.content })
        assertTrue(list.entries.all { it.status == TodoStatus.Pending })
    }

    @Test
    fun failedCallsAndDeletedTasksDropOut() {
        val list =
            board(
                create("一", "1"),
                step(
                    "TaskCreate",
                    """{"subject":"失败的"}""",
                    "<tool_use_error>boom</tool_use_error>",
                    ToolStatus.Failed,
                ),
                create("二", "2"),
                update("2", "deleted"),
                step(
                    "TaskUpdate",
                    """{"taskId":"1","status":"completed"}""",
                    "Task #1 not found",
                    ToolStatus.Failed,
                ),
            )!!
        assertEquals(listOf("一"), list.entries.map { it.content })
        assertEquals(TodoStatus.Pending, list.entries.single().status)
    }

    @Test
    fun updateCanRenameAndCreateUnknownTasks() {
        val list =
            board(create("旧名字", "1"), update("1", subject = "新名字"), update("7", "in_progress"))!!
        assertEquals(listOf("新名字", "任务 #7"), list.entries.map { it.content })
        // activeForm 原来跟着名字走的，改名后也跟着改
        assertEquals("新名字", list.entries[0].activeForm)
    }

    @Test
    fun newBatchAfterEverythingDoneReplacesTheOldOne() {
        val list =
            board(
                create("一", "1"),
                update("1", "completed"),
                create("二", "2"),
                update("2", "completed"),
                create("三", "3"),
                create("四", "4"),
            )!!
        assertEquals(listOf("三", "四"), list.entries.map { it.content })
    }

    @Test
    fun todoWriteIsUsedWhenThereAreNoTasks() {
        val list =
            board(
                step(
                    "TodoWrite",
                    """{"todos":[{"content":"a","status":"completed","activeForm":"A"},{"content":"b","status":"in_progress","activeForm":"B"}]}""",
                ),
                step(
                    "TodoWrite",
                    """{"todos":[{"content":"a","status":"completed","activeForm":"A"},{"content":"b","status":"completed","activeForm":"B"},{"content":"c","status":"pending","activeForm":"C"}]}""",
                ),
            )!!
        assertEquals(listOf("a", "b", "c"), list.entries.map { it.content })
        assertEquals(2, list.done)
        assertNull(list.current)
        // 任务工具的清单优先
        val mixed =
            board(
                step(
                    "TodoWrite",
                    """{"todos":[{"content":"x","status":"pending","activeForm":"X"}]}""",
                ),
                create("任务", "1"),
            )!!
        assertEquals(listOf("任务"), mixed.entries.map { it.content })
    }

    @Test
    fun noListWithoutTodoTools() {
        assertNull(board(step("Bash", """{"command":"ls"}""", "ok")))
        assertNull(TodoBoard.from(emptyList()))
        assertNull(board(step("TodoWrite", """{"todos":[]}""")))
    }

    @Test
    fun listSpansWorkGroupsAndSeed() {
        val seed = listOf(create("早先建的", "1"), update("1", "in_progress"))
        val items =
            listOf(
                WorkItem("w1", listOf(create("第二项", "2"))),
                AnswerItem("a", "好的", streaming = false),
                WorkItem("w2", listOf(update("1", "completed"), update("2", "in_progress"))),
            )
        val list = TodoBoard.from(items, seed)!!
        assertEquals(listOf("早先建的", "第二项"), list.entries.map { it.content })
        assertEquals(
            listOf(TodoStatus.Completed, TodoStatus.InProgress),
            list.entries.map { it.status },
        )
    }

    @Test
    fun seedStepsComeFromHelperEvents() {
        // zeta-helper history 在 zeta_history 行里给出的 todo_seed
        val seed =
            Json.parseToJsonElement(
                """[
              {"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"TaskCreate","input":{"subject":"First","activeForm":"Doing first"}}]}},
              {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":false,"content":"Task #1 created successfully: First"}]}},
              {"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t2","name":"TaskUpdate","input":{"taskId":"1","status":"in_progress"}}]}},
              {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","is_error":false,"content":"Updated task #1 status"}]}}
            ]""",
            ) as JsonArray
        val steps = TodoBoard.seedSteps(seed)
        assertEquals(listOf("TaskCreate", "TaskUpdate"), steps.map { it.name })
        val list = TodoBoard.from(emptyList(), steps)!!
        assertEquals("Doing first", list.current?.activeForm)
        assertTrue(TodoBoard.seedSteps(null).isEmpty())
    }
}
