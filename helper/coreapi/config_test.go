package coreapi

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"testing"
)

// fakeCaller 记下发出的方法和参数（编成 JSON，键按字母序），回 reply。
type fakeCaller struct {
	method string
	params string
	reply  string
	err    error
}

func (f *fakeCaller) Call(_ context.Context, method string, p map[string]any) (json.RawMessage, error) {
	f.method = method
	b, err := json.Marshal(p)
	if err != nil {
		return nil, err
	}
	f.params = string(b)
	if f.err != nil {
		return nil, f.err
	}
	return json.RawMessage(f.reply), nil
}

// apiCase 是一个函数：发 method、参数编成 params，核心回 reply 时结果是 want。
type apiCase struct {
	name   string
	do     func(*Client) (any, error)
	method string
	params string
	reply  string
	want   any
}

func runCases(t *testing.T, cases []apiCase) {
	t.Helper()
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := &fakeCaller{reply: c.reply}
			got, err := c.do(New(f))
			if err != nil {
				t.Fatal(err)
			}
			if f.method != c.method {
				t.Errorf("方法 %q，要 %q", f.method, c.method)
			}
			if f.params != c.params {
				t.Errorf("参数 %s\n要 %s", f.params, c.params)
			}
			if !reflect.DeepEqual(got, c.want) {
				t.Errorf("结果 %#v\n要 %#v", got, c.want)
			}
		})
	}
}

var ctx = context.Background()

const defaultsJSON = `{"model":"sonnet","permissionMode":"plan","efforts":[{"model":"claude-opus-5-5","level":"max"}],"effort":"high","compactWindow":200000}`

var defaults = Defaults{Model: "sonnet", PermissionMode: "plan", Efforts: []ModelEffort{{"claude-opus-5-5", "max"}}, Effort: "high", CompactWindow: 200000}

const permJSON = `{"rules":[{"behavior":"deny","rule":"Bash(rm:*)","malformed":false}],` +
	`"builtin":[{"name":"Bash","label":"命令","example":"Bash(npm run *)","rule":"Bash","own":"","more":false,` +
	`"state":{"behavior":"","inherited":false,"specific":[{"behavior":"deny","rule":"Bash(rm:*)"}]}}],` +
	`"builtinBulk":{"enable":{"add":[{"behavior":"allow","rule":"Bash"}],"remove":null},"disable":{"add":null,"remove":null}},` +
	`"servers":[{"name":"gh","rule":"mcp__gh","scope":"user","oauth":true,"state":{"behavior":"ask","inherited":false,"specific":null},` +
	`"bulk":{"enable":{"add":null,"remove":null},"disable":{"add":null,"remove":null}}}],"serverTools":null}`

var perm = Permissions{
	Rules: []RuleView{{Rule: Rule{Deny, "Bash(rm:*)"}}},
	Builtin: []ToolView{{Name: "Bash", Label: "命令", Example: "Bash(npm run *)", Rule: "Bash",
		State: ToolState{Specific: []Rule{{Deny, "Bash(rm:*)"}}}}},
	BuiltinBulk: BulkPair{Enable: BulkChange{Add: []Rule{{Allow, "Bash"}}}},
	Servers:     []ServerView{{Name: "gh", Rule: "mcp__gh", Scope: "user", OAuth: true, State: ToolState{Behavior: Ask}}},
}

var view = PermissionView{Cwd: "/w", Server: "gh", Tools: []string{"mcp__gh__issue"}}

const viewJSON = `"cwd":"/w","server":"gh","tools":["mcp__gh__issue"]`

var gh = McpServer{Name: "gh", Scope: "local", Type: "http"}

