package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/agyacct"
	"github.com/dzshzx/Zeta/helper/claudecfg"
)

// 核心的 config.* 方法（挂在核心上，见 corehandlers.go）：读写这台主机上 Claude Code 自己的配置（记忆、Skills、MCP、Subagent、工具权限、新对话默认），
// 逻辑在 claudecfg 包里。参数和 id、method 平级写在请求那一行里；成功时结果放在回复的 result 里，失败时 error 是给人看的
// 中文说明（不含 Token）。方法清单和参数见 docs/core.md「Claude Code 配置」。
//
// handleConfig 没有共享状态，可以放进 goroutine 里并发调用：标“慢”的几个要跑 claude 或连 MCP 服务器，
// 要十几秒到一分钟，别挡住同一客户端的 ping。

var errConfigParams = errors.New("参数不对")

// configMethods 是各方法：拿到这台的配置和解好的参数，返回 result。
var configMethods = map[string]func(context.Context, *claudecfg.Store, json.RawMessage) (any, error){
	"config.models.read":                noParams(readModelCatalog),
	"config.defaults.read":              noParams(readDefaults),
	"config.defaults.setModel":          withParams(setDefaultModel),
	"config.defaults.setPermissionMode": withParams(setDefaultPermissionMode),
	"config.defaults.setEffort":         withParams(setDefaultEffort),
	"config.defaults.setCompactWindow":  withParams(setCompactWindow),

	"config.memory.read":      withParams(readMemory),
	"config.memory.readFile":  withParams(readMemoryFile),
	"config.memory.writeFile": withParams(writeMemoryFile),
	"config.memory.delete":    withParams(deleteMemory),
	"config.memory.setAuto":   withParams(setAutoMemory),

	"config.skills.list": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Cwd string `json:"cwd"`
	}) (any, error) {
		return s.SkillsIn(p.Cwd), nil
	}),
	"config.skills.import":     withParams(importSkill),
	"config.skills.setEnabled": withParams(setSkillEnabled),
	"config.skills.delete":     withParams(deleteSkill),

	"config.agents.list": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Cwd string `json:"cwd"`
	}) (any, error) {
		return s.AgentsIn(p.Cwd), nil
	}),
	"config.agents.save":   withParams(saveAgent),
	"config.agents.delete": withParams(deleteAgent),

	"config.permissions.read":    withParams(readPermissions),
	"config.permissions.setTool": withParams(setToolPermission),
	"config.permissions.add":     withParams(addPermissionRule),
	"config.permissions.remove":  withParams(removePermissionRule),
	"config.permissions.replace": withParams(replacePermissionRule),
	"config.permissions.bulk":    withParams(bulkPermission),

	"config.mcp.list":     withParams(listMcp),
	"config.mcp.status":   withParams(mcpStatus),
	"config.mcp.tools":    withParams(mcpTools),
	"config.mcp.addHttp":  withParams(addHTTPMcp),
	"config.mcp.addStdio": withParams(addStdioMcp),
	"config.mcp.remove":   withParams(removeMcp),
	"config.mcp.setToken": withParams(setMcpToken),

	"config.agy.settings.read": noParams(func(_ context.Context, s *claudecfg.Store) (any, error) {
		return s.ReadAgySettings()
	}),
	"config.agy.settings.update": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Patch claudecfg.AgySettingsPatch `json:"patch"`
	}) (any, error) {
		return s.UpdateAgySettings(p.Patch)
	}),
	"config.plugins.list": noParams(func(ctx context.Context, s *claudecfg.Store) (any, error) {
		return s.ListPlugins(ctx)
	}),
	"config.plugins.setEnabled": withParams(func(ctx context.Context, s *claudecfg.Store, p struct {
		Name    string `json:"name"`
		Enabled bool   `json:"enabled"`
	}) (any, error) {
		return s.SetPluginEnabled(ctx, p.Name, p.Enabled)
	}),
	"config.plugins.install": withParams(func(ctx context.Context, s *claudecfg.Store, p struct {
		Target string `json:"target"`
	}) (any, error) {
		return s.InstallPlugin(ctx, p.Target)
	}),
	"config.plugins.uninstall": withParams(func(ctx context.Context, s *claudecfg.Store, p struct {
		Name string `json:"name"`
	}) (any, error) {
		return s.UninstallPlugin(ctx, p.Name)
	}),
	"config.hooks.read": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Cwd string `json:"cwd"`
	}) (any, error) {
		return s.ReadHooks(p.Cwd)
	}),
	"config.hooks.setEnabled": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Cwd     string `json:"cwd"`
		Scope   string `json:"scope"`
		Name    string `json:"name"`
		Enabled bool   `json:"enabled"`
	}) (any, error) {
		return s.SetHookEnabled(p.Cwd, p.Scope, p.Name, p.Enabled)
	}),
	"config.hooks.save": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Cwd   string                 `json:"cwd"`
		Draft claudecfg.AgyHookDraft `json:"draft"`
	}) (any, error) {
		return s.SaveHook(p.Cwd, p.Draft)
	}),
	"config.hooks.delete": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		Cwd   string `json:"cwd"`
		Scope string `json:"scope"`
		Name  string `json:"name"`
	}) (any, error) {
		return s.DeleteHook(p.Cwd, p.Scope, p.Name)
	}),
	"config.sidecars.list": noParams(func(_ context.Context, s *claudecfg.Store) (any, error) {
		return s.ListSidecars()
	}),
	"config.sidecars.setEnabled": withParams(func(_ context.Context, s *claudecfg.Store, p struct {
		ID        string  `json:"id"`
		Enabled   bool    `json:"enabled"`
		ProjectID *string `json:"projectId,omitempty"`
	}) (any, error) {
		return s.SetSidecarEnabled(p.ID, p.Enabled, p.ProjectID)
	}),
}

