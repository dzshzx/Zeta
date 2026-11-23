package main

import (
	"fmt"
	"os"
	"slices"
	"strings"
	"testing"
	"time"
)

// 收进核心的会话规则（置顶、几个会话一起导出、删进行中的会话、搜索的名字、主机名），经真的 serve 和核心走。

// 置顶记在核心里：列表每一项带 pinned，置顶的会话不在最新的 listLimit 个里也列出来；几个客户端看到同一份。
func TestSessionsPinKeepsFarSessionsListed(t *testing.T) {
	e := testEnv(t)
	ids := make([]string, listLimit+1)
	for i := range ids {
		ids[i] = fmt.Sprintf("%08d-0000-0000-0000-000000000000", i)
		// ids[0] 最旧，挤在最新的 listLimit 个外面
		age(t, writeSession(t, e, ids[i], "第"+fmt.Sprint(i)+"个"), time.Duration(len(ids)-i)*time.Minute)
	}
	far := ids[0]
	c := startSessions(t, e)
	c.ok("sessions.watch", "")
	full := c.nextWhere("zeta_sessions", func(m obj) bool { return m["full"] == true })
	if items := asList(full["upsert"]); len(items) != listLimit || asObj(items[0])["pinned"] != false {
		t.Fatalf("整份 %d 项：%v", len(items), asObj(items[0]))
	}

	if got := asObj(c.ok("sessions.pin", `"session":"`+far+`","pinned":true`)); got["pinned"] != true {
		t.Fatalf("置顶：%v", got)
	}
	c.nextWhere("zeta_sessions", upserted(far, func(it obj) bool { return it["pinned"] == true }))
	if got := asList(c.ok("sessions.lookup", `"sessions":["`+far+`"]`)); len(got) != 1 || asObj(got[0])["pinned"] != true {
		t.Fatalf("查：%v", got)
	}

	// 另一个客户端订阅时整份里就有它，排在最后
	other := startServe(t, e, testIdle)
	other.ok("sessions.watch", "")
	items := asList(other.nextWhere("zeta_sessions", func(m obj) bool { return m["full"] == true })["upsert"])
	if last := asObj(items[len(items)-1]); len(items) != listLimit+1 || last["id"] != far || last["pinned"] != true {
		t.Fatalf("另一个客户端的整份 %d 项，最后一项 %v", len(items), last)
	}

	// 取消置顶：又挤出去了
	c.ok("sessions.pin", `"session":"`+far+`","pinned":false`)
	c.nextWhere("zeta_sessions", func(m obj) bool { return slices.Contains(strs(m["remove"]), far) })
	if e.pinnedSessions()[far] {
		t.Fatal("取消置顶后标记还在")
	}
}

// 置顶的标记和归档一样：顺手清掉记录已经不在的，坏的会话号答出错。
func TestSessionsPinDropsGoneSessions(t *testing.T) {
	e := testEnv(t)
	writeSession(t, e, sessionA, "一")
	writeSession(t, e, sessionB, "二")
	c := startSessions(t, e)
	c.ok("sessions.pin", `"session":"`+sessionA+`","pinned":true`)
	c.ok("sessions.pin", `"session":"`+sessionB+`","pinned":true`)
	if err := os.Remove(e.root + "/-w/" + sessionA + ".jsonl"); err != nil {
		t.Fatal(err)
	}
	c.ok("sessions.pin", `"session":"`+sessionB+`","pinned":true`)
	if got := readText(e.pinned); got != sessionB {
		t.Fatalf("置顶文件：%q", got)
	}
	if r := c.call("sessions.pin", `"session":"../x","pinned":true`); r["ok"] != false {
		t.Fatalf("坏的会话号：%v", r)
	}
}

