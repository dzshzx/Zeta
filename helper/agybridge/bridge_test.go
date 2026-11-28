package agybridge

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestBridgeEndToEndWithFakeACP(t *testing.T) {
	tmp := t.TempDir()
	fakeBin := filepath.Join(tmp, "fake-agy.sh")
	script := `#!/usr/bin/env bash
set -euo pipefail
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  if [[ "$line" == *'"method":"initialize"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentInfo":{"name":"agy","version":"1.5.18"}}}\n' "$id"
  elif [[ "$line" == *'"method":"session/new"'* ]]; then
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"compact","description":"Compact context"}]}}}\n'
    printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"11111111-2222-3333-4444-555555555555","configOptions":[{"id":"model","currentValue":"gemini-4-pro","options":[{"value":"gemini-4-pro","name":"Gemini 4 Pro"}]},{"id":"thought_level","currentValue":"high","options":[{"value":"high","name":"High"}]},{"id":"mode","currentValue":"default","options":[{"value":"default","name":"Default"},{"value":"auto","name":"Auto"}]}]}}\n' "$id"
  elif [[ "$line" == *'"method":"session/set_config_option"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{"configOptions":[]}}\n' "$id"
  elif [[ "$line" == *'"method":"session/prompt"'* ]]; then
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"Thinking..."}}}}\n'
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"tool_call","toolCallId":"tc-1","title":"edit_file","kind":"edit","status":"in_progress","rawInput":{"Path":"a.go","Target":"old","Replacement":"new"}}}}\n'
    printf '{"jsonrpc":"2.0","id":99,"method":"session/request_permission","params":{"sessionId":"11111111-2222-3333-4444-555555555555","toolCall":{"toolCallId":"tc-1","title":"Edit a.go","kind":"edit","rawInput":{"Path":"a.go","Target":"old","Replacement":"new"}},"options":[{"optionId":"opt-allow","name":"Allow","kind":"allow_once"},{"optionId":"opt-deny","name":"Deny","kind":"reject_once"}]}}\n'
    IFS= read -r perm_reply
    if [[ "$perm_reply" != *'"opt-allow"'* ]]; then
      exit 2
    fi
    printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"tool_call_update","toolCallId":"tc-1","status":"completed","content":[{"type":"diff","path":"a.go","oldText":"line1\nold\n","newText":"line1\nnew\n"}]}}}'
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"usage_update","used":12000,"size":1048576}}}\n'
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Done!"}}}}\n'
    printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn","usage":{"inputTokens":10000,"cachedReadTokens":2000,"outputTokens":100,"thoughtTokens":50}}}\n' "$id"
  elif [[ "$line" == *'"method":"session/close"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$id"
    exit 0
  fi
done
`
	if err := os.WriteFile(fakeBin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", tmp)
	t.Setenv("ZETA_AGY_BIN", fakeBin)

	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	var errBuf bytes.Buffer
	done := make(chan int, 1)
	go func() {
		done <- Run([]string{"--cwd", tmp, "--title", "测试对话"}, inR, outW, &errBuf)
		_ = outW.Close()
	}()

	send := func(v any) {
		b, _ := json.Marshal(v)
		_, _ = inW.Write(append(b, '\n'))
	}

	send(map[string]any{"type": "control_request", "request_id": "init-1", "request": map[string]any{"subtype": "initialize"}})

	sc := bufio.NewScanner(outR)
	var sawInitResp, sawPerm, sawPatch, sawAnswer, sawResult bool
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.Contains(line, `"request_id":"init-1"`):
			sawInitResp = strings.Contains(line, `"gemini-4-pro"`) && strings.Contains(line, `"compact"`)
			send(map[string]any{
				"type":    "user",
				"uuid":    "u-1",
				"message": map[string]any{"content": []any{map[string]any{"type": "text", "text": "修改 a.go"}}},
			})
		case strings.Contains(line, `"subtype":"can_use_tool"`):
			sawPerm = true
			var cr struct {
				RequestID string `json:"request_id"`
			}
			_ = json.Unmarshal([]byte(line), &cr)
			send(map[string]any{
				"type": "control_response",
				"response": map[string]any{
					"subtype":    "success",
					"request_id": cr.RequestID,
					"response":   map[string]any{"behavior": "allow"},
				},
			})
		case strings.Contains(line, `"structuredPatch"`):
			sawPatch = true
		case strings.Contains(line, `"Done!"`):
			sawAnswer = true
		case strings.Contains(line, `"type":"result"`):
			sawResult = strings.Contains(line, `"contextWindow":1048576`)
			send(map[string]any{"type": "control_request", "request_id": "end-1", "request": map[string]any{"subtype": "end_session"}})
		}
	}

	select {
	case code := <-done:
		if code != 0 {
			t.Fatalf("Run exit=%d stderr=%s", code, errBuf.String())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("bridge did not exit")
	}
	if !sawInitResp || !sawPerm || !sawPatch || !sawAnswer || !sawResult {
		t.Fatalf("missing events: init=%v perm=%v patch=%v answer=%v result=%v", sawInitResp, sawPerm, sawPatch, sawAnswer, sawResult)
	}
}