// handleConfig 处理一个 config.* 请求：params 是请求那一行（或只有参数的对象），多出的字段（id、method）不管。
// 家目录是这个进程的 $HOME。
func handleConfig(ctx context.Context, method string, params json.RawMessage) (any, error) {
	return handleConfigAt(ctx, claudecfg.New(userHome()), method, params)
}

// handleConfigAt 同 handleConfig，配置在 s 那里。
func handleConfigAt(ctx context.Context, s *claudecfg.Store, method string, params json.RawMessage) (any, error) {
	f, ok := configMethods[method]
	if !ok {
		return nil, errors.New("unknown method: " + method)
	}
	if len(params) > 0 {
		var hdr struct {
			Harness string `json:"harness"`
		}
		if json.Unmarshal(params, &hdr) == nil && hdr.Harness != "" {
			s = s.ForHarness(hdr.Harness)
		}
	}
	return f(ctx, s, params)
}

func noParams(f func(context.Context, *claudecfg.Store) (any, error)) func(context.Context, *claudecfg.Store, json.RawMessage) (any, error) {
	return func(ctx context.Context, s *claudecfg.Store, _ json.RawMessage) (any, error) { return f(ctx, s) }
}

func withParams[P any](f func(context.Context, *claudecfg.Store, P) (any, error)) func(context.Context, *claudecfg.Store, json.RawMessage) (any, error) {
	return func(ctx context.Context, s *claudecfg.Store, raw json.RawMessage) (any, error) {
		var p P
		if len(raw) > 0 {
			if err := json.Unmarshal(raw, &p); err != nil {
				return nil, errConfigParams
			}
		}
		return f(ctx, s, p)
	}
}

// ---------- 新对话默认 ----------

type valueParams struct {
	Value string `json:"value"`
}

type relayModelOps struct {
	readCatalog     func(context.Context) (accounts.ModelCatalog, error)
	defaultModel    func(context.Context) (string, error)
	setDefaultModel func(context.Context, string) (bool, error)
}

func relayModelsFor(s *claudecfg.Store) relayModelOps {
	home := s.Home()
	if s.IsAgy() {
		return relayModelOps{
			readCatalog: func(ctx context.Context) (accounts.ModelCatalog, error) {
				out, err := agyacct.ReadModelCatalog(ctx, home)
				if err != nil || out.Relay != "" {
					return out, err
				}
				items := s.ReadAgyModels(ctx)
				models := make([]accounts.RelayModel, len(items))
				for i, it := range items {
					models[i] = accounts.RelayModel{ID: it.ID, DisplayName: it.DisplayName}
				}
				return accounts.ModelCatalog{Models: models}, nil
			},
			defaultModel:    func(context.Context) (string, error) { return agyacct.RelayDefaultModel(home), nil },
			setDefaultModel: func(_ context.Context, v string) (bool, error) { return agyacct.SetRelayDefaultModel(home, v) },
		}
	}
	return relayModelOps{
		readCatalog:     func(ctx context.Context) (accounts.ModelCatalog, error) { return accounts.ReadModelCatalog(ctx, home) },
		defaultModel:    func(ctx context.Context) (string, error) { return accounts.RelayDefaultModel(ctx, home) },
		setDefaultModel: func(ctx context.Context, v string) (bool, error) { return accounts.SetRelayDefaultModel(ctx, home, v) },
	}
}

func readModelCatalog(ctx context.Context, s *claudecfg.Store) (any, error) {
	out, err := relayModelsFor(s).readCatalog(ctx)
	if err == nil && out.Model == "" && (out.Relay != "" || s.IsAgy()) {
		// 用户级设置读不到时仍可列出模型，只是没有可继承的默认模型。
		d, _ := s.Defaults()
		out.Model = d.Model
	}
	return out, err
}

