package agybridge

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dzshzx/Zeta/helper/agystore"
	"github.com/dzshzx/Zeta/helper/locales"
)

const defaultAgyWindow = int64(1048576)

type options struct {
	cwd            string
	resume         string
	model          string
	permissionMode string
	effort         string
	systemPrompt   string
	title          string
	phoneMCP       bool
	helperBin      string
}

type acpConfigOption struct {
	ID           string `json:"id"`
	Category     string `json:"category"`
	CurrentValue string `json:"currentValue"`
	Options      []struct {
		Value       string `json:"value"`
		Name        string `json:"name"`
		Description string `json:"description"`
	} `json:"options"`
}

type acpModelItem struct {
	ModelID string `json:"modelId"`
}

type acpSessionResult struct {
	SessionID     string            `json:"sessionId"`
	ConfigOptions []acpConfigOption `json:"configOptions"`
	Models        *struct {
		AvailableModels []acpModelItem `json:"availableModels"`
	} `json:"models"`
	Meta *struct {
		Antigravity *struct {
			Tier *struct {
				Name string `json:"name"`
			} `json:"tier"`
		} `json:"antigravity"`
	} `json:"_meta"`
}

type acpPromptResult struct {
	StopReason string `json:"stopReason"`
	Usage      *struct {
		InputTokens       int64 `json:"inputTokens"`
		OutputTokens      int64 `json:"outputTokens"`
		CachedReadTokens  int64 `json:"cachedReadTokens"`
		CachedWriteTokens int64 `json:"cachedWriteTokens"`
		ThoughtTokens     int64 `json:"thoughtTokens"`
	} `json:"usage"`
}

type queuedPrompt struct {
	uuid   string
	blocks []map[string]any
	text   string
}

// UsageQueryFunc 由外部（如 agyacct）提供订阅额度查询结果，供 get_usage 控制请求返回。
var UsageQueryFunc func(ctx context.Context, home string) (json.RawMessage, error)

// TierReporterFunc 在 ACP session/new 返回 _meta.antigravity.tier.name 时回调，持久化真实套餐展示名。
var TierReporterFunc func(home, tierName string)

type bridge struct {
	opts     options
	home     string
	agyDir   string
	acp      *acpClient
	mcp      *mcpRelay
	outM     sync.Mutex
	out      *bufio.Writer
	stderr   io.Writer
	exitCh   chan int
	exitOnce sync.Once
	closing  atomic.Bool

	sessMu                     sync.Mutex
	mu                         sync.Mutex
	sessionID                  string
	resumed                    bool
	prompted                   bool
	supportsAppendSystemPrompt bool
	title                      string
	currentModel               string
	defaultModel               string
	currentEffort              string
	userMode                   string
	acpMode                    string
	windowSize                 int64
	contextTokens              int64
	availableModels            []acpModelItem
	models                     json.RawMessage
	commands                   json.RawMessage
	commandsReady              chan struct{}
	contextNotes               []string

	cumInput      int64
	cumOutput     int64
	cumCacheRead  int64
	cumCacheWrite int64

	activeTasks map[string]bgTask

	promptSeq int
	prompts   []*pendingPrompt

	turnM      sync.Mutex
	turnSeq    int
	turnActive bool
	queue      []queuedPrompt

	streamM   sync.Mutex
	curStream *turnStream
}

// Run 执行 `zeta-helper agy-bridge [flags]`，返回进程退出码。
func Run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	opts := parseArgs(args)
	home, _ := os.UserHomeDir()
	if h, ok := os.LookupEnv("HOME"); ok && h != "" {
		home = h
	}
	agyDir := agystore.Dir(home)
	b := &bridge{
		opts:          opts,
		home:          home,
		agyDir:        agyDir,
		out:           bufio.NewWriterSize(stdout, 64*1024),
		stderr:        stderr,
		exitCh:        make(chan int, 1),
		userMode:      opts.permissionMode,
		title:         opts.title,
		windowSize:    defaultAgyWindow,
		commandsReady: make(chan struct{}, 1),
	}
	if err := b.start(); err != nil {
		_, _ = io.WriteString(stderr, "agy-bridge: "+err.Error()+"\n")
		return 1
	}
	defer b.shutdown()

	go b.readStdin(stdin)
	select {
	case code := <-b.exitCh:
		return code
	case <-b.acp.done:
		if b.closing.Load() {
			return <-b.exitCh
		}
		select {
		case code := <-b.exitCh:
			return code
		default:
			return 1
		}
	}
}

