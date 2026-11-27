package chat

import (
	"context"
	"testing"
	"time"
)

func TestSubagentWatchAndPushes(t *testing.T) {
	link, client, conv, notified := opened(t)
	ctx := context.Background()
	// 没看着的子代理的推送不要
	*notified = 0
	client.Handle(line(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_1","full":true,"items":[{"kind":"answer","id":"x"}]}`))
	if _, ok := conv.Subagent("toolu_1"); ok || *notified != 0 {
		t.Fatal("没看着的子代理也收下了")
	}

	if err := conv.Watch(ctx, SubagentTarget("toolu_1"), true); err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 1 || got[0].method != "conv.subagent" || got[0].params != `{"conv":"w1","tool_use_id":"toolu_1","watch":true}` {
		t.Fatalf("看：%+v", got)
	}
	s, ok := conv.Subagent("toolu_1")
	if !ok || s.Rev != 0 || s.Items != nil {
		t.Fatalf("还没推来时：%+v", s)
	}
	client.Handle(line(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_1","full":true,"at":0,"remove":0,
		"items":[{"kind":"work","id":"w"},{"kind":"answer","id":"a"}],
		"meta":{"agent_id":"ag1","agent_type":"Explore","description":"查找","model":"claude-haiku","finished":false,"stopped":false,"background":null},
		"prompt":"找出所有读配置的地方","tool_uses":3,"running":true,"loaded":true,"missing":false,"file":"/h/a.jsonl","skipped":0}`))
	s, _ = conv.Subagent("toolu_1")
	if s.Rev != 1 || ids(t, s.Items) != "w,a" || s.Meta == nil || s.Meta.AgentType != "Explore" || s.Prompt != "找出所有读配置的地方" ||
		s.ToolUses != 3 || !s.Running || !s.Loaded || s.Outcome != "" {
		t.Fatalf("整份：%+v", s)
	}
	first := s.Items
	// 增量：换掉最后一项，再加一项；拿到的旧切片不变
	client.Handle(line(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_1","at":1,"remove":1,
		"items":[{"kind":"answer","id":"a2"},{"kind":"answer","id":"b"}],"meta":null,"prompt":"找出所有读配置的地方",
		"tool_uses":4,"running":false,"loaded":true,"outcome":"completed","file":"/h/a.jsonl","skipped":2}`))
	s, _ = conv.Subagent("toolu_1")
	if s.Rev != 2 || ids(t, s.Items) != "w,a2,b" || s.Outcome != "completed" || s.Running || s.Skipped != 2 || s.Meta != nil {
		t.Fatalf("增量：%+v", s)
	}
	if ids(t, first) != "w,a" {
		t.Fatalf("先前拿到的条目被改了：%s", ids(t, first))
	}

	// 对不上的增量：再看一次，核心从整份推起
	again := make(chan struct{})
	link.onCall = func(method string, _ map[string]any) {
		if method == "conv.subagent" {
			close(again)
		}
	}
	client.Handle(line(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_1","at":9,"remove":1,"items":[]}`))
	select {
	case <-again:
	case <-time.After(5 * time.Second):
		t.Fatal("对不上以后没再看一次")
	}
	link.onCall = nil
	if got := link.took(); len(got) != 1 || got[0].params != `{"conv":"w1","tool_use_id":"toolu_1","watch":true}` {
		t.Fatalf("对不上以后：%+v", got)
	}

	// 断线重连：重新打开后接着看
	client.Disconnected()
	if err := client.Reconnected(ctx); err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 2 || got[0].method != "conv.open" || got[1].method != "conv.subagent" {
		t.Fatalf("重连：%+v", got)
	}

	// 不看了：不再收它的推送
	if err := conv.Watch(ctx, SubagentTarget("toolu_1"), false); err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 1 || got[0].params != `{"conv":"w1","tool_use_id":"toolu_1","watch":false}` {
		t.Fatalf("不看：%+v", got)
	}
	if _, ok := conv.Subagent("toolu_1"); ok {
		t.Fatal("不看以后还留着")
	}
}

// ChangesSince 交出从某一版起的条目变化，依次套上就是现在的条目；整份推来时从整份记起，差得太多时说没记着。
func TestSubagentChangesSince(t *testing.T) {
	_, client, conv, _ := opened(t)
	if err := conv.Watch(context.Background(), SubagentTarget("toolu_1"), true); err != nil {
		t.Fatal(err)
	}
	push := func(change string) {
		client.Handle(line(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_1",` + change + `,"running":true,"loaded":true}`))
	}
	push(`"full":true,"items":[{"kind":"work","id":"w"}]`)
	push(`"at":1,"remove":0,"items":[{"kind":"answer","id":"a"}]`)
	push(`"at":1,"remove":1,"items":[{"kind":"answer","id":"a2"}]`)
	s, _ := conv.Subagent("toolu_1")
	if got, ok := s.ChangesSince(s.Rev); !ok || len(got) != 0 {
		t.Fatalf("同一版：%v %v", got, ok)
	}
	got, ok := s.ChangesSince(1)
	if !ok || len(got) != 2 || got[0].At != 1 || got[0].Remove != 0 || got[1].Remove != 1 || got[1].Items[0].ID != "a2" {
		t.Fatalf("从第 1 版起：%+v %v", got, ok)
	}
	if got, ok := s.ChangesSince(0); !ok || len(got) != 3 || !got[0].Full {
		t.Fatalf("从头：%+v %v", got, ok)
	}
	// 整份推来以后，之前的版本都要整份换
	push(`"full":true,"items":[{"kind":"answer","id":"z"}]`)
	s, _ = conv.Subagent("toolu_1")
	if _, ok := s.ChangesSince(2); ok {
		t.Fatal("整份推来以前的版本不该还能接上")
	}
	if got, ok := s.ChangesSince(3); !ok || len(got) != 1 || !got[0].Full {
		t.Fatalf("上一版：%+v %v", got, ok)
	}
	// 只记最近 keptChanges 次
	for range keptChanges + 1 {
		push(`"at":0,"remove":1,"items":[{"kind":"answer","id":"z"}]`)
	}
	s, _ = conv.Subagent("toolu_1")
	if _, ok := s.ChangesSince(s.Rev - keptChanges - 1); ok {
		t.Fatal("差得太多还说接得上")
	}
	if got, ok := s.ChangesSince(s.Rev - keptChanges); !ok || len(got) != keptChanges {
		t.Fatalf("刚好记着的：%d %v", len(got), ok)
	}
}

// 核心的第一份还没推到时，说明、类型和交给它的任务用后台任务清单里的垫上；推来了以核心的为准，空着的几项照旧垫。
func TestSubagentFilledFromItsTask(t *testing.T) {
	_, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1","tasks":[
		{"id":"b1","type":"local_bash","kind":"command","description":"别的","command":"tail","tool_use_id":"toolu_1","started_at":1,"state":"running","running":true},
		{"id":"a1","type":"local_agent","kind":"agent","description":"查找","command":"找出读配置的地方","agent_type":"Explore",
		 "tool_use_id":"toolu_1","started_at":1,"state":"running","running":true}]}`))
	if err := conv.Watch(context.Background(), SubagentTarget("toolu_1"), true); err != nil {
		t.Fatal(err)
	}
	s, _ := conv.Subagent("toolu_1")
	if s.Rev != 0 || s.Meta == nil || s.Meta.Description != "查找" || s.Meta.AgentType != "Explore" || s.Prompt != "找出读配置的地方" {
		t.Fatalf("还没推来时：%+v %+v", s, s.Meta)
	}
	client.Handle(line(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_1","full":true,"items":[],
		"meta":{"agent_id":"ag1","agent_type":"","description":"核心给的说明","model":"claude-haiku"},"prompt":"核心给的任务"}`))
	s, _ = conv.Subagent("toolu_1")
	if s.Meta.Description != "核心给的说明" || s.Meta.AgentType != "Explore" || s.Meta.Model != "claude-haiku" || s.Prompt != "核心给的任务" {
		t.Fatalf("推来以后：%+v %+v", s, s.Meta)
	}
	// 垫的是另起的一份，记着的那份没被改
	client.Handle(line(`{"type":"conv.state","conv":"w1","tasks":[]}`))
	if s, _ = conv.Subagent("toolu_1"); s.Meta.AgentType != "" {
		t.Fatalf("垫上的写进了记着的那份：%+v", s.Meta)
	}
	// 不在清单里的照旧是零值
	if err := conv.Watch(context.Background(), SubagentTarget("toolu_2"), true); err != nil {
		t.Fatal(err)
	}
	if s, _ = conv.Subagent("toolu_2"); s.Meta != nil || s.Prompt != "" {
		t.Fatalf("不在清单里：%+v", s)
	}
}
