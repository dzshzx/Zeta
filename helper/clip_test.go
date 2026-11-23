package main

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// 会话记录里改文件的工具参数过长时截断，块上标 clipped，归约成的步骤也带着；没截断的不标。客户端看它，不认截断的尾巴。
func TestClippedToolInputIsMarked(t *testing.T) {
	long := strings.Repeat("长", maxText+10)
	blocks := assistantBlocks([]any{
		obj{"type": "tool_use", "id": "t1", "name": "Write", "input": obj{"file_path": "/a", "content": long}},
		obj{"type": "tool_use", "id": "t2", "name": "Edit", "input": obj{"file_path": "/b", "old_string": "x", "new_string": "y"}},
	})
	cut, whole := blocks[0].(obj), blocks[1].(obj)
	if cut["clipped"] != true || whole["clipped"] != nil {
		t.Fatalf("clipped：%v %v", cut["clipped"], whole["clipped"])
	}
	if content := cut["input"].(obj)["content"].(string); !strings.HasSuffix(content, truncMark) {
		t.Fatalf("截断的参数没有尾巴：%q", content[len(content)-20:])
	}
	raw, err := json.Marshal(obj{"id": "m1", "content": blocks})
	if err != nil {
		t.Fatal(err)
	}
	var m transcript.Message
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	r := transcript.New("")
	r.ApplyAssistant(m, true)
	var steps []*transcript.Step
	for _, it := range r.Items {
		steps = append(steps, it.Steps...)
	}
	if len(steps) != 2 || !steps[0].Clipped || steps[1].Clipped {
		t.Fatalf("步骤：%+v", steps)
	}
}