func parseArgs(args []string) options {
	cwd, _ := os.Getwd()
	exe, _ := os.Executable()
	o := options{cwd: cwd, helperBin: cmp.Or(os.Getenv("ZETA_HELPER_BIN"), exe)}
	for i := 0; i < len(args); i++ {
		a := args[i]
		next := func() string {
			if i+1 < len(args) {
				i++
				return args[i]
			}
			return ""
		}
		switch a {
		case "--cwd":
			o.cwd = next()
		case "--resume":
			o.resume = next()
		case "--model":
			o.model = next()
		case "--permission-mode":
			o.permissionMode = next()
		case "--effort":
			o.effort = next()
		case "--append-system-prompt":
			o.systemPrompt = next()
		case "--title":
			o.title = next()
		case "--phone-mcp":
			o.phoneMCP = true
		case "--mcp-config", "--settings", "--name":
			_ = next()
		}
	}
	return o
}

func (b *bridge) start() error {
	if b.opts.phoneMCP && b.opts.helperBin != "" {
		relay, err := startMCPRelay(b.emit)
		if err != nil {
			return err
		}
		b.mcp = relay
	}
	acp, err := startACP(os.Getenv("ZETA_AGY_BIN"), b.opts.cwd, b.stderr, b.handleACPNotify, b.handleACPRequest)
	if err != nil {
		b.mcp.close()
		return err
	}
	b.acp = acp
	initRaw, err := b.acp.call("initialize", map[string]any{
		"protocolVersion": 1,
		// agy acp 仅对 Zed/JetBrains/Xcode 等识别客户端开放 Claude 等第三方模型，保留 Zed 标识。
		"clientInfo": map[string]any{"name": "Zed", "version": "1.0.0"},
		"clientCapabilities": map[string]any{
			"fs":          map[string]any{"readTextFile": false, "writeTextFile": false},
			"terminal":    false,
			"elicitation": map[string]any{"form": map[string]any{}},
		},
	})
	if err != nil {
		b.acp.close()
		b.mcp.close()
		return err
	}
	b.supportsAppendSystemPrompt = parseSupportsAppendSystemPrompt(initRaw)
	return nil
}

func parseSupportsAppendSystemPrompt(raw json.RawMessage) bool {
	var res struct {
		AgentCapabilities struct {
			Meta struct {
				Antigravity struct {
					SystemPrompt struct {
						Append bool `json:"append"`
					} `json:"systemPrompt"`
				} `json:"antigravity"`
			} `json:"_meta"`
		} `json:"agentCapabilities"`
	}
	return json.Unmarshal(raw, &res) == nil && res.AgentCapabilities.Meta.Antigravity.SystemPrompt.Append
}

func (b *bridge) shutdown() {
	b.cleanupSession(false)
	b.mcp.close()
	if b.acp != nil {
		b.acp.close()
	}
}

func (b *bridge) cleanupSession(closeRemote bool) {
	b.mu.Lock()
	sid := b.sessionID
	unpromptedNew := sid != "" && !b.resumed && !b.prompted
	b.sessionID = ""
	b.mu.Unlock()
	if sid == "" {
		return
	}
	if closeRemote && b.acp != nil {
		_, _ = b.acp.call("session/close", map[string]any{"sessionId": sid})
	}
	if unpromptedNew {
		_, _ = agystore.Delete(b.agyDir, sid)
	}
}

