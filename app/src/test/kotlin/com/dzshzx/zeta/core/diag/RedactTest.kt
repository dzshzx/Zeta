package com.dzshzx.zeta.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断包打码。值都是一眼能看出是假的（fake-…），推送前的密钥扫描不会把它们当成真凭据。 */
class RedactTest {
    private val mask = Redact.MASK

    private fun redacted(text: String): String = Redact.text(text).also { out ->
        // 打过码的再打一遍不变
        assertEquals("应当幂等：$out", out, Redact.text(out))
    }

    private fun assertHidden(text: String, vararg secrets: String) {
        val out = redacted(text)
        for (secret in secrets) assertFalse("没打掉 $secret：$out", out.contains(secret))
        assertTrue("应有打码标记：$out", out.contains(mask))
    }

    @Test
    fun authorizationHeaderKeepsScheme() {
        assertEquals("Authorization: Bearer $mask", redacted("Authorization: Bearer fake-token-1"))
        assertEquals("authorization=Basic $mask", redacted("authorization=Basic fake-basic-1"))
        assertEquals("Proxy-Authorization: $mask", redacted("Proxy-Authorization: fake-token-2"))
        assertEquals("""{"Authorization":"Bearer $mask","Accept":"*/*"}""", redacted("""{"Authorization":"Bearer fake-token-3","Accept":"*/*"}"""))
        // 拆开写，免得推送前的密钥扫描把测试语料当成真的 curl 凭据
        val header = "Authorization: Bearer"
        assertEquals("curl -H '$header $mask' https://example.invalid", redacted("curl -H '$header fake-token-4' https://example.invalid"))
    }

