package coreapi

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// sessionsFake 是假的连接：记下发出的方法名和参数，回 reply 当 result。会话、存储与文件这两组的测试共用。
type sessionsFake struct {
	reply  string
	method string
	params map[string]any
}

func (f *sessionsFake) Call(_ context.Context, method string, p map[string]any) (json.RawMessage, error) {
	f.method, f.params = method, p
	if f.reply == "" {
		return nil, nil
	}
	return json.RawMessage(f.reply), nil
}

// sessionsCase 是一个函数的一次调用：发出 method、参数写成 JSON 是 params，核心答 reply 时解出 want。
type sessionsCase struct {
	name   string
	do     func(cl *Client) (any, error)
	method string
	params string
	reply  string
	want   any
}

func runSessionsCases(t *testing.T, cases []sessionsCase) {
	t.Helper()
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := &sessionsFake{reply: c.reply}
			got, err := c.do(New(f))
			if err != nil {
				t.Fatal(err)
			}
			if f.method != c.method {
				t.Errorf("方法是 %s，应当是 %s", f.method, c.method)
			}
			sent, _ := json.Marshal(f.params)
			if string(sent) != c.params {
				t.Errorf("参数是 %s，应当是 %s", sent, c.params)
			}
			if !reflect.DeepEqual(got, c.want) {
				t.Errorf("结果是 %#v，应当是 %#v", got, c.want)
			}
		})
	}
}

func TestSessionsMethods(t *testing.T) {
	ctx := context.Background()
	idle, stuck, window := sessionproto.StatusIdle, sessionproto.StatusStuck, "3"
	parts := []sessionproto.ExportPart{{Session: "a", Title: "甲", Markdown: "# 甲\n"}, {Session: "b", Title: "乙", Markdown: "# 乙\n"}}
	runSessionsCases(t, []sessionsCase{
		{"watch", func(cl *Client) (any, error) { return nil, cl.WatchSessions(ctx) },
			"sessions.watch", `{}`, ``, nil},
		{"unwatch", func(cl *Client) (any, error) { return nil, cl.UnwatchSessions(ctx) },
			"sessions.unwatch", `{}`, ``, nil},
		{"rename", func(cl *Client) (any, error) { return cl.RenameSession(ctx, "s1", " 新  名字 ") },
			"sessions.rename", `{"session":"s1","title":" 新  名字 "}`, `{"title":"新 名字"}`, "新 名字"},
		{"archive", func(cl *Client) (any, error) { return nil, cl.ArchiveSession(ctx, "s1", true) },
			"sessions.archive", `{"archived":true,"session":"s1"}`, `{"archived":true}`, nil},
		{"pin", func(cl *Client) (any, error) { return nil, cl.PinSession(ctx, "s1", false) },
			"sessions.pin", `{"pinned":false,"session":"s1"}`, `{"pinned":false}`, nil},
		{"delete", func(cl *Client) (any, error) { return cl.DeleteSession(ctx, "s1") },
			"sessions.delete", `{"session":"s1"}`, `{"deleted":[{"file":"/p/s1.jsonl","dir":true}],"closed":1}`,
			Deletion{Deleted: []DeletedRecord{{File: "/p/s1.jsonl", Dir: true}}, Closed: 1}},
		{"export", func(cl *Client) (any, error) { return cl.ExportSessions(ctx, []string{"a", "b", "c"}, 480) },
			"sessions.export", `{"sessions":["a","b","c"],"utc_offset":480}`,
			`{"title":"2个对话","markdown":"# 甲\n\n---\n\n# 乙\n","file_name":"Zeta-2个对话-20261123-0930.md","exported":["a","b"],"failed":["c"],` +
				`"parts":[{"session":"a","title":"甲","markdown":"# 甲\n"},{"session":"b","title":"乙","markdown":"# 乙\n"}]}`,
			Export{Title: "2个对话", Markdown: "# 甲\n\n---\n\n# 乙\n", FileName: "Zeta-2个对话-20261123-0930.md",
				Exported: []string{"a", "b"}, Failed: []string{"c"}, Parts: parts}},
		{"lookup", func(cl *Client) (any, error) { return cl.LookupSessions(ctx, []string{"s1", "s2"}) },
			"sessions.lookup", `{"sessions":["s1","s2"]}`,
			`[{"id":"s1","file":"/p/s1.jsonl","cwd":"/home/u","title":"甲","preview":"你好","updated":1700000000000,` +
				`"external":true,"archived":false,"pinned":true,"live":true}]`,
			[]SessionSummary{{ID: "s1", File: "/p/s1.jsonl", Cwd: "/home/u", Title: "甲", Preview: "你好", Updated: 1700000000000,
				External: true, Pinned: true, Live: true}}},
		{"search", func(cl *Client) (any, error) { return cl.SearchSessions(ctx, "关键词", 7) },
			"sessions.search", `{"query":"关键词","search":7}`, `{"scanned":300,"total":300,"matched":2,"complete":true}`,
			SearchSummary{Scanned: 300, Total: 300, Matched: 2, Complete: true}},
		{"image", func(cl *Client) (any, error) { return cl.SessionImage(ctx, "/p/s1.jsonl", "12.0") },
			"sessions.image", `{"file":"/p/s1.jsonl","ref":"12.0"}`, `{"media_type":"image/png","data":"iVBO"}`,
			Image{MediaType: "image/png", Data: []byte{0x89, 0x50, 0x4e}}},
		{"occupant", func(cl *Client) (any, error) { return cl.SessionOccupant(ctx, "s1") },
			"sessions.occupant", `{"session":"s1"}`, `{"status":"idle","waiting_for":null,"hands_over":true}`,
			sessionproto.Occupancy{Status: &idle, HandsOver: true}},
		{"handoff", func(cl *Client) (any, error) { return cl.HandOffSession(ctx, "s1") },
			"sessions.handoff", `{"session":"s1"}`, `{"handed":false,"status":"stuck"}`,
			sessionproto.Handoff{Status: &stuck}},
		{"end", func(cl *Client) (any, error) { return cl.EndSession(ctx, "s1") },
			"sessions.end", `{"session":"s1"}`, `{"ended":1}`, 1},
		{"end elsewhere", func(cl *Client) (any, error) { return cl.EndElsewhere(ctx, "s1") },
			"sessions.end_elsewhere", `{"session":"s1"}`, `{"ended":2}`, 2},
		{"desk open", func(cl *Client) (any, error) { return cl.OpenDeskWindow(ctx, "s1") },
			"sessions.desk_open", `{"session":"s1"}`, `{"window":"3"}`, &window},
		{"desk open without tmux", func(cl *Client) (any, error) { return cl.OpenDeskWindow(ctx, "s1") },
			"sessions.desk_open", `{"session":"s1"}`, `{"window":null}`, (*string)(nil)},
		{"desk close", func(cl *Client) (any, error) { return cl.CloseDeskWindow(ctx, "s1") },
			"sessions.desk_close", `{"session":"s1"}`, `{"closed":1}`, 1},
	})
}

// TestSessionsNoResult：该有结果的方法核心答了 null 时是 ErrNoResult，不是零值。
func TestSessionsNoResult(t *testing.T) {
	_, err := New(&sessionsFake{reply: "null"}).DeleteSession(context.Background(), "s1")
	if err != ErrNoResult {
		t.Fatalf("答 null 时：%v", err)
	}
}