func (b *bridge) ensureSession() error {
	b.sessMu.Lock()
	defer b.sessMu.Unlock()

	b.mu.Lock()
	if b.sessionID != "" {
		b.mu.Unlock()
		return nil
	}
	resume := b.opts.resume
	b.mu.Unlock()

	params := map[string]any{
		"cwd":        b.opts.cwd,
		"mcpServers": b.mcpServersParam(),
	}
	sysPrompt := strings.TrimSpace(b.opts.systemPrompt)
	withMetaPrompt := b.supportsAppendSystemPrompt && sysPrompt != ""
	if withMetaPrompt {
		params["_meta"] = map[string]any{
			"antigravity": map[string]any{
				"systemPrompt": map[string]any{"append": sysPrompt},
			},
		}
	}
	method := "session/new"
	if resume != "" {
		method = "session/resume"
		params["sessionId"] = resume
	}
	resRaw, err := b.acp.call(method, params)
	if err != nil {
		return err
	}
	var res acpSessionResult
	if json.Unmarshal(resRaw, &res) != nil || res.SessionID == "" {
		return errors.New("agy acp returned empty sessionId")
	}
	if res.Models != nil && len(res.Models.AvailableModels) > 0 {
		b.mu.Lock()
		b.availableModels = res.Models.AvailableModels
		b.mu.Unlock()
	}
	if res.Meta != nil && res.Meta.Antigravity != nil && res.Meta.Antigravity.Tier != nil {
		if tier := strings.TrimSpace(res.Meta.Antigravity.Tier.Name); tier != "" && TierReporterFunc != nil {
			TierReporterFunc(b.home, tier)
		}
	}
	b.applyConfigOptions(res.ConfigOptions)
	b.mu.Lock()
	b.sessionID = res.SessionID
	b.resumed = resume != ""
	b.opts.resume = ""
	initModel := b.opts.model
	initEffort := b.opts.effort
	initMode := b.userMode
	initTitle := b.title
	b.mu.Unlock()

	agystore.WriteZetaMeta(b.agyDir, res.SessionID, agystore.ZetaMeta{Cwd: b.opts.cwd, Title: initTitle})
	b.applyInitialOptions(initModel, initEffort, initMode)
	return nil
}

func (b *bridge) applyInitialOptions(model, effort, mode string) {
	b.mu.Lock()
	curModel, curEffort, curMode := b.currentModel, b.currentEffort, b.acpMode
	b.mu.Unlock()
	if model != "" && model != "default" && model != curModel {
		b.setConfigOption("model", model)
	}
	if effort != "" && effort != curEffort {
		b.setConfigOption("thought_level", effort)
	}
	if mode != "" {
		b.mu.Lock()
		b.userMode = mode
		b.mu.Unlock()
		acpMode := zetaModeToACP(mode)
		if acpMode != zetaModeToACP(curMode) {
			b.setConfigOption("mode", acpMode)
		}
	}
}

func (b *bridge) mcpServersParam() []any {
	if b.mcp == nil || b.opts.helperBin == "" {
		return []any{}
	}
	return []any{
		map[string]any{
			"name":    "phone",
			"command": b.opts.helperBin,
			"args":    []string{"mcp-sock", b.mcp.sockPath},
			"env":     []any{},
		},
	}
}

func (b *bridge) applyConfigOptions(opts []acpConfigOption) {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, opt := range opts {
		switch opt.ID {
		case "model":
			b.applyModelOption(opt)
		case "thought_level":
			b.currentEffort = cmp.Or(opt.CurrentValue, b.currentEffort)
		case "mode":
			b.acpMode = cmp.Or(acpModeToZeta(opt.CurrentValue), b.acpMode)
		}
	}
}

var effortOrder = []string{"low", "medium", "high", "xhigh", "max"}

func modelEfforts(baseID string, avail []acpModelItem) (hasInfo bool, levels []string) {
	if len(avail) == 0 {
		return false, nil
	}
	exact := false
	found := map[string]bool{}
	prefix := baseID + "-"
	for _, item := range avail {
		if item.ModelID == baseID {
			exact = true
			continue
		}
		if suffix, ok := strings.CutPrefix(item.ModelID, prefix); ok && slices.Contains(effortOrder, suffix) {
			found[suffix] = true
		}
	}
	if len(found) > 0 {
		for _, lv := range effortOrder {
			if found[lv] {
				levels = append(levels, lv)
			}
		}
		return true, levels
	}
	if exact {
		return true, []string{}
	}
	return false, nil
}

func (b *bridge) applyModelOption(opt acpConfigOption) {
	if opt.CurrentValue != "" {
		b.currentModel = opt.CurrentValue
		if b.defaultModel == "" {
			b.defaultModel = opt.CurrentValue
		}
	}
	if len(opt.Options) == 0 {
		return
	}
	models := make([]map[string]any, 0, len(opt.Options))
	for _, m := range opt.Options {
		entry := map[string]any{
			"value":       m.Value,
			"displayName": cmp.Or(m.Name, m.Value),
			"description": m.Description,
		}
		if hasInfo, levels := modelEfforts(m.Value, b.availableModels); hasInfo {
			entry["supportsEffort"] = len(levels) > 0
			entry["supportedEffortLevels"] = levels
		}
		models = append(models, entry)
	}
	b.models, _ = json.Marshal(models)
}

