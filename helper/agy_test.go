package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/agystore"
)

const (
	agyParentID = "11111111-aaaa-4444-bbbb-222222222222"
	agyChildID  = "33333333-cccc-4444-dddd-444444444444"
	claudeID    = "55555555-eeee-4444-ffff-666666666666"
)

func seedAgySession(t *testing.T, e *env) string {
	t.Helper()
	childPath := agystore.TranscriptPath(e.agy, agyChildID)
	writeLines(t, childPath,
		`{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-28T07:00:01Z","content":"<USER_REQUEST>\n检查子模块\n</USER_REQUEST>"}`,
		`{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:03Z","model":"gemini-4-flash","content":"子模块正常","input_tokens":800,"output_tokens":30}`,
	)
	agystore.WriteZetaMeta(e.agy, agyChildID, agystore.ZetaMeta{Parent: agyParentID})

	parentPath := agystore.TranscriptPath(e.agy, agyParentID)
	writeLines(t, parentPath,
		`{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-28T07:00:00Z","content":"<USER_REQUEST>\n排查登录模块关键词独特标记\n</USER_REQUEST>"}`,
		`{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:01Z","model":"gemini-4-pro","thinking":"派子代理查","input_tokens":2000,"output_tokens":90,"tool_calls":[{"id":"call-sub-1","name":"invoke_subagent","args":{"TypeName":"\"research\"","Role":"\"Researcher\""}}]}`,
		`{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","created_at":"2026-11-28T07:00:04Z","output":"conversationId: 33333333-cccc-4444-dddd-444444444444\n子模块正常"}`,
		`{"step_index":3,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:05Z","model":"gemini-4-pro","content":"登录模块已修复完毕。","input_tokens":2400,"output_tokens":50}`,
	)
	agystore.WriteZetaMeta(e.agy, agyParentID, agystore.ZetaMeta{Cwd: "/workspace/demo", Title: "登录修复"})
	return parentPath
}

func TestAgySessionListAndHistory(t *testing.T) {
	e := testEnv(t)
	parentPath := seedAgySession(t, e)
	writeLines(t, e.root+"/-demo/"+claudeID+".jsonl",
		`{"type":"user","cwd":"/workspace/demo","message":{"content":"Claude 问题"}}`,
		`{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Claude 回答"}]}}`,
	)

	listOut := mustCall(t, e, "list")
	if len(listOut) != 2 {
		t.Fatalf("list 应含父 agy 会话与 claude 会话（不含子代理）：%v", listOut)
	}
	byID := map[string]obj{}
	for _, row := range listOut {
		byID[asString(row["id"])] = row
	}
	if byID[agyParentID]["harness"] != "agy" || byID[agyParentID]["title"] != "登录修复" || byID[claudeID]["harness"] != "claude" {
		t.Fatalf("list harness/title 不对：%v", byID)
	}

	hist := mustCall(t, e, "history", parentPath)
	if len(hist) < 3 || hist[0]["type"] != "zeta_history" || hist[0]["model"] != "gemini-4-pro" {
		t.Fatalf("agy history 不对：%v", hist)
	}
}

func TestAgySearchExportSubagentRenameDelete(t *testing.T) {
	e := testEnv(t)
	parentPath := seedAgySession(t, e)

	hits := mustCall(t, e, "search", "独特标记")
	if len(hits) != 2 || hits[0]["id"] != agyParentID || hits[1]["type"] != "zeta_search_done" {
		t.Fatalf("agy search 不对：%v", hits)
	}

	exp := mustCall(t, e, "export", parentPath)
	if len(exp) != 1 || !strings.Contains(asString(exp[0]["markdown"]), "登录模块已修复完毕") {
		t.Fatalf("agy export 不对：%v", exp)
	}

	subRaw, err := subagentOutput(e, []string{"0", "0", "call-sub-1", "-", agyParentID})
	if err != nil || !strings.Contains(string(subRaw), agyChildID) || !strings.Contains(string(subRaw), "子模块正常") {
		t.Fatalf("agy subagentOutput 不对：err=%v out=%s", err, subRaw)
	}

	gotTitle, err := renameSession(parentPath, "新标题")
	if err != nil || gotTitle != "新标题" || agystore.ReadAnnotation(e.agy, agyParentID).Title != "新标题" {
		t.Fatalf("agy renameSession 不对：title=%q err=%v", gotTitle, err)
	}

	delRec, err := deleteRecord(e, parentPath)
	if err != nil || !delRec.Dir || sessionFile(e, agyParentID) != "" {
		t.Fatalf("agy deleteRecord 不对：rec=%+v err=%v", delRec, err)
	}
}

