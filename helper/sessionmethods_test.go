package main

import (
	"encoding/json"
	"fmt"
	"os"
	"slices"
	"strings"
	"syscall"
	"testing"
	"time"
)

// 核心的 sessions.* 方法：经真的 serve 和核心走，临时 HOME 里有真的会话记录。

// startSessions 起核心和一个 serve，扮侧栏的客户端。
func startSessions(t *testing.T, e *env) *serveClient {
	t.Helper()
	inProcessCore(t, e, fastWatch)
	return startServe(t, e, testIdle)
}

// upserted：会话列表的增量里有 id 这一项，项满足 want。
func upserted(id string, want func(obj) bool) func(obj) bool {
	return func(m obj) bool {
		for _, it := range asList(m["upsert"]) {
			if item := asObj(it); item["id"] == id && want(item) {
				return true
			}
		}
		return false
	}
}

// 侧栏的一串操作：改名、归档、按会话号查、导出、搜索、删除都经核心，订阅着的列表跟着变。
func TestSessionsMethodsThroughServe(t *testing.T) {
	e := testEnv(t)
	calls := fakeTmux(t, true, func(args []string) (string, error) {
		if args[0] == "list-windows" {
			return "@4 " + sessionA, nil
		}
		return "", nil
	})
	a := writeSession(t, e, sessionA, "说说天气")
	age(t, a, time.Hour)
	writeSession(t, e, sessionB, "另一个话题")
	c := startSessions(t, e)
	c.ok("sessions.watch", "")
	c.nextWhere("zeta_sessions", func(m obj) bool { return m["full"] == true })

	// 改名：写进记录，应答带实际写下的标题，列表推来新标题
	if got := c.ok("sessions.rename", `"session":"`+sessionA+`","title":"  天气\t预报 "`); asObj(got)["title"] != "天气 预报" {
		t.Fatalf("改名：%v", got)
	}
	c.nextWhere("zeta_sessions", upserted(sessionA, func(it obj) bool { return it["title"] == "天气 预报" }))

	// 归档：列表推来 archived，它的 tmux 窗口一并关掉；取消归档再推回来
	if got := c.ok("sessions.archive", `"session":"`+sessionA+`","archived":true`); asObj(got)["archived"] != true {
		t.Fatalf("归档：%v", got)
	}
	c.nextWhere("zeta_sessions", upserted(sessionA, func(it obj) bool { return it["archived"] == true }))
	if !slices.Contains(*calls, "kill-window -t @4") {
		t.Fatalf("归档没关窗口：%v", *calls)
	}

	// 按会话号查：每项和列表推来的一项相同，找不到的不在里面
	got := asList(c.ok("sessions.lookup", `"sessions":["`+sessionA+`","cccccccc-0000-0000-0000-000000000003"]`))
	if len(got) != 1 {
		t.Fatalf("查：%v", got)
	}
	item := asObj(got[0])
	listed := lookupSessions(e, []string{sessionA})[0]
	if compactJSON(item) != compactJSON(roundTrip(t, listed)) || item["archived"] != true || item["title"] != "天气 预报" {
		t.Fatalf("查到的 %v，列表里的 %v", item, listed)
	}
	c.ok("sessions.archive", `"session":"`+sessionA+`","archived":false`)
	c.nextWhere("zeta_sessions", upserted(sessionA, func(it obj) bool { return it["archived"] == false }))

	// 导出：正文和建议的文件名
	exported := asObj(c.ok("sessions.export", `"session":"`+sessionA+`"`))
	if !strings.HasPrefix(asString(exported["markdown"]), "# 天气 预报\n\n## 用户\n\n说说天气") || exported["title"] != "天气 预报" ||
		!strings.HasPrefix(asString(exported["file_name"]), "Zeta-天气 预报-") || !strings.HasSuffix(asString(exported["file_name"]), ".md") {
		t.Fatalf("导出：%v", exported)
	}

	// 搜索：命中边找边推，应答只有汇总
	result := asObj(c.ok("sessions.search", `"query":"话题"`))
	if result["matches"] != nil || num(t, result["matched"]) != 1 ||
		num(t, result["scanned"]) != 2 || num(t, result["total"]) != 2 || result["complete"] != true {
		t.Fatalf("搜索：%v", result)
	}
	if m := c.stashed("sessions.match"); len(m) != 1 || m[0]["id"] != sessionB || m[0]["role"] != "user" || fmt.Sprint(m[0]["search"]) != fmt.Sprint(c.seq) {
		t.Fatalf("搜索的推送：%v", m)
	}

	// 删除：记录和同名目录都没了，窗口关掉，列表推来 remove
	writeFile(t, e.root+"/-w/"+sessionA+"/subagents/agent-1.jsonl", "{}\n")
	deleted := asObj(c.ok("sessions.delete", `"session":"`+sessionA+`"`))
	if d := asList(deleted["deleted"]); len(d) != 1 || asObj(d[0])["file"] != a || asObj(d[0])["dir"] != true || num(t, deleted["closed"]) != 1 {
		t.Fatalf("删除：%v", deleted)
	}
	if lexists(a) || lexists(e.root+"/-w/"+sessionA) {
		t.Fatal("记录或同名目录还在")
	}
	c.nextWhere("zeta_sessions", func(m obj) bool { return slices.Contains(strs(m["remove"]), sessionA) })

	// 已经删了：改名、导出答 no_session，再删不算出错
	for _, method := range []string{"sessions.rename", "sessions.export"} {
		if r := c.call(method, `"session":"`+sessionA+`","title":"x"`); r["ok"] != false || r["code"] != "no_session" {
			t.Fatalf("%s 删掉的会话：%v", method, r)
		}
	}
	if again := asObj(c.ok("sessions.delete", `"session":"`+sessionA+`"`)); len(asList(again["deleted"])) != 0 {
		t.Fatalf("再删：%v", again)
	}
}