func readDefaults(ctx context.Context, s *claudecfg.Store) (any, error) {
	return withEffectiveModel(ctx, s, s.Defaults)
}

func withEffectiveModel(ctx context.Context, s *claudecfg.Store, defaults func() (claudecfg.Defaults, error)) (any, error) {
	model, err := relayModelsFor(s).defaultModel(ctx)
	if err != nil {
		return nil, err
	}
	d, err := defaults()
	if model != "" {
		d.Model = model
	}
	return d, err
}

func setDefaultModel(ctx context.Context, s *claudecfg.Store, p valueParams) (any, error) {
	// 先读用户设置：读不了就什么都不写，不留下只改了中转的一半。
	d, err := s.Defaults()
	if err != nil {
		return nil, err
	}
	changed, err := relayModelsFor(s).setDefaultModel(ctx, p.Value)
	if err != nil {
		return nil, err
	}
	if !changed {
		return s.SetDefaultModel(p.Value)
	}
	if model := strings.TrimSpace(p.Value); model != "" {
		d.Model = model
	}
	return d, nil
}

func setDefaultPermissionMode(ctx context.Context, s *claudecfg.Store, p valueParams) (any, error) {
	if p.Value == "" {
		return nil, errConfigParams
	}
	return withEffectiveModel(ctx, s, func() (claudecfg.Defaults, error) { return s.SetDefaultPermissionMode(p.Value) })
}

func setDefaultEffort(ctx context.Context, s *claudecfg.Store, p struct {
	Model string `json:"model"`
	Value string `json:"value"`
}) (any, error) {
	return withEffectiveModel(ctx, s, func() (claudecfg.Defaults, error) { return s.SetDefaultEffort(p.Model, p.Value) })
}

func setCompactWindow(ctx context.Context, s *claudecfg.Store, p struct {
	Value int64 `json:"value"`
}) (any, error) {
	return withEffectiveModel(ctx, s, func() (claudecfg.Defaults, error) { return s.SetCompactWindow(p.Value) })
}

// ---------- 记忆 ----------

type pathParams struct {
	Path string `json:"path"`
}

func readMemory(_ context.Context, s *claudecfg.Store, p struct {
	Cwd     string `json:"cwd"`
	Project string `json:"project"`
}) (any, error) {
	return s.Memory(p.Cwd, p.Project), nil
}

func readMemoryFile(_ context.Context, s *claudecfg.Store, p pathParams) (any, error) {
	return s.ReadDocument(p.Path)
}

func writeMemoryFile(_ context.Context, s *claudecfg.Store, p struct {
	Path    string `json:"path"`
	Content string `json:"content"`
	SHA     string `json:"sha"`
	Force   bool   `json:"force"`
}) (any, error) {
	sha, err := s.WriteDocument(p.Path, p.Content, p.SHA, p.Force)
	if err == claudecfg.ErrConflict {
		return obj{"conflict": true}, nil
	}
	if err != nil {
		return nil, err
	}
	return obj{"sha": sha}, nil
}

func deleteMemory(_ context.Context, s *claudecfg.Store, p pathParams) (any, error) {
	return nil, s.DeleteMemory(p.Path)
}

func setAutoMemory(_ context.Context, s *claudecfg.Store, p struct {
	On bool `json:"on"`
}) (any, error) {
	return nil, s.SetAutoMemory(p.On)
}

// ---------- Skills ----------

func importSkill(_ context.Context, s *claudecfg.Store, p struct {
	FileName string `json:"fileName"`
	Zip      []byte `json:"zip"` // base64
	Replace  bool   `json:"replace"`
}) (any, error) {
	return s.ImportSkill(p.FileName, p.Zip, p.Replace)
}

func setSkillEnabled(_ context.Context, s *claudecfg.Store, p struct {
	Name    string `json:"name"`
	Title   string `json:"title"`
	Enabled bool   `json:"enabled"`
}) (any, error) {
	return nil, s.SetSkillEnabled(claudecfg.Skill{Name: p.Name, Title: p.Title}, p.Enabled)
}

func deleteSkill(_ context.Context, s *claudecfg.Store, p struct {
	Name string `json:"name"`
}) (any, error) {
	return nil, s.DeleteSkill(p.Name)
}

// ---------- Subagent ----------

func saveAgent(_ context.Context, s *claudecfg.Store, p struct {
	Original *claudecfg.Subagent     `json:"original"`
	Draft    claudecfg.SubagentDraft `json:"draft"`
}) (any, error) {
	file, err := s.SaveAgent(p.Original, p.Draft)
	if err != nil {
		return nil, err
	}
	return obj{"file": file}, nil
}

