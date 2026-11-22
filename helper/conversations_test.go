package main

import (
	"errors"
	"testing"
)

// 核心读子代理记录走的是进程内的 zeta-helper subagent：参数和输出同命令行。
func TestSubagentOutputInProcess(t *testing.T) {
	e := testEnv(t)
	path := subagentLog(t, e, "a1", "toolu_1",
		`{"type":"user","message":{"content":"任务说明"}}`,
		`{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"做完了"}]}}`)
	out, err := subagentOutput(e, []string{"0", "0", "toolu_1", "-", testSession})
	if err != nil {
		t.Fatal(err)
	}
	lines := parseOutput(t, string(out))
	if head := lines[0]; head["type"] != "zeta_subagent" || head["agent_id"] != "a1" || head["file"] != realPath(path) {
		t.Fatalf("概要：%v", head)
	}
	if len(lines) != 3 || lines[1]["prompt"] != true || lines[2]["type"] != "assistant" {
		t.Fatalf("事件：%v", lines[1:])
	}
	if _, err := subagentOutput(e, []string{"0", "0", "toolu_1"}); !errors.Is(err, errUsage) {
		t.Fatalf("参数不全时应报用法不对：%v", err)
	}
}