// 几个会话由核心合成一份：按给的顺序，导不出来的跳过，文件名的时刻照客户端的时区。
func TestSessionsExportSeveral(t *testing.T) {
	e := testEnv(t)
	writeSession(t, e, sessionA, "说说天气")
	writeSession(t, e, sessionB, "另一个话题")
	gone := "cccccccc-0000-0000-0000-000000000003"
	c := startSessions(t, e)

	got := asObj(c.ok("sessions.export", `"sessions":["`+sessionB+`","`+gone+`","`+sessionA+`","`+sessionB+`"],"utc_offset":-720`))
	one, _, _ := exportSession(e.root+"/-w/"+sessionB+".jsonl", "")
	two, twoTitle, _ := exportSession(e.root+"/-w/"+sessionA+".jsonl", "")
	want := strings.TrimRight(one, "\n") + "\n\n---\n\n" + strings.TrimRight(two, "\n") + "\n"
	if got["markdown"] != want || got["title"] != "2个对话" {
		t.Fatalf("合成的：%q，要 %q（%v）", got["markdown"], want, got["title"])
	}
	if strs(got["exported"])[0] != sessionB || len(strs(got["exported"])) != 2 || !slices.Equal(strs(got["failed"]), []string{gone}) {
		t.Fatalf("导出成的、没导出成的：%v %v", got["exported"], got["failed"])
	}
	// 各自的导出按给的先后另放在 parts 里，客户端拿它们按用户选的先后重新合成
	parts := asList(got["parts"])
	if len(parts) != 2 || asObj(parts[0])["session"] != sessionB || asObj(parts[0])["markdown"] != one ||
		asObj(parts[1])["session"] != sessionA || asObj(parts[1])["markdown"] != two || asObj(parts[1])["title"] != twoTitle {
		t.Fatalf("parts：%v", parts)
	}
	day := time.Now().UTC().Add(-12 * time.Hour).Format("20060102-1504")
	if name := asString(got["file_name"]); name != "Zeta-2个对话-"+day+".md" {
		// 跨了一分钟时再算一次
		if later := time.Now().UTC().Add(-12 * time.Hour).Format("20060102-1504"); name != "Zeta-2个对话-"+later+".md" {
			t.Fatalf("文件名：%q，要 %s", name, day)
		}
	}

	// 只导出成一个时和单个导出一样
	single := asObj(c.ok("sessions.export", `"session":"`+sessionA+`"`))
	if only := asObj(c.ok("sessions.export", `"sessions":["`+gone+`","`+sessionA+`"]`)); only["markdown"] != single["markdown"] || only["title"] != single["title"] {
		t.Fatalf("只成一个：%v", only)
	}
	if r := c.call("sessions.export", `"sessions":["`+gone+`"]`); r["ok"] != false || r["code"] != "no_session" {
		t.Fatalf("一个都不在：%v", r)
	}
	if r := c.call("sessions.export", `"sessions":["`+sessionA+`","../x"]`); r["ok"] != false || r["code"] != nil {
		t.Fatalf("坏的会话号：%v", r)
	}
}

// 正在进行的会话不删：Zeta 之外的进程占着、本包托管的进程正忙时答 active，记录还在；闲下来以后照删。
func TestSessionsDeleteRefusesActiveSessions(t *testing.T) {
	e := testEnv(t)
	record := writeSession(t, e, handoffID, "一")
	c := startSessions(t, e)

	pid, done := tui(t, false)
	registerStatus(t, e, pid, handoffID, "idle", "")
	if r := c.call("sessions.delete", `"session":"`+handoffID+`"`); r["ok"] != false || r["code"] != "active" || !lexists(record) {
		t.Fatalf("终端里开着：%v", r)
	}
	c.ok("sessions.handoff", `"session":"`+handoffID+`"`)
	if !exited(done) {
		t.Fatal("终端的进程没退")
	}

	_, hostPid, _ := hosted(t, e, e.name, handoffID, turnOn...)
	registerStatus(t, e, hostPid, handoffID, "busy", "")
	if r := c.call("sessions.delete", `"session":"`+handoffID+`"`); r["code"] != "active" || !lexists(record) {
		t.Fatalf("本包托管的正忙：%v", r)
	}
	registerStatus(t, e, hostPid, handoffID, "idle", "")
	if d := asObj(c.ok("sessions.delete", `"session":"`+handoffID+`"`)); len(asList(d["deleted"])) != 1 || lexists(record) {
		t.Fatalf("闲着时删：%v", d)
	}
}