// config.* 的每个函数发对方法和参数，结果解得出来。
func TestConfigMethods(t *testing.T) {
	agent := &Subagent{File: "rev.md", Name: "rev", SHA: "s1"}
	runCases(t, []apiCase{
		{"Defaults", func(cl *Client) (any, error) { return cl.Defaults(ctx) },
			"config.defaults.read", `{}`, defaultsJSON, defaults},
		{"SetDefaultModel", func(cl *Client) (any, error) { return cl.SetDefaultModel(ctx, "opus") },
			"config.defaults.setModel", `{"value":"opus"}`, defaultsJSON, defaults},
		{"SetDefaultPermissionMode", func(cl *Client) (any, error) { return cl.SetDefaultPermissionMode(ctx, "plan") },
			"config.defaults.setPermissionMode", `{"value":"plan"}`, defaultsJSON, defaults},
		{"SetDefaultEffort", func(cl *Client) (any, error) { return cl.SetDefaultEffort(ctx, "claude-opus-5-5", "max") },
			"config.defaults.setEffort", `{"model":"claude-opus-5-5","value":"max"}`, defaultsJSON, defaults},
		{"SetCompactWindow", func(cl *Client) (any, error) { return cl.SetCompactWindow(ctx, 200000) },
			"config.defaults.setCompactWindow", `{"value":200000}`, defaultsJSON, defaults},

		{"Memory", func(cl *Client) (any, error) { return cl.Memory(ctx, "/w", "p") },
			"config.memory.read", `{"cwd":"/w","project":"p"}`,
			`{"autoMemory":true,"forcedOff":false,"customDir":"","instructions":{"path":"/h/.claude/CLAUDE.md","exists":true,"size":3},` +
				`"projects":[{"key":"p","label":"w","count":1,"default":true}],"selected":"p","dir":"/m","index":null,` +
				`"files":[{"path":"/m/a.md","fileName":"a.md","name":"a","description":"d","type":"user","size":9}]}`,
			Memory{AutoMemory: true, Instructions: ConfigFile{"/h/.claude/CLAUDE.md", true, 3},
				Projects: []MemoryProject{{"p", "w", 1, true}}, Selected: "p", Dir: "/m",
				Files: []MemoryEntry{{"/m/a.md", "a.md", "a", "d", "user", 9}}}},
		{"ReadMemoryFile", func(cl *Client) (any, error) { return cl.ReadMemoryFile(ctx, "/m/a.md") },
			"config.memory.readFile", `{"path":"/m/a.md"}`, `{"content":"x","sha":"s","exists":true}`, Document{"x", "s", true}},
		{"WriteMemoryFile", func(cl *Client) (any, error) { return cl.WriteMemoryFile(ctx, "/m/a.md", "y", "s", true) },
			"config.memory.writeFile", `{"content":"y","force":true,"path":"/m/a.md","sha":"s"}`, `{"sha":"s2"}`, "s2"},
		{"DeleteMemory", func(cl *Client) (any, error) { return nil, cl.DeleteMemory(ctx, "/m/a.md") },
			"config.memory.delete", `{"path":"/m/a.md"}`, `null`, nil},
		{"SetAutoMemory", func(cl *Client) (any, error) { return nil, cl.SetAutoMemory(ctx, false) },
			"config.memory.setAuto", `{"on":false}`, `null`, nil},

		{"Skills", func(cl *Client) (any, error) { return cl.Skills(ctx) },
			"config.skills.list", `{}`,
			`{"user":[{"name":"a","title":"A","description":"d","enabled":true,"valid":true}],"synced":[{"name":"b","description":"e","enabled":false}]}`,
			Skills{User: []Skill{{"a", "A", "d", true, true}}, Synced: []SyncedSkill{{"b", "e", false}}}},
		{"ImportSkill", func(cl *Client) (any, error) { return cl.ImportSkill(ctx, "a.zip", []byte("PK"), true) },
			"config.skills.import", `{"fileName":"a.zip","replace":true,"zip":"UEs="}`,
			`{"name":"a","exists":false,"existingDescription":"","replaced":true,"enabled":true}`,
			SkillImport{Name: "a", Replaced: true, Enabled: true}},
		{"SetSkillEnabled", func(cl *Client) (any, error) { return nil, cl.SetSkillEnabled(ctx, Skill{Name: "a", Title: "A"}, true) },
			"config.skills.setEnabled", `{"enabled":true,"name":"a","title":"A"}`, `null`, nil},
		{"DeleteSkill", func(cl *Client) (any, error) { return nil, cl.DeleteSkill(ctx, "a") },
			"config.skills.delete", `{"name":"a"}`, `null`, nil},

		{"Agents", func(cl *Client) (any, error) { return cl.Agents(ctx) },
			"config.agents.list", `{}`,
			`[{"file":"rev.md","name":"rev","description":"审查","model":"","tools":null,"prompt":"p","extraKeys":["color"],"sha":"s1"}]`,
			[]Subagent{{File: "rev.md", Name: "rev", Description: "审查", Prompt: "p", ExtraKeys: []string{"color"}, SHA: "s1"}}},
		{"SaveAgent", func(cl *Client) (any, error) {
			return cl.SaveAgent(ctx, agent, SubagentDraft{Name: "rev2", Tools: []string{"Read"}, Prompt: "p"})
		}, "config.agents.save",
			`{"draft":{"name":"rev2","description":"","model":"","tools":["Read"],"prompt":"p"},` +
				`"original":{"file":"rev.md","name":"rev","description":"","model":"","tools":null,"prompt":"","extraKeys":null,"sha":"s1"}}`,
			`{"file":"rev2.md"}`, "rev2.md"},
		{"SaveAgent new", func(cl *Client) (any, error) { return cl.SaveAgent(ctx, nil, SubagentDraft{Name: "n"}) },
			"config.agents.save", `{"draft":{"name":"n","description":"","model":"","tools":null,"prompt":""},"original":null}`,
			`{"file":"n.md"}`, "n.md"},
		{"DeleteAgent", func(cl *Client) (any, error) { return nil, cl.DeleteAgent(ctx, "rev.md") },
			"config.agents.delete", `{"file":"rev.md"}`, `null`, nil},

		{"Permissions", func(cl *Client) (any, error) { return cl.Permissions(ctx, view) },
			"config.permissions.read", `{` + viewJSON + `}`, permJSON, perm},
		{"Permissions without server", func(cl *Client) (any, error) { return cl.Permissions(ctx, PermissionView{}) },
			"config.permissions.read", `{"cwd":"","server":"","tools":null}`, permJSON, perm},
		{"SetToolPermission", func(cl *Client) (any, error) { return cl.SetToolPermission(ctx, view, "mcp__gh", Deny) },
			"config.permissions.setTool", `{"behavior":"deny","cwd":"/w","server":"gh","tool":"mcp__gh","tools":["mcp__gh__issue"]}`, permJSON, perm},
		{"AddPermissionRule", func(cl *Client) (any, error) { return cl.AddPermissionRule(ctx, view, Rule{Allow, "Read"}) },
			"config.permissions.add", `{"behavior":"allow","cwd":"/w","rule":"Read","server":"gh","tools":["mcp__gh__issue"]}`, permJSON, perm},
		{"RemovePermissionRule", func(cl *Client) (any, error) { return cl.RemovePermissionRule(ctx, view, Rule{Ask, "Read"}) },
			"config.permissions.remove", `{"behavior":"ask","cwd":"/w","rule":"Read","server":"gh","tools":["mcp__gh__issue"]}`, permJSON, perm},
		{"ReplacePermissionRule", func(cl *Client) (any, error) {
			return cl.ReplacePermissionRule(ctx, view, Rule{Ask, "Read"}, Rule{Deny, "Read(/etc/**)"})
		}, "config.permissions.replace",
			`{"cwd":"/w","new":{"behavior":"deny","rule":"Read(/etc/**)"},"old":{"behavior":"ask","rule":"Read"},"server":"gh","tools":["mcp__gh__issue"]}`,
			permJSON, perm},
		{"SetPermissionGroup", func(cl *Client) (any, error) {
			return cl.SetPermissionGroup(ctx, PermissionView{Cwd: "/w"}, PermissionGroup{Server: "gh"}, false)
		}, "config.permissions.bulk", `{"cwd":"/w","enable":false,"group":{"server":"gh"},"server":"","tools":null}`, permJSON, perm},

		{"McpServers", func(cl *Client) (any, error) { return cl.McpServers(ctx, "/w") },
			"config.mcp.list", `{"cwd":"/w"}`,
			`[{"name":"gh","scope":"local","type":"http","url":"https://x","command":"","args":null,"envKeys":null,` +
				`"headerKeys":["X-Key"],"bearer":true,"authHeader":false,"oauth":false,"shadowedBy":"user"}]`,
			[]McpServer{{Name: "gh", Scope: "local", Type: "http", URL: "https://x", HeaderKeys: []string{"X-Key"}, Bearer: true, ShadowedBy: "user"}}},
		{"McpStatus", func(cl *Client) (any, error) { return cl.McpStatus(ctx, "/w") },
			"config.mcp.status", `{"cwd":"/w"}`,
			`{"servers":{"local/gh":{"state":"connected","issue":""}},"connectors":[{"name":"Gmail","state":{"state":"needs-auth","issue":"x"}}]}`,
			McpStatus{Servers: map[string]McpState{"local/gh": {"connected", ""}}, Connectors: []McpConnector{{"Gmail", McpState{"needs-auth", "x"}}}}},
		{"McpTools", func(cl *Client) (any, error) { return cl.McpTools(ctx, "/w", gh) },
			"config.mcp.tools", `{"cwd":"/w","name":"gh","scope":"local"}`, `[{"name":"issue","description":"d"}]`, []McpTool{{"issue", "d"}}},
		{"AddHTTPMcp", func(cl *Client) (any, error) {
			return nil, cl.AddHTTPMcp(ctx, HTTPMcp{Name: "gh", Type: "sse", URL: "https://x", Token: "t"})
		}, "config.mcp.addHttp", `{"name":"gh","token":"t","type":"sse","url":"https://x"}`, `null`, nil},
		{"AddStdioMcp", func(cl *Client) (any, error) {
			return nil, cl.AddStdioMcp(ctx, StdioMcp{Name: "fs", Command: "npx", Args: []string{"-y", "fs"}, Env: map[string]string{"K": "v"}})
		}, "config.mcp.addStdio", `{"args":["-y","fs"],"command":"npx","env":{"K":"v"},"name":"fs"}`, `null`, nil},
		{"RemoveMcp", func(cl *Client) (any, error) { return nil, cl.RemoveMcp(ctx, "/w", gh) },
			"config.mcp.remove", `{"cwd":"/w","name":"gh","scope":"local"}`, `null`, nil},
		{"SetMcpToken", func(cl *Client) (any, error) { return nil, cl.SetMcpToken(ctx, "/w", gh, "") },
			"config.mcp.setToken", `{"cwd":"/w","name":"gh","scope":"local","token":""}`, `null`, nil},
	})
}