func (b *bridge) setConfigOption(configID, value string) {
	b.mu.Lock()
	sid := b.sessionID
	b.mu.Unlock()
	if sid == "" {
		return
	}
	resRaw, err := b.acp.call("session/set_config_option", map[string]any{
		"sessionId": sid,
		"configId":  configID,
		"value":     value,
	})
	if err != nil {
		return
	}
	var res acpConfigUpdate
	if json.Unmarshal(resRaw, &res) == nil && len(res.ConfigOptions) > 0 {
		b.applyConfigOptions(res.ConfigOptions)
		return
	}
	b.mu.Lock()
	switch configID {
	case "model":
		b.currentModel = value
	case "thought_level":
		b.currentEffort = value
	case "mode":
		b.acpMode = acpModeToZeta(value)
	}
	b.mu.Unlock()
}

func zetaModeToACP(mode string) string {
	switch mode {
	case "acceptEdits":
		return "accept-edits"
	case "bypassPermissions":
		return "auto"
	case "":
		return "default"
	default:
		return mode
	}
}

func acpModeToZeta(mode string) string {
	if mode == "accept-edits" {
		return "acceptEdits"
	}
	return mode
}

func (b *bridge) applyPermissionMode(mode string) {
	b.mu.Lock()
	b.userMode = mode
	b.mu.Unlock()
	b.setConfigOption("mode", zetaModeToACP(mode))
}

func (b *bridge) effectiveMode() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.userMode == "bypassPermissions" {
		return "bypassPermissions"
	}
	return cmp.Or(b.acpMode, b.userMode, "default")
}

func (b *bridge) readStdin(stdin io.Reader) {
	br := bufio.NewReaderSize(stdin, 256*1024)
	for {
		line, err := br.ReadBytes('\n')
		trimmed := bytes.TrimSpace(line)
		if len(trimmed) > 0 {
			b.handleInboundLine(trimmed)
		}
		if err != nil {
			b.requestExit(0)
			return
		}
	}
}

func (b *bridge) handleInboundLine(line []byte) {
	var hdr struct {
		Type        string          `json:"type"`
		UUID        string          `json:"uuid"`
		Command     string          `json:"command"`
		ShouldQuery *bool           `json:"shouldQuery"`
		RequestID   string          `json:"request_id"`
		Request     json.RawMessage `json:"request"`
		Response    json.RawMessage `json:"response"`
		Message     struct {
			Content json.RawMessage `json:"content"`
		} `json:"message"`
	}
	if json.Unmarshal(line, &hdr) != nil {
		return
	}
	switch hdr.Type {
	case "control_request":
		go b.handleControlRequest(hdr.RequestID, hdr.Request)
	case "control_response":
		b.handleControlResponse(hdr.Response)
	case "bash_command":
		go b.handleBashCommand(hdr.UUID, hdr.Command)
	case "user":
		b.handleUserMessage(hdr.UUID, hdr.ShouldQuery, hdr.Message.Content)
	}
}

func (b *bridge) handleControlResponse(rawResp json.RawMessage) {
	var r struct {
		RequestID string          `json:"request_id"`
		Response  json.RawMessage `json:"response"`
	}
	if json.Unmarshal(rawResp, &r) != nil || r.RequestID == "" {
		return
	}
	if b.mcp != nil && strings.HasPrefix(r.RequestID, "agy-mcp-") {
		var mr struct {
			MCPResponse json.RawMessage `json:"mcp_response"`
		}
		_ = json.Unmarshal(r.Response, &mr)
		b.mcp.handleReply(r.RequestID, mr.MCPResponse)
		return
	}
	b.handlePromptResponse(r.RequestID, r.Response)
}

