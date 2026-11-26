package main

import (
	"fmt"
	"testing"
)

// agentAnswer 是子代理记录里的一行回答：消息 id、模型、usage 四项、思考强度，可带发起子代理的工具调用。
func agentAnswer(id, model, effort string, in, out, read, write int, toolUses ...string) string {
	content := `[{"type":"text","text":"x"}]`
	if len(toolUses) > 0 {
		content = "["
		for i, u := range toolUses {
			if i > 0 {
				content += ","
			}
			content += fmt.Sprintf(`{"type":"tool_use","id":%q,"name":"Agent","input":{}}`, u)
		}
		content += "]"
	}
	return fmt.Sprintf(`{"type":"assistant","effort":%q,"message":{"id":%q,"model":%q,"content":%s,`+
		`"usage":{"input_tokens":%d,"output_tokens":%d,"cache_read_input_tokens":%d,"cache_creation_input_tokens":%d}}}`,
		effort, id, model, content, in, out, read, write)
}

// 一页里发起子代理的回答后面跟一行 subagent_usage：它和它又派的子代理按模型加起来；同一条消息拆成几行只算一次，
// 合成的回答不算；没有记录的调用不跟。
func TestHistoryFollowsSubagentCallsWithTheirUsage(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/" + testSession + ".jsonl"
	writeLines(t, file,
		userLine("u1", "go"),
		`{"type":"assistant","message":{"id":"m1","model":"claude-opus-5-5","content":[`+
			`{"type":"tool_use","id":"toolu_a","name":"Agent","input":{}},{"type":"tool_use","id":"toolu_none","name":"Agent","input":{}}]},"uuid":"a1"}`,
		answerLine("a2", "done"))
	subagentLog(t, e, "aa", "toolu_a",
		`{"type":"user","message":{"content":"task"}}`,
		agentAnswer("s1", "claude-sonnet-5-5", "medium", 1, 2, 3, 4),
		agentAnswer("s1", "claude-sonnet-5-5", "medium", 1, 2, 3, 4, "toolu_b"), // 同一条消息的另一块
		agentAnswer("s2", "<synthetic>", "", 100, 100, 100, 100))
	subagentLog(t, e, "bb", "toolu_b",
		agentAnswer("h1", "claude-haiku-5-5", "low", 10, 20, 30, 40),
		agentAnswer("s3", "claude-sonnet-5-5", "low", 1, 1, 1, 1))

	out := mustCall(t, e, "history", file)
	var kinds []string
	for _, ev := range out[1:] {
		kinds = append(kinds, asString(ev["type"]))
	}
	if fmt.Sprint(kinds) != "[user assistant subagent_usage assistant]" {
		t.Fatalf("events %v", kinds)
	}
	u := out[3]
	if u["tool_use_id"] != "toolu_a" {
		t.Fatalf("usage %v", u)
	}
	got := map[string]string{}
	for _, m := range asList(u["models"]) {
		mo := asObj(m)
		us := asObj(mo["usage"])
		got[asString(mo["model"])] = fmt.Sprintf("%v %v %v %v", us["input_tokens"], us["output_tokens"], us["cache_read_input_tokens"],
			us["cache_creation_input_tokens"])
	}
	want := map[string]string{"claude-sonnet-5-5": "2 3 4 5", "claude-haiku-5-5": "10 20 30 40"}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("models %v, want %v", got, want)
	}
}

// 子代理查看页读到的回答带着实际用的模型和思考强度；合成的回答不带；meta 里指定的思考强度照样带出。
func TestSubagentEventsCarryModelAndEffort(t *testing.T) {
	e := testEnv(t)
	dir := e.root + "/-p/" + testSession + "/subagents"
	writeLines(t, dir+"/agent-cc.jsonl",
		agentAnswer("s1", "claude-sonnet-5-5", "medium", 1, 1, 1, 1),
		`{"type":"assistant","effort":"high","message":{"id":"s2","model":"<synthetic>","content":[{"type":"text","text":"x"}]}}`)
	writeFile(t, dir+"/agent-cc.meta.json", `{"toolUseId":"toolu_c","model":"sonnet","effort":"medium"}`)
	out := mustCall(t, e, "subagent", "0", "0", "toolu_c", "-", testSession)
	if meta := asObj(out[0]["meta"]); meta["model"] != "sonnet" || meta["effort"] != "medium" {
		t.Errorf("meta %v", meta)
	}
	if len(out) != 3 || asObj(out[1]["message"])["model"] != "claude-sonnet-5-5" || out[1]["effort"] != "medium" {
		t.Fatalf("events %v", out)
	}
	if _, ok := asObj(out[2]["message"])["model"]; ok {
		t.Errorf("synthetic answer carries a model: %v", out[2])
	}
	if _, ok := out[2]["effort"]; ok {
		t.Errorf("synthetic answer carries an effort: %v", out[2])
	}
}