func deleteAgent(_ context.Context, s *claudecfg.Store, p struct {
	File string `json:"file"`
}) (any, error) {
	return nil, s.DeleteAgent(p.File)
}

// ---------- 工具权限 ----------

// viewParams 是权限方法都带的：返回的页面按 cwd 列 MCP 服务器，给了 server 时另算它的 tools。
type viewParams struct {
	Cwd    string   `json:"cwd"`
	Server string   `json:"server"`
	Tools  []string `json:"tools"`
}

func permissionsView(s *claudecfg.Store, v viewParams, rules claudecfg.Rules, err error) (any, error) {
	if err != nil {
		return nil, err
	}
	return s.PermissionsView(rules, v.Cwd, v.Server, v.Tools), nil
}

func readPermissions(_ context.Context, s *claudecfg.Store, p viewParams) (any, error) {
	rules, err := s.Permissions()
	return permissionsView(s, p, rules, err)
}

func setToolPermission(_ context.Context, s *claudecfg.Store, p struct {
	viewParams
	Tool     string             `json:"tool"`
	Behavior claudecfg.Behavior `json:"behavior"`
}) (any, error) {
	rules, err := s.SetToolPermission(p.Tool, p.Behavior)
	return permissionsView(s, p.viewParams, rules, err)
}

type ruleParams struct {
	viewParams
	claudecfg.Rule
}

func addPermissionRule(_ context.Context, s *claudecfg.Store, p ruleParams) (any, error) {
	rules, err := s.AddPermissionRule(p.Rule)
	return permissionsView(s, p.viewParams, rules, err)
}

func removePermissionRule(_ context.Context, s *claudecfg.Store, p ruleParams) (any, error) {
	rules, err := s.RemovePermissionRule(p.Rule)
	return permissionsView(s, p.viewParams, rules, err)
}

func replacePermissionRule(_ context.Context, s *claudecfg.Store, p struct {
	viewParams
	Old claudecfg.Rule `json:"old"`
	New claudecfg.Rule `json:"new"`
}) (any, error) {
	rules, err := s.ReplacePermissionRule(p.Old, p.New)
	return permissionsView(s, p.viewParams, rules, err)
}

func bulkPermission(_ context.Context, s *claudecfg.Store, p struct {
	viewParams
	Group  claudecfg.PermissionGroup `json:"group"`
	Enable bool                      `json:"enable"`
}) (any, error) {
	if len(p.Group.Tools) == 0 && p.Group.Server == "" {
		return nil, errConfigParams
	}
	rules, err := s.SetGroupPermission(p.Group, p.Enable)
	return permissionsView(s, p.viewParams, rules, err)
}

// ---------- MCP ----------

type serverParams struct {
	Cwd   string `json:"cwd"`
	Scope string `json:"scope"`
	Name  string `json:"name"`
}

func listMcp(_ context.Context, s *claudecfg.Store, p struct {
	Cwd string `json:"cwd"`
}) (any, error) {
	return s.McpServers(p.Cwd)
}

func mcpStatus(ctx context.Context, s *claudecfg.Store, p struct {
	Cwd string `json:"cwd"`
}) (any, error) {
	return s.McpStatus(ctx, p.Cwd)
}

func mcpTools(ctx context.Context, s *claudecfg.Store, p serverParams) (any, error) {
	return s.McpTools(ctx, p.Scope, p.Name, p.Cwd)
}

func addHTTPMcp(ctx context.Context, s *claudecfg.Store, p struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	URL   string `json:"url"`
	Token string `json:"token"`
}) (any, error) {
	if p.Type != "http" && p.Type != "sse" {
		return nil, errConfigParams
	}
	return nil, s.AddHTTPServer(ctx, p.Name, p.Type, p.URL, p.Token)
}

func addStdioMcp(ctx context.Context, s *claudecfg.Store, p struct {
	Name    string            `json:"name"`
	Command string            `json:"command"`
	Args    []string          `json:"args"`
	Env     map[string]string `json:"env"`
}) (any, error) {
	return nil, s.AddStdioServer(ctx, p.Name, p.Command, p.Args, p.Env)
}

func removeMcp(ctx context.Context, s *claudecfg.Store, p serverParams) (any, error) {
	return nil, s.RemoveMcp(ctx, p.Scope, p.Name, p.Cwd)
}

func setMcpToken(ctx context.Context, s *claudecfg.Store, p struct {
	serverParams
	Token string `json:"token"`
}) (any, error) {
	return nil, s.SetMcpToken(ctx, p.Scope, p.Name, p.Cwd, p.Token)
}