// roundTrip 把 v 编成 JSON 再按客户端那样读回来（数字是 json.Number）。
func roundTrip(t *testing.T, v any) obj {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	m, ok := parseObject(data)
	if !ok {
		t.Fatalf("not an object: %s", data)
	}
	return m
}

func TestSessionsMethodsRejectBadParams(t *testing.T) {
	e := testEnv(t)
	c := startSessions(t, e)
	for _, req := range [][2]string{
		{"sessions.rename", `"session":"../x","title":"t"`},
		{"sessions.delete", ``},
		{"sessions.desk_open", `"session":"a b"`},
		{"sessions.lookup", `"sessions":["ok","../x"]`},
		{"sessions.search", `"query":"  "`},
		{"sessions.image", `"ref":"0.0"`},
		{"sessions.nope", `"session":"` + sessionA + `"`},
		{"sessions.rename", `"session":1`},
	} {
		if r := c.call(req[0], req[1]); r["ok"] != false || r["code"] != nil {
			t.Fatalf("%s %s：%v", req[0], req[1], r)
		}
	}
	writeSession(t, e, sessionA, "一")
	if r := c.call("sessions.rename", `"session":"`+sessionA+`","title":" \t "`); r["ok"] != false {
		t.Fatalf("空标题：%v", r)
	}
}

func TestSessionsImageReadsTheRecord(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-w/" + sessionA + ".jsonl"
	writeLines(t, path,
		`{"type":"user","cwd":"/w","message":{"content":[{"type":"text","text":"看图"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}]}}`)
	outside := t.TempDir() + "/x.jsonl"
	writeFile(t, outside, "{}\n")
	c := startSessions(t, e)
	img := asObj(c.ok("sessions.image", `"file":`+quote(path)+`,"ref":"0.1"`))
	if img["media_type"] != "image/png" || img["data"] != "aGk=" {
		t.Fatalf("图：%v", img)
	}
	if r := c.call("sessions.image", `"file":`+quote(path)+`,"ref":"0.0"`); r["ok"] != false || r["code"] != nil {
		t.Fatalf("不是图的块：%v", r)
	}
	if r := c.call("sessions.image", `"file":`+quote(outside)+`,"ref":"0.0"`); r["ok"] != false || r["code"] != nil {
		t.Fatalf("记录目录外的文件：%v", r)
	}
	if r := c.call("sessions.image", `"file":`+quote(e.root+"/-w/gone.jsonl")+`,"ref":"0.1"`); r["code"] != "no_session" {
		t.Fatalf("记录不在：%v", r)
	}
}