    @Test
    fun cookiesHideTheWholeValue() {
        assertEquals("Cookie: $mask", redacted("Cookie: sid=fake-cookie-1; theme=dark"))
        assertEquals("set-cookie: $mask\nnext line", redacted("set-cookie: sid=fake-cookie-2; Path=/; HttpOnly\nnext line"))
        assertEquals(""""cookie": "$mask"""", redacted(""""cookie": "sid=fake-cookie-3""""))
        assertHidden("session_cookie=fake-cookie-4", "fake-cookie-4")
        // 只是提到这个词的不动
        assertEquals("the cookies jar is empty", redacted("the cookies jar is empty"))
    }

    @Test
    fun secretKeyValuePairs() {
        assertEquals("ANTHROPIC_API_KEY=$mask", redacted("ANTHROPIC_API_KEY=fake-key-1"))
        assertEquals("export GH_TOKEN=$mask", redacted("export GH_TOKEN=fake-token-5"))
        assertEquals(""""password": "$mask"""", redacted(""""password": "fake-pass-1""""))
        assertEquals("api_key: $mask", redacted("api_key: fake-key-2"))
        assertEquals("""{"accessToken":"$mask","expiresAt":1700000000000}""", redacted("""{"accessToken":"fake-token-6","expiresAt":1700000000000}"""))
        assertEquals("https://example.invalid/cb?code=1&access_token=$mask&state=x", redacted("https://example.invalid/cb?code=1&access_token=fake-token-7&state=x"))
        assertEquals("client_secret = '$mask'", redacted("client_secret = 'fake-secret-1'"))
        assertEquals("X-Api-Key: $mask", redacted("X-Api-Key: fake-key-3"))
        // 不像机密的键，值里嵌着的照样打
        assertEquals("url: https://example.invalid/?token=$mask", redacted("url: https://example.invalid/?token=fake-token-12"))
        assertEquals(""""note": "set password=$mask later"""", redacted(""""note": "set password=fake-pass-4 later""""))
    }

    @Test
    fun escapedJsonInsideALogLine() {
        val line = """{"type":"log","text":"{\"refreshToken\":\"fake-token-8\",\"user\":\"bob\"}"}"""
        assertEquals("""{"type":"log","text":"{\"refreshToken\":\"$mask\",\"user\":\"bob\"}"}""", redacted(line))
    }

    @Test
    fun countsAndFlagsAreKept() {
        val usage = """{"input_tokens":1234,"output_tokens":56,"cache_read_input_tokens":0}"""
        assertEquals(usage, redacted(usage))
        assertEquals("""{"hasToken":true,"apiKey":null,"password":""}""", redacted("""{"hasToken":true,"apiKey":null,"password":""}"""))
        assertEquals("PWD=/workspace/claude SHELL=/bin/bash", redacted("PWD=/workspace/claude SHELL=/bin/bash"))
        assertEquals("sessionId: 0a1b2c3d author: someone", redacted("sessionId: 0a1b2c3d author: someone"))
        assertEquals("""{"token_type":"bearer","max_tokens":4096}""", redacted("""{"token_type":"bearer","max_tokens":4096}"""))
    }

    @Test
    fun commandLineFlags() {
        assertEquals("claude mcp add x --api-key $mask --verbose", redacted("claude mcp add x --api-key fake-key-4 --verbose"))
        assertEquals("tool --token=$mask -p", redacted("tool --token=fake-token-9 -p"))
        assertEquals("tool --password '$mask'", redacted("tool --password 'fake-pass-2'"))
        // 后面跟的是另一个参数，不是值
        assertEquals("tool --verbose --model opus", redacted("tool --verbose --model opus"))
        assertEquals("tool --verbose --token $mask", redacted("tool --verbose --token fake-token-10"))
    }

    @Test
    fun urlPasswords() {
        assertEquals("git clone https://someone:$mask@example.invalid/x.git", redacted("git clone https://someone:fake-pass-3@example.invalid/x.git"))
        // 端口不是密码
        assertEquals("http://localhost:8080/a@b", redacted("http://localhost:8080/a@b"))
    }

    @Test
    fun recognisableTokenShapesAnywhere() {
        assertEquals("key is sk-ant-$mask here", redacted("key is sk-ant-fake-token-0001 here"))
        assertEquals("sk-$mask", redacted("sk-fakefakefakefake0001"))
        assertEquals("sent Bearer $mask ok", redacted("sent Bearer fake.token.value-11 ok"))
        assertEquals("gh $mask x", redacted("gh ghp_fakefakefakefake01 x"))
        assertEquals("slack $mask", redacted("slack xoxb-fake-fake-01"))
        assertEquals("jwt $mask end", redacted("jwt eyJfake.eyJfake.fakesig end"))
        // 普通的词不误伤
        assertEquals("task-runner desk-lamp skip-it", redacted("task-runner desk-lamp skip-it"))
    }

    @Test
    fun privateKeyBlocks() {
        // 拆开写，理由同上
        val kind = "OPENSSH PRIVATE"
        val begin = "-----BEGIN $kind KEY-----"
        val end = "-----END $kind KEY-----"
        val text = "before\n$begin\nfake-key-material-1\nfake-key-material-2\n$end\nafter"
        assertEquals("before\n$begin\n$mask\n$end\nafter", redacted(text))
        // 尾部截断、没有 END 的也打到末尾
        val bare = "PRIVATE"
        assertHidden("-----BEGIN $bare KEY-----\nfake-key-material-3", "fake-key-material-3")
    }

    @Test
    fun secretNames() {
        for (name in listOf("token", "GITHUB_TOKEN", "oauthToken", "x-api-key", "apiKey", "client_secret", "PGPASSWORD", "aws_secret_access_key", "credentials", "basic_auth")) {
            assertTrue(name, Redact.secretName(name))
        }
        for (name in listOf("tokens", "input_tokens", "author", "key", "monkey", "PWD", "sessionId", "token_type", "path")) {
            assertFalse(name, Redact.secretName(name))
        }
    }

    @Test(timeout = 5_000)
    fun stackTraceFromStderr() {
        val text = "# zeta: claude 退出码 1，2026-11-19 09:59:50 +0800\n" +
            "[ERROR] MCP server \"github\" connection failed: Authorization: Bearer fake-token-1\n" +
            "Error: spawn npx ENOENT\n    at ChildProcess._handle.onexit (node:internal/child_process:285:19)"
        val out = redacted(text)
        assertFalse(out, out.contains("fake-token-1"))
        assertTrue(out, out.contains("(node:internal/child_process:285:19)"))
    }

    @Test
    fun plainTextUntouched() {
        val text = "2026-11-19 10:00:00.123 信息 [Claude 进程] 起 Claude 进程（cwd /workspace/claude，--model opus）\nexit code 143"
        assertEquals(text, redacted(text))
    }
}