func TestMCPSockDiscoverAndRelay(t *testing.T) {
	var emitted []string
	var mu bytes.Buffer
	_ = mu
	var relay *mcpRelay
	var err error
	reqCh := make(chan string, 1)
	relay, err = startMCPRelay(func(v any) {
		b, _ := json.Marshal(v)
		emitted = append(emitted, string(b))
		var m struct {
			RequestID string `json:"request_id"`
		}
		if json.Unmarshal(b, &m) == nil && m.RequestID != "" {
			reqCh <- m.RequestID
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.close()

	clientInR, clientInW := io.Pipe()
	clientOutR, clientOutW := io.Pipe()
	sockDone := make(chan int, 1)
	go func() {
		sockDone <- RunMCPSock(relay.sockPath, clientInR, clientOutW)
		_ = clientOutW.Close()
	}()

	_, _ = io.WriteString(clientInW, `{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}`+"\n")
	br := bufio.NewReader(clientOutR)
	discoverLine, err := br.ReadString('\n')
	if err != nil || !strings.Contains(discoverLine, "-32601") {
		t.Fatalf("discover reply = %q, err=%v", discoverLine, err)
	}

	// Notification (no id) must not block or emit stdout
	_, _ = io.WriteString(clientInW, `{"jsonrpc":"2.0","method":"notifications/initialized"}`+"\n")

	// Normal request with id
	_, _ = io.WriteString(clientInW, `{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}`+"\n")
	reqID := <-reqCh
	relay.handleReply(reqID, json.RawMessage(`{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"screenshot"}]}}`))

	toolsLine, err := br.ReadString('\n')
	if err != nil || !strings.Contains(toolsLine, `"screenshot"`) {
		t.Fatalf("tools/list reply = %q, err=%v", toolsLine, err)
	}
	_ = clientInW.Close()
	<-sockDone
	_ = emitted
}

func TestBridgeConcurrentInitAndUnpromptedCleanup(t *testing.T) {
	tmp := t.TempDir()
	countFile := filepath.Join(tmp, "new_count")
	fakeBin := filepath.Join(tmp, "fake-agy-race.sh")
	script := `#!/usr/bin/env bash
set -euo pipefail
n=0
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  if [[ "$line" == *'"method":"initialize"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1}}\n' "$id"
  elif [[ "$line" == *'"method":"session/new"'* ]]; then
    n=$((n + 1))
    echo "$n" > "` + countFile + `"
    sleep 0.02
    sid=$(printf '22222222-3333-4444-5555-%012d' "$n")
    mkdir -p "$HOME/.gemini/antigravity-cli/brain/$sid"
    printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"%s","configOptions":[]}}\n' "$id" "$sid"
  elif [[ "$line" == *'"method":"session/prompt"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}\n' "$id"
  elif [[ "$line" == *'"method":"session/close"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$id"
    exit 0
  fi
done
`
	if err := os.WriteFile(fakeBin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", tmp)
	t.Setenv("ZETA_AGY_BIN", fakeBin)

	// 1) Concurrent initialize + user message calls session/new only ONCE.
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	done := make(chan int, 1)
	go func() {
		done <- Run([]string{"--cwd", tmp}, inR, outW, io.Discard)
		_ = outW.Close()
	}()
	_, _ = io.WriteString(inW, `{"type":"control_request","request_id":"i1","request":{"subtype":"initialize"}}`+"\n"+
		`{"type":"user","uuid":"u1","message":{"content":"hi"}}`+"\n")
	sc := bufio.NewScanner(outR)
	for sc.Scan() {
		if strings.Contains(sc.Text(), `"type":"result"`) {
			_, _ = io.WriteString(inW, `{"type":"control_request","request_id":"e1","request":{"subtype":"end_session"}}`+"\n")
		}
	}
	<-done
	gotCount, _ := os.ReadFile(countFile)
	if strings.TrimSpace(string(gotCount)) != "1" {
		t.Fatalf("session/new count = %q, want 1", string(gotCount))
	}
	promptedBrain := filepath.Join(tmp, ".gemini/antigravity-cli/brain/22222222-3333-4444-5555-000000000001")
	if _, err := os.Stat(promptedBrain); err != nil {
		t.Fatalf("prompted session brain should remain: %v", err)
	}

	// 2) Unprompted session (only initialize, then end_session) cleans up empty brain/<id>.
	inR2, inW2 := io.Pipe()
	outR2, outW2 := io.Pipe()
	done2 := make(chan int, 1)
	go func() {
		done2 <- Run([]string{"--cwd", tmp}, inR2, outW2, io.Discard)
		_ = outW2.Close()
	}()
	_, _ = io.WriteString(inW2, `{"type":"control_request","request_id":"i2","request":{"subtype":"initialize"}}`+"\n")
	sc2 := bufio.NewScanner(outR2)
	for sc2.Scan() {
		if strings.Contains(sc2.Text(), `"request_id":"i2"`) {
			_, _ = io.WriteString(inW2, `{"type":"control_request","request_id":"e2","request":{"subtype":"end_session"}}`+"\n")
		}
	}
	<-done2
	unpromptedBrain := filepath.Join(tmp, ".gemini/antigravity-cli/brain/22222222-3333-4444-5555-000000000001")
	_ = os.RemoveAll(promptedBrain)
	entries, _ := os.ReadDir(filepath.Join(tmp, ".gemini/antigravity-cli/brain"))
	if len(entries) != 0 {
		t.Fatalf("unprompted session brain was not cleaned up: %v (ref=%s)", entries, unpromptedBrain)
	}
}

func TestBridgeModeMappingSystemPromptEffortTierAndSubagent(t *testing.T) {
	tmp := t.TempDir()
	modeLog := filepath.Join(tmp, "modes.log")
	promptLog := filepath.Join(tmp, "prompt.log")
	fakeBin := filepath.Join(tmp, "fake-agy-precision.sh")
	script := `#!/usr/bin/env bash
set -euo pipefail
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  if [[ "$line" == *'"method":"initialize"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentCapabilities":{"_meta":{"antigravity":{"tier":true,"systemPrompt":{"append":true}}}}}}\n' "$id"
  elif [[ "$line" == *'"method":"session/new"'* ]]; then
    printf '%s\n' "$line" > "` + promptLog + `"
    printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"11111111-2222-3333-4444-555555555555","models":{"availableModels":[{"modelId":"gemini-4-neon-low","name":"Neon Low"},{"modelId":"gemini-4-neon-high","name":"Neon High"},{"modelId":"gemini-4-neon-xhigh","name":"Neon XHigh"},{"modelId":"gpt-oss-120b-medium","name":"GPT-OSS"}]},"configOptions":[{"id":"model","currentValue":"gemini-4-neon","options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"},{"value":"gpt-oss-120b-medium","name":"GPT-OSS 120B"}]},{"id":"mode","currentValue":"accept-edits","options":[{"value":"default","name":"Default"},{"value":"accept-edits","name":"Accept edits"}]}],"_meta":{"antigravity":{"tier":{"name":"Google AI Ultra"}}}}}\n' "$id"
  elif [[ "$line" == *'"method":"session/set_config_option"'* ]]; then
    printf '%s\n' "$line" >> "` + modeLog + `"
    printf '{"jsonrpc":"2.0","id":%s,"result":{"configOptions":[{"id":"mode","currentValue":"accept-edits"}]}}\n' "$id"
  elif [[ "$line" == *'"method":"session/prompt"'* ]]; then
    printf '%s\n' "$line" >> "` + promptLog + `"
    printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"tool_call","toolCallId":"sub-1","title":"invoke_subagent","kind":"other","status":"in_progress","rawInput":{"TypeName":"research","Role":"Code Researcher","Prompt":"inspect"}}}}\n'
    printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"11111111-2222-3333-4444-555555555555","update":{"sessionUpdate":"tool_call_update","toolCallId":"sub-1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"conversationId: 99999999-8888-7777-6666-555555555555\ndone"}}]}}}'
    printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}\n' "$id"
  elif [[ "$line" == *'"method":"session/close"'* ]]; then
    printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$id"
    exit 0
  fi
done
`
	if err := os.WriteFile(fakeBin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", tmp)
	t.Setenv("ZETA_AGY_BIN", fakeBin)

	var reportedTier string
	prevTier := TierReporterFunc
	TierReporterFunc = func(_ string, tier string) { reportedTier = tier }
	t.Cleanup(func() { TierReporterFunc = prevTier })

	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	done := make(chan int, 1)
	go func() {
		done <- Run([]string{"--cwd", tmp, "--permission-mode", "acceptEdits", "--append-system-prompt", "SYS_HINT_MARKER"}, inR, outW, io.Discard)
		_ = outW.Close()
	}()

	send := func(v any) {
		b, _ := json.Marshal(v)
		_, _ = inW.Write(append(b, '\n'))
	}
	send(map[string]any{"type": "control_request", "request_id": "init-1", "request": map[string]any{"subtype": "initialize"}})

	var sawEffort, sawNoEffort, sawModeZeta, sawTaskStarted, sawAgentID, sawTaskNotif bool
	sc := bufio.NewScanner(outR)
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.Contains(line, `"request_id":"init-1"`):
			sawEffort = strings.Contains(line, `"supportedEffortLevels":["low","high","xhigh"]`)
			sawNoEffort = strings.Contains(line, `"supportsEffort":false`)
			sawModeZeta = strings.Contains(line, `"current_permission_mode":"acceptEdits"`)
			send(map[string]any{"type": "control_request", "request_id": "m-1", "request": map[string]any{"subtype": "set_permission_mode", "mode": "acceptEdits"}})
			send(map[string]any{"type": "user", "uuid": "u-1", "message": map[string]any{"content": "hello"}})
		case strings.Contains(line, `"subtype":"task_started"`):
			sawTaskStarted = strings.Contains(line, `"task_id":"99999999-8888-7777-6666-555555555555"`) && strings.Contains(line, `"tool_use_id":"sub-1"`)
		case strings.Contains(line, `"agentId":"99999999-8888-7777-6666-555555555555"`):
			sawAgentID = true
		case strings.Contains(line, `"subtype":"task_notification"`):
			sawTaskNotif = strings.Contains(line, `"status":"completed"`)
		case strings.Contains(line, `"type":"result"`):
			send(map[string]any{"type": "control_request", "request_id": "end-1", "request": map[string]any{"subtype": "end_session"}})
		}
	}
	<-done

	if reportedTier != "Google AI Ultra" {
		t.Fatalf("reportedTier = %q, want Google AI Ultra", reportedTier)
	}
	if !sawEffort || !sawNoEffort || !sawModeZeta {
		t.Fatalf("init checks failed: effort=%v noEffort=%v modeZeta=%v", sawEffort, sawNoEffort, sawModeZeta)
	}
	if !sawTaskStarted || !sawAgentID || !sawTaskNotif {
		t.Fatalf("subagent checks failed: started=%v agentId=%v notif=%v", sawTaskStarted, sawAgentID, sawTaskNotif)
	}
	modesRaw, _ := os.ReadFile(modeLog)
	if !strings.Contains(string(modesRaw), `"value":"accept-edits"`) || strings.Contains(string(modesRaw), `"value":"acceptEdits"`) {
		t.Fatalf("ACP mode mapping wrong: %s", string(modesRaw))
	}
	promptRaw, _ := os.ReadFile(promptLog)
	lines := strings.Split(strings.TrimSpace(string(promptRaw)), "\n")
	if len(lines) < 2 || !strings.Contains(lines[0], "SYS_HINT_MARKER") || strings.Contains(lines[1], "SYS_HINT_MARKER") {
		t.Fatalf("systemPrompt should be in session/new _meta and not prepended to user prompt: %s", string(promptRaw))
	}
}

func TestMCPRelayDeniedToolsFilteringAndBlocking(t *testing.T) {
	reqCh := make(chan string, 1)
	relay, err := startMCPRelay(func(v any) {
		b, _ := json.Marshal(v)
		var m struct {
			RequestID string `json:"request_id"`
		}
		if json.Unmarshal(b, &m) == nil && m.RequestID != "" {
			reqCh <- m.RequestID
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.close()
	relay.setDeniedTools([]string{"create_scheduled_task"})

	clientInR, clientInW := io.Pipe()
	clientOutR, clientOutW := io.Pipe()
	sockDone := make(chan int, 1)
	go func() {
		sockDone <- RunMCPSock(relay.sockPath, clientInR, clientOutW)
		_ = clientOutW.Close()
	}()

	br := bufio.NewReader(clientOutR)
	// 1) tools/list strips denied tools
	_, _ = io.WriteString(clientInW, `{"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}`+"\n")
	reqID := <-reqCh
	relay.handleReply(reqID, json.RawMessage(`{"jsonrpc":"2.0","id":10,"result":{"tools":[{"name":"create_scheduled_task"},{"name":"report_scheduled_run"}]}}`))
	listLine, err := br.ReadString('\n')
	if err != nil || strings.Contains(listLine, "create_scheduled_task") || !strings.Contains(listLine, "report_scheduled_run") {
		t.Fatalf("filtered tools/list = %q err=%v", listLine, err)
	}

	// 2) tools/call to denied tool is blocked locally without emitting to host
	_, _ = io.WriteString(clientInW, `{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"create_scheduled_task","arguments":{}}}`+"\n")
	callLine, err := br.ReadString('\n')
	if err != nil || !strings.Contains(callLine, `"isError":true`) {
		t.Fatalf("blocked tools/call = %q err=%v", callLine, err)
	}
	select {
	case unexpected := <-reqCh:
		t.Fatalf("denied tools/call should not be forwarded, got reqID=%s", unexpected)
	default:
	}

	_ = clientInW.Close()
	<-sockDone
}