// 别处占用与接管：终端里的 Claude Code 闲着时接得过来，它照 /exit 退出。
func TestSessionsOccupantAndHandoff(t *testing.T) {
	e := testEnv(t)
	pid, done := tui(t, false)
	registerStatus(t, e, pid, handoffID, "idle", "")
	c := startSessions(t, e)
	if occ := asObj(c.ok("sessions.occupant", `"session":"`+handoffID+`"`)); occ["status"] != "idle" || occ["hands_over"] != true {
		t.Fatalf("占用：%v", occ)
	}
	if handed := asObj(c.ok("sessions.handoff", `"session":"`+handoffID+`"`)); handed["handed"] != true || !exited(done) {
		t.Fatalf("接管：%v", handed)
	}
	if occ := asObj(c.ok("sessions.occupant", `"session":"`+handoffID+`"`)); occ["status"] != nil {
		t.Fatalf("接过来以后还有人占着：%v", occ)
	}
}

// tmux 窗口：工作目录照会话记录；还没有记录时是家目录；关掉的只是它的窗口。
func TestSessionsDeskWindows(t *testing.T) {
	e := testEnv(t)
	writeSession(t, e, sessionA, "一")
	windows := map[string]string{}
	calls := fakeTmux(t, true, func(args []string) (string, error) {
		switch args[0] {
		case "list-windows":
			var lines []string
			for id, w := range windows {
				lines = append(lines, w+" "+id)
			}
			return strings.Join(lines, "\n"), nil
		case "has-session":
			return "", os.ErrNotExist
		case "new-session":
			return "@9", nil
		case "set-option":
			if args[4] == "@zeta_session" {
				windows[args[5]] = args[3]
			}
		}
		return "", nil
	})
	c := startSessions(t, e)
	if got := asObj(c.ok("sessions.desk_open", `"session":"`+sessionA+`"`)); got["window"] != "@9" {
		t.Fatalf("开窗口：%v", got)
	}
	if joined := strings.Join(*calls, "\n"); !strings.Contains(joined, "-n aaaaaaaa -c /w ") {
		t.Fatalf("工作目录不对：\n%s", joined)
	}
	c.ok("sessions.desk_open", `"session":"`+sessionB+`"`)
	if joined := strings.Join(*calls, "\n"); !strings.Contains(joined, "-n bbbbbbbb -c "+e.home+" ") {
		t.Fatalf("没有记录时不在家目录：\n%s", joined)
	}
	if got := asObj(c.ok("sessions.desk_close", `"session":"`+sessionA+`"`)); num(t, got["closed"]) != 1 {
		t.Fatalf("关窗口：%v", got)
	}
}

func TestSessionsDeskOpenWithoutTmux(t *testing.T) {
	e := testEnv(t)
	fakeTmux(t, false, func([]string) (string, error) { return "", os.ErrNotExist })
	c := startSessions(t, e)
	if got := asObj(c.ok("sessions.desk_open", `"session":"`+sessionA+`"`)); got["window"] != nil {
		t.Fatalf("没有 tmux：%v", got)
	}
}

// endlessRecord 把会话记录 path 做成读不完的管道：每来一个读的一方，就一直往里写不会命中的行，直到它不读了（关掉）。
// 每有一个开始读往 opened 发一次，读的一方都走了往 closed 发一次。
func endlessRecord(t *testing.T, path string) (opened, closed <-chan struct{}) {
	t.Helper()
	if err := syscall.Mkfifo(path, 0o644); err != nil {
		t.Fatal(err)
	}
	o, c := make(chan struct{}, 4), make(chan struct{}, 4)
	stop := make(chan struct{})
	go func() {
		line := []byte(`{"type":"user","message":{"content":"无关的话"}}` + "\n")
		for {
			f, err := os.OpenFile(path, os.O_WRONLY, 0) // 等到有读的一方
			if err != nil {
				return
			}
			select {
			case <-stop:
				f.Close()
				return
			default:
			}
			o <- struct{}{}
			for err == nil {
				_, err = f.Write(line)
			}
			f.Close()
			c <- struct{}{}
		}
	}()
	t.Cleanup(func() {
		close(stop)
		// 写的一方还在等读的一方时，开一下让它往下走、看到 stop
		if f, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0); err == nil {
			f.Close()
		}
	})
	return o, c
}

