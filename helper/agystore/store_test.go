package agystore

import (
	"encoding/json"
	"os"
	"os/exec"
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

func TestSQLiteConversationSummariesSchemaAndStatus(t *testing.T) {
	sqliteBin, err := exec.LookPath("sqlite3")
	if err != nil {
		if os.Getenv("CI") != "" {
			t.Skip("CI 环境未安装 sqlite3，跳过 SQLite 集成测试")
		}
		t.Fatalf("本机缺少 sqlite3：%v", err)
	}
	agyDir := t.TempDir()
	parentID := "11111111-2222-3333-4444-555555555555"
	childID := "66666666-7777-8888-9999-000000000000"
	runningID := "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb"

	parentPath := writeTestTranscript(t, agyDir, parentID,
		`{"step_index":0,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-28T07:00:01Z","content":"ok"}`,
	)
	writeTestTranscript(t, agyDir, childID,
		`{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-28T07:00:01Z","content":"child"}`,
	)
	writeTestTranscript(t, agyDir, runningID,
		`{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-28T07:00:01Z","content":"running"}`,
	)

	dbPath := filepath.Join(agyDir, "conversation_summaries.db")
	schemaSQL := `
CREATE TABLE conversation_summaries (
  conversation_id text PRIMARY KEY,
  title text NOT NULL DEFAULT "",
  preview text NOT NULL DEFAULT "",
  step_count integer NOT NULL DEFAULT 0,
  last_modified_time datetime NOT NULL,
  workspace_uris text NOT NULL,
  status text NOT NULL DEFAULT "",
  parent_conversation_id text NOT NULL DEFAULT "",
  not_fully_idle numeric NOT NULL DEFAULT false,
  last_user_input_time datetime NOT NULL
);
CREATE TABLE conversations_fts (conversation_id text, title text);
CREATE TABLE conversation_parents (conversation_id TEXT PRIMARY KEY, parent_id TEXT NOT NULL);
INSERT INTO conversation_summaries VALUES
  ('` + parentID + `','DB 标题','DB 预览',5,'2026-11-28 10:33:23.448261955+00:00','["file:///workspace/real-proj"]','CASCADE_RUN_STATUS_IDLE','',0,'2026-11-28 10:19:49.865945581+00:00'),
  ('` + childID + `','','',3,'2026-11-28 10:34:00.000000000+00:00','["file:///workspace/real-proj"]','CASCADE_RUN_STATUS_IDLE','` + parentID + `',0,'0001-01-01 00:00:00+00:00'),
  ('` + runningID + `','运行中','正在执行',2,'2026-11-28 10:35:00.000000000+00:00','["file:///workspace/real-proj"]','CASCADE_RUN_STATUS_RUNNING','',1,'2026-11-28 10:34:30.000000000+00:00');
INSERT INTO conversations_fts VALUES ('` + parentID + `','DB 标题');
INSERT INTO conversation_parents VALUES ('` + childID + `','` + parentID + `');
`
	if out, err := exec.Command(sqliteBin, dbPath, schemaSQL).CombinedOutput(); err != nil {
		t.Fatalf("init sqlite failed: %v %s", err, out)
	}

	if got := SessionRunStatus(agyDir, parentID); got != "idle" {
		t.Fatalf("idle session status = %q, want idle", got)
	}
	if got := SessionRunStatus(agyDir, runningID); got != "busy" {
		t.Fatalf("running session status = %q, want busy", got)
	}

	listed := ListedTranscriptFiles(agyDir)
	if len(listed) != 2 {
		t.Fatalf("ListedTranscriptFiles 应过滤子代理，got %v", listed)
	}
	sum, ok, err := Summarize(agyDir, parentPath)
	if err != nil || !ok {
		t.Fatalf("Summarize failed: ok=%v err=%v", ok, err)
	}
	if sum.Title != "DB 标题" || sum.Preview != "DB 预览" || sum.Cwd != "/workspace/real-proj" || sum.Created == 0 {
		t.Fatalf("Summarize from DB mismatch: %+v", sum)
	}

	if err := Rename(agyDir, parentID, "同步改名"); err != nil {
		t.Fatal(err)
	}
	out, err := exec.Command(sqliteBin, dbPath, "SELECT title FROM conversation_summaries WHERE conversation_id='"+parentID+"';").Output()
	if err != nil || strings.TrimSpace(string(out)) != "同步改名" {
		t.Fatalf("DB title after Rename = %q err=%v", string(out), err)
	}

	if _, err := Delete(agyDir, parentID); err != nil {
		t.Fatal(err)
	}
	cntOut, err := exec.Command(sqliteBin, dbPath, "SELECT count(*) FROM conversation_summaries WHERE conversation_id='"+parentID+"';").Output()
	if err != nil || strings.TrimSpace(string(cntOut)) != "0" {
		t.Fatalf("conversation_summaries row not deleted: %q err=%v", string(cntOut), err)
	}
	ftsOut, err := exec.Command(sqliteBin, dbPath, "SELECT count(*) FROM conversations_fts WHERE conversation_id='"+parentID+"';").Output()
	if err != nil || strings.TrimSpace(string(ftsOut)) != "0" {
		t.Fatalf("conversations_fts row not deleted: %q err=%v", string(ftsOut), err)
	}
}