func (b *bridge) handleControlRequest(reqID string, rawReq json.RawMessage) {
	var req struct {
		Subtype     string          `json:"subtype"`
		Model       string          `json:"model"`
		Mode        string          `json:"mode"`
		Title       string          `json:"title"`
		TaskID      string          `json:"task_id"`
		MessageUUID string          `json:"message_uuid"`
		Settings    json.RawMessage `json:"settings"`
	}
	if json.Unmarshal(rawReq, &req) != nil {
		b.replyControlError(reqID, "invalid control request")
		return
	}
	switch req.Subtype {
	case "initialize":
		b.controlInitialize(reqID)
	case "get_context_usage":
		b.controlContextUsage(reqID)
	case "get_settings":
		b.mu.Lock()
		effort := b.currentEffort
		b.mu.Unlock()
		b.replyControlSuccess(reqID, map[string]any{"applied": map[string]any{"effort": effort}})
	case "get_usage":
		b.controlGetUsage(reqID)
	case "set_model":
		if err := b.ensureSession(); err == nil {
			val := req.Model
			if val == "" || val == "default" {
				b.mu.Lock()
				val = b.defaultModel
				b.mu.Unlock()
			}
			if val != "" {
				b.setConfigOption("model", val)
			}
		}
		b.replyControlSuccess(reqID, map[string]any{})
	case "set_permission_mode":
		if err := b.ensureSession(); err == nil {
			b.applyPermissionMode(req.Mode)
			b.emit(map[string]any{"type": "system", "subtype": "status", "permissionMode": b.effectiveMode()})
		}
		b.replyControlSuccess(reqID, map[string]any{})
	case "apply_flag_settings":
		b.controlApplyFlagSettings(reqID, req.Settings)
	case "interrupt":
		b.controlInterrupt(reqID)
	case "cancel_async_message":
		b.cancelQueuedMessage(req.MessageUUID)
		b.replyControlSuccess(reqID, map[string]any{})
	case "rename_session":
		b.controlRename(reqID, req.Title)
	case "stop_task":
		b.replyControlError(reqID, locales.M("errAgyStopTaskUnsupported").String())
	case "get_task_output":
		b.controlGetTaskOutput(reqID, req.TaskID)
	case "end_session":
		b.controlEndSession(reqID)
	default:
		b.replyControlSuccess(reqID, map[string]any{})
	}
}

func (b *bridge) controlGetTaskOutput(reqID, taskID string) {
	if !agystore.IsSessionID(taskID) {
		b.replyControlError(reqID, locales.M("errAgyTaskOutputMissing").String())
		return
	}
	path := agystore.TranscriptPath(b.agyDir, taskID)
	data, err := os.ReadFile(path)
	if err != nil {
		b.replyControlError(reqID, locales.M("errAgyTaskOutputMissing").String())
		return
	}
	const maxOut = 8192
	total := len(data)
	truncated := false
	if len(data) > maxOut {
		data = data[len(data)-maxOut:]
		truncated = true
	}
	b.replyControlSuccess(reqID, map[string]any{
		"output":      string(data),
		"total_bytes": total,
		"truncated":   truncated,
	})
}

func (b *bridge) controlInitialize(reqID string) {
	if err := b.ensureSession(); err != nil {
		b.replyControlError(reqID, err.Error())
		return
	}
	b.mu.Lock()
	hasCmds := len(b.commands) > 0
	b.mu.Unlock()
	if !hasCmds {
		select {
		case <-b.commandsReady:
		case <-time.After(250 * time.Millisecond):
		}
	}
	b.mu.Lock()
	models := b.models
	cmds := b.commands
	b.mu.Unlock()
	if len(models) == 0 {
		models = json.RawMessage(`[]`)
	}
	if len(cmds) == 0 {
		cmds = json.RawMessage(`[]`)
	}
	resp := map[string]any{
		"models":                  models,
		"commands":                cmds,
		"current_permission_mode": b.effectiveMode(),
		"capabilities":            []string{},
	}
	b.emit(map[string]any{
		"type": "control_response",
		"response": map[string]any{
			"subtype":                     "success",
			"request_id":                  reqID,
			"response":                    resp,
			"pending_permission_requests": b.pendingPermissionSnapshot(),
		},
	})
}

func (b *bridge) controlContextUsage(reqID string) {
	b.mu.Lock()
	win := b.windowSize
	tok := b.contextTokens
	model := b.currentModel
	b.mu.Unlock()
	res := map[string]any{
		"maxTokens":            win,
		"model":                model,
		"isAutoCompactEnabled": true,
	}
	if tok > 0 {
		res["totalTokens"] = tok
	}
	b.replyControlSuccess(reqID, res)
}

