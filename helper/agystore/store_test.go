package agystore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestExtractUserRequestAndDecodeArgs(t *testing.T) {
	wrapped := "<USER_REQUEST>\n帮我检查 main.go\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nThe current local time is: ...\n</ADDITIONAL_METADATA>"
	if got := ExtractUserRequest(wrapped); got != "帮我检查 main.go" {
		t.Fatalf("ExtractUserRequest = %q", got)
	}
	args := DecodeToolArgs(map[string]any{
		"Path":      `"/workspace/demo/main.go"`,
		"StartLine": `10`,
		"Overwrite": `true`,
		"Plain":     "raw-text",
	})
	if args["Path"] != "/workspace/demo/main.go" || args["Overwrite"] != true || args["Plain"] != "raw-text" {
		t.Fatalf("DecodeToolArgs = %#v", args)
	}
	if n, ok := args["StartLine"].(json.Number); !ok || n.String() != "10" {
		t.Fatalf("StartLine = %#v", args["StartLine"])
	}
}

func TestPbtxtRoundTripAndOctalUnescape(t *testing.T) {
	dir := t.TempDir()
	id := "11111111-2222-3333-4444-555555555555"
	annDir := filepath.Join(dir, "annotations")
	if err := os.MkdirAll(annDir, 0o755); err != nil {
		t.Fatal(err)
	}
	// prototext octal escape for "中文"
	raw := "title: \"\\344\\270\\255\\346\\226\\207\"\npinned: true\n"
	if err := os.WriteFile(AnnotationPath(dir, id), []byte(raw), 0o644); err != nil {
		t.Fatal(err)
	}
	ann := ReadAnnotation(dir, id)
	if ann.Title != "中文" || !ann.Pinned || ann.Archived {
		t.Fatalf("ReadAnnotation = %+v", ann)
	}
	if err := SetAnnotationTitle(dir, id, `新标题 "A"`); err != nil {
		t.Fatal(err)
	}
	ann2 := ReadAnnotation(dir, id)
	if ann2.Title != `新标题 "A"` || !ann2.Pinned {
		t.Fatalf("after SetAnnotationTitle = %+v", ann2)
	}
}

func writeTestTranscript(t *testing.T, agyDir, id string, lines ...string) string {
	t.Helper()
	p := TranscriptPath(agyDir, id)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func seedParentAndChild(t *testing.T, agyDir, parentID, childID string) string {
	t.Helper()
	writeTestTranscript(t, agyDir, childID,
		`{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-28T07:00:01Z","content":"<USER_REQUEST>\n检查子模块\n</USER_REQUEST>"}`,
		`{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:03Z","model":"gemini-4-flash","content":"子模块正常","input_tokens":1000,"output_tokens":50}`,
	)
	WriteZetaMeta(agyDir, childID, ZetaMeta{Parent: parentID})

	parentPath := writeTestTranscript(t, agyDir, parentID,
		`{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-28T07:00:00Z","content":"<USER_REQUEST>\n检查仓库结构\n</USER_REQUEST>"}`,
		`{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:01Z","model":"gemini-4-pro","thinking":"先派子代理看一眼","input_tokens":2000,"cache_read_tokens":500,"output_tokens":120,"tool_calls":[{"id":"call-sub-1","name":"invoke_subagent","args":{"TypeName":"\"research\"","Role":"\"Codebase Researcher\""}}]}`,
		`{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","created_at":"2026-11-28T07:00:04Z","output":"conversationId: 99999999-8888-7777-6666-555555555555\n子模块正常"}`,
		`{"step_index":3,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:05Z","model":"gemini-4-pro","content":"全部检查完毕。","input_tokens":2500,"output_tokens":40}`,
	)
	WriteZetaMeta(agyDir, parentID, ZetaMeta{Cwd: "/workspace/demo", Title: "初始标题"})
	return parentPath
}

func TestSummarizeTimelineRenameDeleteAndSubagent(t *testing.T) {
	agyDir := t.TempDir()
	parentID := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	childID := "99999999-8888-7777-6666-555555555555"
	parentPath := seedParentAndChild(t, agyDir, parentID, childID)

	listed := ListedTranscriptFiles(agyDir)
	if len(listed) != 1 || listed[0] != parentPath {
		t.Fatalf("ListedTranscriptFiles = %v, want [%s]", listed, parentPath)
	}
	sum, ok, err := Summarize(agyDir, parentPath)
	if err != nil || !ok || sum.ID != parentID || sum.Cwd != "/workspace/demo" || sum.Title != "初始标题" || sum.Preview != "检查仓库结构" {
		t.Fatalf("Summarize = %+v, ok=%v, err=%v", sum, ok, err)
	}
	tl, err := ReadTimeline(parentPath)
	if err != nil || len(tl.Events) != 4 || tl.Model != "gemini-4-pro" {
		t.Fatalf("Timeline = %+v, err=%v", tl, err)
	}
	verifySubagentAndMutate(t, agyDir, parentID, childID, parentPath)
}

func verifySubagentAndMutate(t *testing.T, agyDir, parentID, childID, parentPath string) {
	t.Helper()
	sub, found := FindSubagent(agyDir, "call-sub-1", "", []string{parentID})
	if !found || sub.AgentID != childID || sub.Meta["agentType"] != "research" {
		t.Fatalf("FindSubagent = %+v, found=%v", sub, found)
	}
	if ChildTokenModels(agyDir, parentPath)["call-sub-1"]["gemini-4-flash"][0] != 1000 {
		t.Fatalf("ChildTokenModels mismatch")
	}
	if err := Rename(agyDir, parentID, "改名后的会话"); err != nil {
		t.Fatal(err)
	}
	if sum2, ok, _ := Summarize(agyDir, parentPath); !ok || sum2.Title != "改名后的会话" {
		t.Fatalf("after Rename = %+v", sum2)
	}
	if had, err := Delete(agyDir, parentID); err != nil || !had || len(ListedTranscriptFiles(agyDir)) != 0 {
		t.Fatalf("Delete had=%v err=%v", had, err)
	}
}