// 写记忆文件撞上别人改过时是 ErrConflict；该有结果的方法核心给了 null 时是 ErrNoResult；核心的出错原样交回。
func TestConfigErrors(t *testing.T) {
	cl := New(&fakeCaller{reply: `{"conflict":true}`})
	if _, err := cl.WriteMemoryFile(ctx, "/m/a.md", "y", "s", false); !errors.Is(err, ErrConflict) {
		t.Errorf("conflict: %v", err)
	}
	if _, err := New(&fakeCaller{reply: `null`}).Defaults(ctx); !errors.Is(err, ErrNoResult) {
		t.Errorf("null: %v", err)
	}
	failing := &fakeCaller{err: errors.New("完全放行不能设成默认")}
	if _, err := New(failing).SetDefaultPermissionMode(ctx, "bypassPermissions"); err == nil || err.Error() != "完全放行不能设成默认" {
		t.Errorf("出错没有原样交回：%v", err)
	}
	big := &fakeCaller{}
	if _, err := New(big).ImportSkill(ctx, "a.zip", make([]byte, MaxSkillZip+1), false); err == nil || big.method != "" {
		t.Errorf("太大的 ZIP 不该发：%v %q", err, big.method)
	}
}

// 结果类型上的小工具。
func TestConfigHelpers(t *testing.T) {
	if defaults.EffortOf("claude-opus-5-5") != "max" || defaults.EffortOf("sonnet") != "" {
		t.Error("EffortOf")
	}
	if g := perm.BuiltinGroup(); !reflect.DeepEqual(g, PermissionGroup{Tools: []string{"Bash"}}) {
		t.Errorf("BuiltinGroup %+v", g)
	}
	if !perm.BuiltinBulk.Disable.Empty() || perm.BuiltinBulk.Enable.Empty() {
		t.Error("Empty")
	}
	if gh.Key() != "local/gh" {
		t.Error(gh.Key())
	}
}