func TestAgyAccountsAndQuotaWatch(t *testing.T) {
	home := t.TempDir()
	e := newEnvAt(home, "zeta")
	writeFile(t, filepath.Join(e.agy, "antigravity-oauth-token"), "fake-agy-oauth-token-alpha")

	list := mustAccounts(t, e, "accounts.list", `{"harness":"agy"}`)
	cur, _ := list["current"].(map[string]any)
	if cur == nil || cur["subscription"] != "antigravity" {
		t.Fatalf("未保存的活跃 OAuth 令牌应识别为当前账号：%v", list)
	}

	saved := mustAccounts(t, e, "accounts.save", `{"harness":"agy"}`)
	if saved["name"] != "agy" {
		t.Fatalf("保存 agy 账号后 name = %v", saved["name"])
	}
	if afterSave := mustAccounts(t, e, "accounts.list", `{"harness":"agy"}`); currentName(afterSave) != "agy" {
		t.Fatalf("保存后 accounts.list current = %v", afterSave)
	}

	mustAccounts(t, e, "accounts.relay_save", `{"harness":"agy","name":"gw","baseUrl":"https://relay.example.com","key":"fake-key-1234","model":"gemini-4-pro","create":true}`)
	mustAccounts(t, e, "accounts.switch", `{"harness":"agy","name":"gw"}`)
	afterRelay := mustAccounts(t, e, "accounts.list", `{"harness":"agy"}`)
	if afterRelay["current"] != nil || !strings.Contains(fmt.Sprint(afterRelay["relays"]), "gw") {
		t.Fatalf("切到 agy 中转后 accounts.list = %v", afterRelay)
	}
	mustAccounts(t, e, "accounts.switch", `{"harness":"agy","name":"agy"}`)

	inProcessCore(t, e, fastWatch)
	c := startServe(t, e, testIdle)
	other := startServe(t, e, testIdle)
	c.ok("quota.watch", `"harness":"agy"`)
	c.stashed("quota.snapshot")

	usagePayload := `{"harness":"agy","response":{"rate_limits_available":true,"rate_limits":{"limits":[{"kind":"session_scoped","group":"session","percent":25,"resets_at":"2030-01-01T00:00:00Z","is_active":true,"scope":{"model":{"display_name":"Gemini"}}}]}}}`
	other.ok("quota.usage", strings.TrimSuffix(strings.TrimPrefix(usagePayload, "{"), "}"))
	c.call("quota.get", `"harness":"agy"`)
	pushes := c.stashed("quota.snapshot")
	if len(pushes) != 1 || pushes[0]["harness"] != "agy" || !strings.Contains(fmt.Sprint(pushes[0]["snapshot"]), "Gemini") {
		t.Fatalf("agy quota.snapshot 推送不对：%v", pushes)
	}
}

func TestAgySystemVersionAndUpdate(t *testing.T) {
	dir := t.TempDir()
	verFile := filepath.Join(dir, "ver")
	writeFile(t, verFile, "1.5.18")
	fakeBin := filepath.Join(dir, "fake-agy")
	script := "#!/usr/bin/env bash\n" +
		"if [[ \"${1:-}\" == \"--version\" ]]; then\n" +
		"  printf 'agy version %s\\n' \"$(cat " + verFile + ")\"\n" +
		"elif [[ \"${1:-}\" == \"update\" ]]; then\n" +
		"  printf '1.5.19' > " + verFile + "\n" +
		"  printf 'Updated agy to 1.5.19\\n'\n" +
		"fi\n"
	writeFile(t, fakeBin, script)
	if err := os.Chmod(fakeBin, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("ZETA_AGY_BIN", fakeBin)

	e := testEnv(t)
	v, err := systemAgyMethod(e, oneShotParams{})
	if err != nil || v.(obj)["version"] != "1.5.18" {
		t.Fatalf("system.agy = %v, err=%v", v, err)
	}
	u, err := systemAgyUpdateMethod(e, oneShotParams{})
	if err != nil {
		t.Fatal(err)
	}
	rep := u.(obj)
	if rep["ok"] != true || rep["before"] != "1.5.18" || rep["version"] != "1.5.19" {
		t.Fatalf("system.agy_update = %v", rep)
	}
}

func TestAgyDiagAndStorageLogs(t *testing.T) {
	e := testEnv(t)
	logDir := filepath.Join(e.agy, "log")
	writeFile(t, logDir+"/old.log", "old log")
	age(t, logDir+"/old.log", 72*time.Hour)
	writeFile(t, logDir+"/fresh.log", "fresh log")
	if err := os.Symlink(logDir+"/fresh.log", logDir+"/cli.log"); err != nil {
		t.Fatal(err)
	}
	brainPath := seedAgySession(t, e)
	age(t, brainPath, 72*time.Hour)

	v, err := diagFilesMethod(e, oneShotParams{})
	if err != nil || len(v.(diagFilesReply).Files) != 2 {
		t.Fatalf("diag.files 应只列 2 个普通 agy 日志（跳过 cli.log 链接）：%v %v", v, err)
	}
	if !diagReadable(e, logDir+"/old.log") || diagReadable(e, filepath.Join(e.agy, "antigravity-oauth-token")) {
		t.Fatal("diagReadable 边界检查不对")
	}

	keys := cleanedKeys(t, e, "agy-logs")
	if !keys["old.log"] || keys["fresh.log"] || keys["cli.log"] {
		t.Fatalf("agy-logs 候选不对：%v", keys)
	}
	cleanStorage(e, "agy-logs", nil)
	if lexists(logDir+"/old.log") || !lexists(logDir+"/fresh.log") || !lexists(brainPath) {
		t.Fatal("cleanStorage(agy-logs) 删除范围不对")
	}
}

func TestAgySessionStartHookTarget(t *testing.T) {
	id, cmd := sessionStartTarget(obj{"conversationId": agyParentID})
	if id != agyParentID || cmd != "agy --conversation" {
		t.Fatalf("conversationId 解析不对：%q %q", id, cmd)
	}
	id2, cmd2 := sessionStartTarget(obj{"session_id": claudeID, "source": "resume"})
	if id2 != claudeID || cmd2 != "claude --resume" {
		t.Fatalf("session_id 解析不对：%q %q", id2, cmd2)
	}
}