func (b *bridge) controlGetUsage(reqID string) {
	if UsageQueryFunc == nil {
		b.replyControlError(reqID, "quota query unavailable")
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	raw, err := UsageQueryFunc(ctx, b.home)
	if err != nil {
		b.replyControlError(reqID, err.Error())
		return
	}
	b.replyControlSuccess(reqID, raw)
}

func (b *bridge) controlApplyFlagSettings(reqID string, rawSettings json.RawMessage) {
	var s struct {
		EffortLevel *string `json:"effortLevel"`
		Permissions *struct {
			Deny []string `json:"deny"`
		} `json:"permissions"`
	}
	if json.Unmarshal(rawSettings, &s) == nil {
		if s.EffortLevel != nil && *s.EffortLevel != "" {
			if err := b.ensureSession(); err == nil {
				b.setConfigOption("thought_level", *s.EffortLevel)
			}
		}
		if s.Permissions != nil && b.mcp != nil {
			b.mcp.setDeniedTools(extractDeniedPhoneTools(s.Permissions.Deny))
		}
	}
	b.replyControlSuccess(reqID, map[string]any{})
}

func extractDeniedPhoneTools(rules []string) []string {
	var denied []string
	for _, r := range rules {
		if name, ok := strings.CutPrefix(strings.TrimSpace(r), "mcp__phone__"); ok && name != "" {
			denied = append(denied, name)
		}
	}
	return denied
}

func (b *bridge) controlInterrupt(reqID string) {
	b.cancelPendingPrompts()
	b.mu.Lock()
	sid := b.sessionID
	b.mu.Unlock()
	if sid != "" {
		_, _ = b.acp.call("session/cancel", map[string]any{"sessionId": sid})
	}
	b.replyControlSuccess(reqID, map[string]any{})
}

func (b *bridge) controlRename(reqID, title string) {
	b.mu.Lock()
	sid := b.sessionID
	b.title = title
	b.mu.Unlock()
	if sid != "" && strings.TrimSpace(title) != "" {
		_ = agystore.Rename(b.agyDir, sid, strings.TrimSpace(title))
	}
	b.replyControlSuccess(reqID, map[string]any{})
}

func (b *bridge) controlEndSession(reqID string) {
	b.closing.Store(true)
	b.cancelPendingPrompts()
	b.cleanupSession(true)
	b.replyControlSuccess(reqID, map[string]any{})
	b.requestExit(0)
}

func (b *bridge) handleUserMessage(uuid string, shouldQuery *bool, rawContent json.RawMessage) {
	acpBlocks, text := parseInboundUserContent(rawContent)
	if shouldQuery != nil && !*shouldQuery {
		if strings.TrimSpace(text) != "" {
			b.mu.Lock()
			b.contextNotes = append(b.contextNotes, text)
			sid := b.sessionID
			b.mu.Unlock()
			b.emit(map[string]any{"type": "system", "subtype": "init", "session_id": sid, "user_message_uuids": []string{uuid}})
			b.emit(map[string]any{"type": "result", "subtype": "success", "session_id": sid, "num_turns": 0, "user_message_uuids": []string{uuid}})
		}
		return
	}
	if strings.TrimSpace(text) == "/clear" && len(acpBlocks) <= 1 {
		go b.runClear(uuid)
		return
	}
	b.enqueuePrompt(queuedPrompt{uuid: uuid, blocks: acpBlocks, text: text})
}

func parseInboundUserContent(raw json.RawMessage) ([]map[string]any, string) {
	var plain string
	if json.Unmarshal(raw, &plain) == nil {
		return []map[string]any{{"type": "text", "text": plain}}, plain
	}
	var list []struct {
		Type   string `json:"type"`
		Text   string `json:"text"`
		Source struct {
			MediaType string `json:"media_type"`
			Data      string `json:"data"`
		} `json:"source"`
	}
	_ = json.Unmarshal(raw, &list)
	var out []map[string]any
	var texts []string
	for _, blk := range list {
		switch blk.Type {
		case "text":
			out = append(out, map[string]any{"type": "text", "text": blk.Text})
			texts = append(texts, blk.Text)
		case "image":
			if blk.Source.Data != "" {
				out = append(out, map[string]any{
					"type":     "image",
					"mimeType": cmp.Or(blk.Source.MediaType, "image/png"),
					"data":     blk.Source.Data,
				})
			}
		}
	}
	return out, strings.Join(texts, "\n")
}

func (b *bridge) enqueuePrompt(q queuedPrompt) {
	b.turnM.Lock()
	b.queue = append(b.queue, q)
	if b.turnActive {
		b.turnM.Unlock()
		return
	}
	b.turnActive = true
	b.turnM.Unlock()
	go b.drainQueue()
}

func (b *bridge) cancelQueuedMessage(uuid string) {
	if uuid == "" {
		return
	}
	b.turnM.Lock()
	idx := slices.IndexFunc(b.queue, func(q queuedPrompt) bool { return q.uuid == uuid })
	if idx >= 0 {
		b.queue = slices.Delete(b.queue, idx, idx+1)
	}
	b.turnM.Unlock()
	if idx >= 0 {
		b.emit(map[string]any{"type": "command_lifecycle", "command_uuid": uuid, "state": "cancelled"})
	}
}

func (b *bridge) drainQueue() {
	for {
		b.turnM.Lock()
		if len(b.queue) == 0 {
			b.turnActive = false
			b.turnM.Unlock()
			return
		}
		q := b.queue[0]
		b.queue = b.queue[1:]
		b.turnSeq++
		seq := b.turnSeq
		b.turnM.Unlock()

		b.runPromptTurn(seq, q)
	}
}

func (b *bridge) runPromptTurn(seq int, q queuedPrompt) {
	start := time.Now()
	if q.uuid != "" {
		b.emit(map[string]any{"type": "command_lifecycle", "command_uuid": q.uuid, "state": "started"})
	}
	if err := b.ensureSession(); err != nil {
		b.emit(map[string]any{
			"type":     "result",
			"subtype":  "error_during_execution",
			"is_error": true,
			"result":   err.Error(),
		})
		return
	}
	b.mu.Lock()
	b.prompted = true
	sid := b.sessionID
	model := b.currentModel
	notes := b.contextNotes
	b.contextNotes = nil
	sysPrompt := strings.TrimSpace(b.opts.systemPrompt)
	if seq > 1 || b.supportsAppendSystemPrompt {
		sysPrompt = ""
	}
	b.mu.Unlock()

	b.emit(map[string]any{
		"type":           "system",
		"subtype":        "init",
		"session_id":     sid,
		"model":          model,
		"permissionMode": b.effectiveMode(),
	})

	isCompact := strings.TrimSpace(q.text) == "/compact"
	if isCompact {
		b.emit(map[string]any{"type": "system", "subtype": "status", "status": "compacting"})
	}

	blocks := prependPromptContext(q.blocks, notes, sysPrompt)
	ts := newTurnStream(seq)
	b.streamM.Lock()
	b.curStream = ts
	b.streamM.Unlock()

	resRaw, err := b.acp.call("session/prompt", map[string]any{
		"sessionId": sid,
		"prompt":    blocks,
	})

	b.streamM.Lock()
	b.closeOpenBlock(ts)
	b.curStream = nil
	b.streamM.Unlock()
	b.cancelPendingPrompts()

	if err != nil {
		b.emit(map[string]any{
			"type":       "result",
			"subtype":    "error_during_execution",
			"is_error":   true,
			"session_id": sid,
			"result":     err.Error(),
		})
		return
	}

	var pr acpPromptResult
	_ = json.Unmarshal(resRaw, &pr)
	if isCompact {
		b.emit(map[string]any{
			"type":             "system",
			"subtype":          "compact_boundary",
			"compact_metadata": map[string]any{"trigger": "manual"},
		})
	}
	b.emitPromptResult(sid, pr, time.Since(start).Milliseconds())
}

func prependPromptContext(blocks []map[string]any, notes []string, sysPrompt string) []map[string]any {
	var extra []map[string]any
	if sysPrompt != "" {
		extra = append(extra, map[string]any{"type": "text", "text": sysPrompt})
	}
	for _, n := range notes {
		extra = append(extra, map[string]any{"type": "text", "text": n})
	}
	if len(extra) == 0 {
		return blocks
	}
	return append(extra, blocks...)
}

func (b *bridge) emitPromptResult(sid string, pr acpPromptResult, durationMs int64) {
	b.mu.Lock()
	model := cmp.Or(b.currentModel, "agy")
	win := b.windowSize
	var inTok, outTok, cacheRead, cacheWrite int64
	if pr.Usage != nil {
		cacheRead = pr.Usage.CachedReadTokens
		cacheWrite = pr.Usage.CachedWriteTokens
		inTok = max(0, pr.Usage.InputTokens-cacheRead)
		outTok = pr.Usage.OutputTokens + pr.Usage.ThoughtTokens
		if total := pr.Usage.InputTokens + outTok; total > 0 {
			b.contextTokens = total
		}
		b.cumInput += inTok
		b.cumOutput += outTok
		b.cumCacheRead += cacheRead
		b.cumCacheWrite += cacheWrite
	}
	cumIn, cumOut, cumCache, cumWrite := b.cumInput, b.cumOutput, b.cumCacheRead, b.cumCacheWrite
	b.mu.Unlock()

	res := map[string]any{
		"type":        "result",
		"subtype":     "success",
		"is_error":    false,
		"session_id":  sid,
		"duration_ms": durationMs,
	}
	if pr.Usage != nil {
		res["usage"] = map[string]any{
			"input_tokens":                inTok,
			"output_tokens":               outTok,
			"cache_read_input_tokens":     cacheRead,
			"cache_creation_input_tokens": cacheWrite,
		}
		res["modelUsage"] = map[string]any{
			model: map[string]any{
				"inputTokens":              cumIn,
				"outputTokens":             cumOut,
				"cacheReadInputTokens":     cumCache,
				"cacheCreationInputTokens": cumWrite,
				"contextWindow":            win,
			},
		}
	}
	b.emit(res)
}

func (b *bridge) runClear(uuid string) {
	b.mu.Lock()
	model := b.currentModel
	effort := b.currentEffort
	mode := b.userMode
	b.contextTokens = 0
	b.cumInput, b.cumOutput, b.cumCacheRead, b.cumCacheWrite = 0, 0, 0, 0
	b.contextNotes = nil
	b.mu.Unlock()

	b.turnM.Lock()
	b.turnSeq = 0
	b.turnM.Unlock()

	b.cleanupSession(true)
	b.mu.Lock()
	b.resumed = false
	b.prompted = false
	b.opts.model = model
	b.opts.effort = effort
	b.userMode = mode
	b.mu.Unlock()
	if uuid != "" {
		b.emit(map[string]any{"type": "command_lifecycle", "command_uuid": uuid, "state": "started"})
	}
	if err := b.ensureSession(); err != nil {
		b.emit(map[string]any{"type": "result", "subtype": "error_during_execution", "is_error": true, "result": err.Error()})
		return
	}
	b.mu.Lock()
	newID := b.sessionID
	b.mu.Unlock()
	b.emit(map[string]any{"type": "conversation_reset", "new_conversation_id": newID})
	b.emit(map[string]any{"type": "system", "subtype": "init", "session_id": newID, "model": model, "permissionMode": b.effectiveMode()})
	b.emit(map[string]any{"type": "result", "subtype": "success", "session_id": newID})
}

func (b *bridge) handleBashCommand(uuid, command string) {
	b.mu.Lock()
	b.prompted = true
	b.mu.Unlock()
	b.emit(map[string]any{"type": "command_lifecycle", "command_uuid": uuid, "state": "started"})
	b.emit(map[string]any{"type": "command_lifecycle", "command_uuid": uuid, "state": "completed"})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "bash", "-lc", command)
	cmd.Dir = b.opts.cwd
	var stdoutBuf, stderrBuf bytes.Buffer
	cmd.Stdout = &stdoutBuf
	cmd.Stderr = &stderrBuf
	err := cmd.Run()
	exitCode := 0
	if ctx.Err() == context.DeadlineExceeded {
		exitCode = 124
	} else if ee, ok := errors.AsType[*exec.ExitError](err); ok {
		exitCode = ee.ExitCode()
	} else if err != nil {
		exitCode = 1
	}

	b.emit(map[string]any{
		"type":     "user",
		"isReplay": true,
		"message":  map[string]any{"content": "<bash-input>" + command + "</bash-input>"},
	})
	outText := "<bash-stdout>" + stdoutBuf.String() + "</bash-stdout><bash-stderr>" + stderrBuf.String() + "</bash-stderr>"
	if exitCode != 0 {
		outText += fmt.Sprintf("<bash-exit-code>%d</bash-exit-code>", exitCode)
	}
	b.emit(map[string]any{
		"type":     "user",
		"isReplay": true,
		"message":  map[string]any{"content": outText},
	})
}

func (b *bridge) replyControlSuccess(reqID string, resp any) {
	b.emit(map[string]any{
		"type": "control_response",
		"response": map[string]any{
			"subtype":    "success",
			"request_id": reqID,
			"response":   resp,
		},
	})
}

func (b *bridge) replyControlError(reqID, errMsg string) {
	b.emit(map[string]any{
		"type": "control_response",
		"response": map[string]any{
			"subtype":    "error",
			"request_id": reqID,
			"error":      errMsg,
		},
	})
}

func (b *bridge) emit(v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	b.emitRaw(data)
}

func (b *bridge) emitRaw(data []byte) {
	b.outM.Lock()
	defer b.outM.Unlock()
	_, _ = b.out.Write(data)
	_ = b.out.WriteByte('\n')
	_ = b.out.Flush()
}

func (b *bridge) requestExit(code int) {
	b.exitOnce.Do(func() {
		b.exitCh <- code
	})
}