// 核心里开着的对话正在回答时不删，答完以后照删。
func TestSessionsDeleteWaitsForTheTurn(t *testing.T) {
	e0, _ := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fake := fakeClaudeIn(t, e, `{"steps":[{"text":"前半","chunk":8,"delay":1},{"wait":"go","ms":20000},{"text":"后半"}]}`)
	c := startServe(t, e, testIdle)
	c.ok("conv.open", `"conv":"a","cwd":`+quote(e.home))
	c.ok("conv.send", `"conv":"a","text":"你好"`)
	session := asString(c.nextWhere("conv.state", func(m obj) bool { return asString(m["session"]) != "" })["session"])
	record := writeSession(t, e, session, "你好")
	c.nextWhere("conv.items", func(m obj) bool { return strings.Contains(compactJSON(m), "前半") })

	if r := c.call("sessions.delete", `"session":`+quote(session)); r["ok"] != false || r["code"] != "active" || !lexists(record) {
		t.Fatalf("回答中：%v", r)
	}
	if err := os.WriteFile(fake+"/marks/go", nil, 0o644); err != nil {
		t.Fatal(err)
	}
	c.nextWhere("conv.turn", func(m obj) bool { return m["phase"] == "finished" })
	if d := asObj(c.ok("sessions.delete", `"session":`+quote(session))); len(asList(d["deleted"])) != 1 || lexists(record) {
		t.Fatalf("答完以后：%v", d)
	}
}

// 搜索的推送带客户端起的名字；没起名字时带请求的 id。
func TestSessionsSearchNamedByClient(t *testing.T) {
	e := testEnv(t)
	writeSession(t, e, sessionA, "说说天气")
	c := startSessions(t, e)
	c.ok("sessions.search", `"query":"天气","search":"第一次"`)
	if m := c.stashed("sessions.match"); len(m) != 1 || m[0]["search"] != "第一次" || m[0]["id"] != sessionA {
		t.Fatalf("带名字的：%v", m)
	}
	c.ok("sessions.search", `"query":"天气","search":null`)
	if m := c.stashed("sessions.match"); len(m) != 1 || fmt.Sprint(m[0]["search"]) != fmt.Sprint(c.seq) {
		t.Fatalf("没带名字的：%v", m)
	}
}

// 报了英文界面的客户端：导出里核心自己写的字和出错应答都按它的语言写，种类码照旧。
func TestSessionsExportInClientLanguage(t *testing.T) {
	e := testEnv(t)
	writeSession(t, e, sessionA, "说说天气")
	writeSession(t, e, sessionB, "另一个话题")
	c := startSessions(t, e)
	c.ok("core.client", `"lang":"en"`)

	got := asObj(c.ok("sessions.export", `"sessions":["`+sessionA+`","`+sessionB+`"]`))
	if got["title"] != "2 conversations" || !strings.Contains(asString(got["markdown"]), "## User\n\n说说天气") ||
		!strings.HasPrefix(asString(got["file_name"]), "Zeta-2 conversations-") {
		t.Fatalf("英文的导出：%v %q %v", got["title"], got["markdown"], got["file_name"])
	}
	gone := "cccccccc-0000-0000-0000-000000000003"
	if r := c.call("sessions.export", `"session":"`+gone+`"`); r["error"] != "This session's transcript no longer exists" || r["code"] != "no_session" {
		t.Fatalf("英文的出错：%v", r)
	}
}
