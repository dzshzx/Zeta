package main

import (
	"bytes"
	"fmt"
	"os"
	"strings"
	"testing"
)

const testSession = "55555555-5555-5555-5555-555555555555"

func subagentLog(t *testing.T, e *env, agent, toolUse string, lines ...string) string {
	t.Helper()
	dir := e.root + "/-p/" + testSession + "/subagents"
	path := dir + "/agent-" + agent + ".jsonl"
	writeLines(t, path, lines...)
	writeFile(t, dir+"/agent-"+agent+".meta.json", fmt.Sprintf(`{"toolUseId":%q,"agentType":"x","finished":true,"extra":1}`, toolUse))
	return path
}

// readAll 用块大小 chunk 从头一段段读完，返回全部事件和每次读完后的位置。
func readSubagentAll(t *testing.T, e *env, toolUse string, chunk int) ([]obj, []obj) {
	t.Helper()
	var events, headers []obj
	offset, line := int64(0), int64(0)
	for i := 0; i < 100; i++ {
		var buf bytes.Buffer
		o := newEmitter(&buf)
		if err := cmdSubagent(e, o, subagentRequest{offset: offset, line: line, toolUse: toolUse, sessions: []string{testSession}}, chunk); err != nil {
			t.Fatal(err)
		}
		o.flush()
		out := parseOutput(t, buf.String())
		headers = append(headers, out[0])
		events = append(events, out[1:]...)
		offset, line = num(t, out[0]["offset"]), num(t, out[0]["line"])
		if out[0]["more"] != true {
			return events, headers
		}
	}
	t.Fatal("never finished")
	return nil, nil
}

func parseOutput(t *testing.T, s string) []obj {
	t.Helper()
	var out []obj
	for _, l := range strings.Split(strings.TrimSpace(s), "\n") {
		m, ok := parseObject([]byte(l))
		if !ok {
			t.Fatalf("bad line %q", l)
		}
		out = append(out, m)
	}
	return out
}

func TestSubagentChunkedReadsSeeEveryLineOnce(t *testing.T) {
	e := testEnv(t)
	var lines []string
	lines = append(lines, `{"type":"user","message":{"content":"任务说明"}}`)
	for i := 0; i < 40; i++ {
		lines = append(lines, fmt.Sprintf(`{"type":"assistant","message":{"id":"m%d","content":[{"type":"text","text":"step %d %s"}]}}`, i, i, strings.Repeat("x", i*3)))
	}
	path := subagentLog(t, e, "a1", "toolu_1", lines...)
	longest := 0
	for _, l := range lines {
		longest = max(longest, len(l)+1)
	}
	full, _ := readSubagentAll(t, e, "toolu_1", 1<<20)
	// 块不比最长的一行短时，怎么分块都是同样的事件（比一行还短的块会跳过那一行，见下一个测试）
	for _, chunk := range []int{longest, longest + 1, longest + 97, 2*longest - 1, 1000} {
		events, headers := readSubagentAll(t, e, "toolu_1", chunk)
		if len(events) != len(full) {
			t.Errorf("chunk %d: %d events, want %d", chunk, len(events), len(full))
			continue
		}
		for i := range events {
			if textOf(events[i]) != textOf(full[i]) {
				t.Errorf("chunk %d: event %d differs", chunk, i)
			}
		}
		last := headers[len(headers)-1]
		fi, _ := os.Stat(path)
		if num(t, last["offset"]) != fi.Size() || num(t, last["line"]) != int64(len(lines)) {
			t.Errorf("chunk %d: ended at %v/%v", chunk, last["offset"], last["line"])
		}
	}
	if full[0]["prompt"] != true || textOf(full[0]) != "任务说明" {
		t.Errorf("first event %v", full[0])
	}
}

func TestSubagentSkipsAnOverlongLineAndWaitsForAnUnfinishedOne(t *testing.T) {
	e := testEnv(t)
	long := `{"type":"user","message":{"content":"` + strings.Repeat("z", 500) + `"}}`
	path := subagentLog(t, e, "a2", "toolu_2", `{"type":"user","message":{"content":"first"}}`, long, `{"type":"assistant","message":{"content":[{"type":"text","text":"after"}]}}`)
	events, _ := readSubagentAll(t, e, "toolu_2", 100)
	if got := []string{textOf(events[0]), textOf(events[1])}; len(events) != 2 || got[0] != "first" || got[1] != "after" {
		t.Errorf("events %v", events)
	}
	// 还没写完的超长行：位置不动，等它写完
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(path)
	_, _ = f.WriteString(`{"type":"user","message":{"content":"` + strings.Repeat("w", 300))
	f.Close()
	read, err := readSubagentChunkAt(t, path, fi.Size(), 3, 100)
	if err != nil || read.offset != fi.Size() || read.line != 3 || !read.full || len(read.data) != 0 {
		t.Errorf("unfinished long line: %+v %v", read, err)
	}
}

func readSubagentChunkAt(t *testing.T, path string, offset, line int64, chunk int) (subagentRead, error) {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	return readSubagentChunk(f, offset, line, chunk)
}

func TestSubagentFindsTheLogAndResetsWhenTheFileShrinks(t *testing.T) {
	e := testEnv(t)
	subagentLog(t, e, "a3", "toolu_3", `{"type":"user","message":{"content":"x"}}`)
	writeLines(t, e.root+"/-p/"+testSession+"/subagents/agent-nometa.jsonl", `{"type":"user","message":{"content":"y"}}`)
	cases := []struct {
		args []string
		want string
	}{
		{[]string{"0", "0", "toolu_3", "-", testSession}, "a3"},
		{[]string{"0", "0", "toolu_x", "nometa", testSession}, "nometa"}, // 有 hint、没有 meta 也认
		{[]string{"0", "0", "toolu_x", "a3", testSession}, ""},           // hint 的 meta 是别的调用
		{[]string{"0", "0", "toolu_3", "-", "66666666-6666-6666-6666-666666666666"}, ""},
	}
	for _, c := range cases {
		out := mustCall(t, e, append([]string{"subagent"}, c.args...)...)
		if got := asString(out[0]["agent_id"]); got != c.want || (c.want == "") != (out[0]["missing"] == true) {
			t.Errorf("%v: %v", c.args, out[0])
		}
	}
	out := mustCall(t, e, "subagent", "999", "7", "toolu_3", "-", testSession)
	if out[0]["reset"] != true || num(t, out[0]["line"]) != 1 || len(out) != 2 {
		t.Errorf("reset: %v", out)
	}
	if meta := asObj(out[0]["meta"]); meta["extra"] != nil || meta["finished"] != true || meta["agentType"] != "x" {
		t.Errorf("meta %v", meta)
	}
}