func receive(t *testing.T, ch <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(5 * time.Second):
		t.Fatalf("5 秒内没等到%s", what)
	}
}

// 搜索能取消：同一个客户端发来新的搜索时上一个停下（照样答汇总，complete 为假）；客户端断开时正在跑的停下。
// 读不完的记录（endlessRecord）里搜索只会因为被取消停下：时限改长，个数限成 1 个，让命中的那次搜索不去读它。
func TestSessionsSearchCancels(t *testing.T) {
	oldTime, oldLimit := searchTime, searchLimit
	searchTime, searchLimit = time.Minute, 1
	t.Cleanup(func() { searchTime, searchLimit = oldTime, oldLimit })
	e := testEnv(t)
	// 往管道里写会更新它的修改时刻：命中的那份摆到将来，排在它前面
	age(t, writeSession(t, e, sessionB, "说说天气"), -time.Hour)
	opened, closed := endlessRecord(t, e.root+"/-w/"+sessionA+".jsonl")
	c := startSessions(t, e)

	c.send(`{"id":"old","method":"sessions.search","query":"找不到的词"}`)
	receive(t, opened, "搜索读到那份记录")
	if r := asObj(c.ok("sessions.search", `"query":"天气"`)); num(t, r["matched"]) != 1 {
		t.Fatalf("新的搜索：%v", r)
	}
	old := c.nextWhere("zeta_reply", func(m obj) bool { return m["id"] == "old" })
	if r := asObj(old["result"]); old["ok"] != true || r["complete"] != false || num(t, r["scanned"]) != 2 {
		t.Fatalf("被新的搜索取消的那次：%v", old)
	}
	receive(t, closed, "被取消的搜索放开记录")

	c.send(`{"id":"gone","method":"sessions.search","query":"找不到的词"}`)
	receive(t, opened, "又一次搜索读到那份记录")
	if err := c.in.Close(); err != nil {
		t.Fatal(err)
	}
	receive(t, closed, "客户端断开后搜索放开记录")
}

// 会话在核心里开着时，改名经那个对话（标题跟着变）；删除先结束它的进程，记录照删。
func TestSessionsMethodsReachOpenConversation(t *testing.T) {
	e0, _ := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fakeClaudeIn(t, e, `{"steps":[{"text":"好"}]}`)
	c := startServe(t, e, testIdle)
	c.ok("conv.open", `"conv":"a","cwd":`+quote(e.home))
	c.ok("conv.send", `"conv":"a","text":"你好"`)
	state := c.nextWhere("conv.state", func(m obj) bool { return asString(m["session"]) != "" })
	session := asString(state["session"])
	c.nextWhere("conv.turn", func(m obj) bool { return m["phase"] == "finished" })
	// 假 Claude 不写会话记录，这里摆一份
	record := writeSession(t, e, session, "你好")

	if got := asObj(c.ok("sessions.rename", `"session":`+quote(session)+`,"title":"新 名字"`)); got["title"] != "新 名字" {
		t.Fatalf("改名：%v", got)
	}
	c.nextWhere("conv.state", func(m obj) bool { return m["title"] == "新 名字" })

	deleted := asObj(c.ok("sessions.delete", `"session":`+quote(session)))
	if d := asList(deleted["deleted"]); len(d) != 1 || asObj(d[0])["file"] != record || lexists(record) {
		t.Fatalf("删除：%v", deleted)
	}
	c.nextWhere("conv.state", func(m obj) bool { return m["process"] == false })
}
