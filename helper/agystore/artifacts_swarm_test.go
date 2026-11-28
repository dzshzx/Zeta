package agystore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestArtifactsListReadAndSecurity(t *testing.T) {
	agyDir := t.TempDir()
	sessionID := "sess-1234"
	brain := filepath.Join(agyDir, "brain", sessionID)
	if err := os.MkdirAll(filepath.Join(brain, ".system_generated"), 0o755); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(brain, "report.md"), []byte("# Report\nHello"), 0o644)
	_ = os.WriteFile(filepath.Join(brain, "report.md.metadata.json"), []byte(`{"summary":"Summary text","userFacing":true,"updatedAt":"2026-01-01T00:00:00Z"}`), 0o644)
	_ = os.WriteFile(filepath.Join(brain, ".zeta_meta.json"), []byte(`{}`), 0o644)
	_ = os.WriteFile(filepath.Join(brain, ".system_generated", "secret.txt"), []byte("hidden"), 0o644)

	outside := filepath.Join(agyDir, "outside.txt")
	_ = os.WriteFile(outside, []byte("secret"), 0o644)
	_ = os.Symlink(outside, filepath.Join(brain, "escape.md"))

	list := ListArtifacts(agyDir, sessionID)
	if len(list) != 1 || list[0].Name != "report.md" || !list[0].UserFacing || list[0].Summary != "Summary text" {
		t.Fatalf("unexpected artifacts list: %+v", list)
	}

	got, err := ReadArtifact(agyDir, sessionID, "report.md")
	if err != nil || got.Content != "# Report\nHello" || got.Truncated {
		t.Fatalf("ReadArtifact: %+v err=%v", got, err)
	}
	if _, err := ReadArtifact(agyDir, sessionID, "escape.md"); err == nil {
		t.Fatal("expected symlink escape to be rejected")
	}
	if _, err := ReadArtifact(agyDir, sessionID, "../outside.txt"); err == nil {
		t.Fatal("expected path traversal to be rejected")
	}
}

func TestSwarmStateExtraction(t *testing.T) {
	var st *SwarmState
	st = ApplySwarmCall(st, json.RawMessage(`{"action":"title","title":"Refactor Auth"}`), "")
	st = ApplySwarmCall(st, json.RawMessage(`{"action":"add","name":"worker","model":"pro","brief":"Audit auth middleware","n":2}`), "")
	st = ApplySwarmCall(st, json.RawMessage(`{"action":"kill","name":"worker-2"}`), "")
	st = ApplySwarmCall(st, json.RawMessage(`{"action":"status"}`), `{"members":[{"name":"worker-1","status":"waiting","conversationId":"conv-abc"}]}`)

	if st == nil || st.Title != "Refactor Auth" || len(st.Members) != 2 {
		t.Fatalf("unexpected swarm state: %+v", st)
	}
	if st.Members[0].Status != "waiting" || st.Members[0].ConversationID != "conv-abc" {
		t.Fatalf("unexpected worker-1: %+v", st.Members[0])
	}
	if st.Members[1].Status != "killed" {
		t.Fatalf("unexpected worker-2: %+v", st.Members[1])
	}
}
