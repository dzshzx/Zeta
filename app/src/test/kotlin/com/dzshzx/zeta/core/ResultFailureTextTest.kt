package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 一轮出错结束时聊天里那条提示（[resultFailureText]）取哪段文字。 */
class ResultFailureTextTest {
    private fun result(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun prefersResultText() {
        assertEquals(
            "API Error: 400",
            resultFailureText(
                result(
                    """{"type":"result","subtype":"success","is_error":true,"result":"API Error: 400","errors":["别的"]}""",
                ),
            ),
        )
    }

    @Test
    fun joinsErrorsWhenResultIsBlank() {
        assertEquals(
            "第一条\n第二条",
            resultFailureText(
                result(
                    """{"type":"result","subtype":"error_during_execution","result":" ","errors":["第一条","第二条"]}""",
                ),
            ),
        )
    }

    @Test
    fun fallsBackToSubtype() {
        assertEquals(
            "这一轮没有正常结束（error_max_turns）",
            resultFailureText(
                result("""{"type":"result","subtype":"error_max_turns","errors":[]}"""),
            ),
        )
        assertEquals(
            "这一轮没有正常结束（null）",
            resultFailureText(result("""{"type":"result","is_error":true}""")),
        )
    }
}
