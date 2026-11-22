package main

import (
	"os"
	"os/exec"
	"regexp"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/host"
)

// 闲置超时（多久结束闲着的进程）：经真的 serve 和核心读写，写下的值托管脚本的看门狗（zeta_host.sh 的 idle_limit）读得到。
func TestSystemIdleThroughServe(t *testing.T) {
	e := testEnv(t)
	c := startSessions(t, e)
	got := asObj(c.ok("system.idle", ""))
	if num(t, got["minutes"]) != defaultIdleMinutes {
		t.Fatalf("没设时：%v", got)
	}
	if got := asObj(c.ok("system.set_idle", `"minutes":30`)); num(t, got["minutes"]) != 30 {
		t.Fatalf("设成 30：%v", got)
	}
	if got := asObj(c.ok("system.idle", "")); num(t, got["minutes"]) != 30 {
		t.Fatalf("读回：%v", got)
	}
	if got := idleLimitInScript(t, e); got != "30" {
		t.Fatalf("看门狗读到 %q", got)
	}
	c.ok("system.set_idle", `"minutes":0`)
	if got := idleLimitInScript(t, e); got != "0" {
		t.Fatalf("不自动结束：看门狗读到 %q", got)
	}
	for _, bad := range []string{``, `"minutes":-1`, `"minutes":1441`, `"minutes":"5"`} {
		if r := c.call("system.set_idle", bad); r["ok"] != false {
			t.Fatalf("%s：%v", bad, r)
		}
	}
	if got := asObj(c.ok("system.idle", "")); num(t, got["minutes"]) != 0 {
		t.Fatalf("不合要求的没写进去：%v", got)
	}
}

// 文件没有或写坏了时，看门狗和核心都按默认的 10 分钟算（看门狗另认测试用的 ZETA_IDLE_LIMIT）。
func TestIdleLimitFallsBack(t *testing.T) {
	e := testEnv(t)
	if got := idleLimitInScript(t, e); got != "10" {
		t.Fatalf("没设：%q", got)
	}
	writeFile(t, e.idle, "半小时\n")
	if got := idleLimitInScript(t, e); got != "10" || e.idleMinutes() != defaultIdleMinutes {
		t.Fatalf("写坏了：%q %d", got, e.idleMinutes())
	}
}

// idleLimitInScript 从托管脚本里取出 idle_limit 函数，在 e 的家目录和包名下跑一次，给出它的输出。
func idleLimitInScript(t *testing.T, e *env) string {
	t.Helper()
	fn := regexp.MustCompile(`(?ms)^idle_limit\(\) \{.*?^\}`).FindString(host.Script)
	if fn == "" {
		t.Fatal("zeta_host.sh 里没有 idle_limit")
	}
	cmd := exec.Command("bash", "-c", fn+"\nidle_limit")
	cmd.Env = append(os.Environ(), "ZETA_IDLE_LIMIT=", "HOME="+e.home, "ZETA_NAME="+e.name)
	out, err := cmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(out))
}
