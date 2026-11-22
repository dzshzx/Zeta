package hub

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
)

// 侧栏的会话操作对着真的 serve 和核心（经 sshtest）：列表、搜索（边找边推的命中）、取图、改名、归档、导出、取回不在列表里的置顶、
// 删除，改完的结果经 sessions.watch 推回来。
func TestSessionOperationsThroughCore(t *testing.T) {
	e := newE2E(t, `{"steps":[{"text":"好"}]}`+"\n")
	h := e.hub
	const (
		login = "aaaaaaaa-0000-4000-8000-000000000001"
		other = "aaaaaaaa-0000-4000-8000-000000000002"
		gone  = "aaaaaaaa-0000-4000-8000-000000000099" // 置顶着，那边已经没有了
	)
	dir := filepath.Join(e.server.Home, ".claude/projects/-w")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	transcript := func(id, line string) string {
		path := filepath.Join(dir, id+".jsonl")
		if err := os.WriteFile(path, []byte(line+"\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		return path
	}
	// 第 0 行第 1 块是一张 PNG（数据是 "png!" 的 base64）
	loginFile := transcript(login, `{"type":"user","cwd":"/w/proj","sessionId":"`+login+`","message":{"role":"user","content":[`+
		`{"type":"text","text":"改一下登录页的 NEEDLE 校验"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"cG5nIQ=="}}]}}`)
	transcript(other, `{"type":"user","cwd":"/w/proj","sessionId":"`+other+`","message":{"role":"user","content":"别的事"}}`)
	x := h.Hosts()[0]
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: gone})

	h.Start()
	byID := func(id string) hostlink.Session {
		i := slices.IndexFunc(h.Sessions(), func(s hostlink.Session) bool { return s.ID == id })
		if i < 0 {
			return hostlink.Session{}
		}
		return h.Sessions()[i]
	}
	e.until(t, "列出两个会话", func() bool { return byID(login).ID != "" && byID(other).ID != "" })
	e.until(t, "那边没有的置顶去掉", func() bool { return !h.Pinned(hostlink.Session{Backend: x.ID, ID: gone}) })

	// 搜索：命中边找边推来，按请求 id 对上
	h.SetQuery("needle")
	e.until(t, "搜完", func() bool { return h.SearchProgress() == SearchDone })
	if r := h.Results(hostlink.Session{}, time.Now()); len(r) != 1 || r[0].ID != login || !strings.Contains(h.Snippet(r[0]), "NEEDLE") {
		t.Fatalf("搜到 %+v，片段 %q", r, h.Snippet(byID(login)))
	}
	h.SetQuery("")

	// 取图（在后台 goroutine 上调用）
	var image []byte
	var imageErr error
	load := h.ImageLoader(x.ID)
	done := make(chan struct{})
	go func() { image, imageErr = load(loginFile, "0.1"); close(done) }()
	e.until(t, "取到图", func() bool {
		select {
		case <-done:
			return true
		default:
			return false
		}
	})
	if imageErr != nil || string(image) != "png!" {
		t.Fatalf("图 %q、%v", image, imageErr)
	}

	var failures []string
	failed := func(err error) { failures = append(failures, err.Error()) }

	// 改名、归档：界面先改，核心改完推来的和它一样
	h.Rename(byID(other), "  新的   名字 ", failed)
	h.SetArchived([]hostlink.Session{byID(other)}, true, failed)
	e.until(t, "改名、归档推回来", func() bool {
		data, _ := os.ReadFile(filepath.Join(dir, other+".jsonl"))
		return strings.Contains(string(data), "新的 名字") && byID(other).Archived && byID(other).Title == "新的 名字"
	})

	// 导出
	var exported *Exported
	h.Export([]hostlink.Session{byID(login)}, time.Date(2026, 11, 22, 9, 5, 0, 0, time.Local), func(x Exported) { exported = &x }, failed)
	e.until(t, "导出", func() bool { return exported != nil || len(failures) > 0 })
	if exported == nil || !strings.Contains(exported.Content, "NEEDLE") || !strings.HasSuffix(exported.Name, "-20261122-0905.md") {
		t.Fatalf("导出 %+v，没成的 %q", exported, failures)
	}

	// 删除：记录没了，列表跟着去掉
	h.Delete([]hostlink.Session{byID(login)}, failed)
	e.until(t, "删掉", func() bool {
		_, err := os.Stat(loginFile)
		return os.IsNotExist(err) && byID(login).ID == ""
	})
	e.flush(t)
	if len(failures) != 0 {
		t.Errorf("没成的：%q", failures)
	}
}
