// Package claudecfg 是一台设备上 Claude Code 配置的薄客户端：调那边 zeta-helper serve 的 config.* 方法（逻辑在
// helper/claudecfg，方法和参数见 docs/core.md「Claude Code 配置」），把结果解成结构体给界面；另管客户端本地存的每台设备的
// 自动压缩、自动标题（device.go），和存在那台主机上的闲置超时（idle.go）。方法都是阻塞的网络调用，界面在后台 goroutine 里调。
package claudecfg

import (
	"context"
	"encoding/json"
	"errors"
)

// Caller 发一个 serve 请求、拿回 result。由 internal/host 的 serve 连接实现。
type Caller interface {
	// Call 发 method：params 编成 JSON 对象后，它的字段和 id、method 平级放进请求那一行。成功时返回应答的 result
	// （没有时是 nil）；应答 ok 为假时返回的错误就是它的 error（给人看的原因）。连不上、断了也返回错误。
	Call(ctx context.Context, method string, params any) (json.RawMessage, error)
}

// Client 是一台设备上的 Claude Code 配置。
type Client struct {
	c Caller
	// Cwd 是这台设备的默认工作目录（记忆的默认项目、MCP 的本地配置按它找），空是那边的家目录。
	Cwd string
}

// New 是经 c 调的那台设备。
func New(c Caller, cwd string) *Client { return &Client{c: c, Cwd: cwd} }

type obj = map[string]any

// call 发请求，把 result 解进 out（out 为 nil 时不要结果）。
func (cl *Client) call(ctx context.Context, method string, params obj, out any) error {
	if params == nil {
		params = obj{}
	}
	raw, err := cl.c.Call(ctx, method, params)
	if err != nil || out == nil {
		return err
	}
	if len(raw) == 0 || string(raw) == "null" {
		return errors.New("设备没有给结果")
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return errors.New("设备给的结果认不出：" + err.Error())
	}
	return nil
}

// ---------- 新对话默认 ----------

// Defaults 是一台设备上新对话的默认值（那台 ~/.claude/settings.json 里的，和终端里 /model、/effort 存为默认的同一份）。
type Defaults struct {
	Model          string        `json:"model"`          // 空是 Claude Code 内置的默认模型
	PermissionMode string        `json:"permissionMode"` // 空是逐项确认
	Efforts        []ModelEffort `json:"efforts"`        // 按模型存的思考强度
	Effort         string        `json:"effort"`         // 没单独设的模型用的那一档
	CompactWindow  int64         `json:"compactWindow"`  // 上下文窗口（token 数），0 是按模型的
}

// ModelEffort 是按模型存的一档思考强度。
type ModelEffort struct {
	Model string `json:"model"`
	Level string `json:"level"`
}

// EffortOf 是 model 单独设的那一档，没设时为空。
func (d Defaults) EffortOf(model string) string {
	for _, e := range d.Efforts {
		if e.Model == model {
			return e.Level
		}
	}
	return ""
}

// MinCompactWindow 是 Claude Code 肯认的最小上下文窗口。
const MinCompactWindow = 100_000

func (cl *Client) defaults(ctx context.Context, method string, params obj) (Defaults, error) {
	var d Defaults
	err := cl.call(ctx, method, params, &d)
	return d, err
}

// Defaults 读新对话的默认值。
func (cl *Client) Defaults(ctx context.Context) (Defaults, error) {
	return cl.defaults(ctx, "config.defaults.read", nil)
}

// SetDefaultModel 设默认模型；空是回到 Claude Code 内置的默认。
func (cl *Client) SetDefaultModel(ctx context.Context, model string) (Defaults, error) {
	return cl.defaults(ctx, "config.defaults.setModel", obj{"value": model})
}

// SetDefaultPermissionMode 设默认权限模式（完全放行不能设成默认）。
func (cl *Client) SetDefaultPermissionMode(ctx context.Context, mode string) (Defaults, error) {
	return cl.defaults(ctx, "config.defaults.setPermissionMode", obj{"value": mode})
}

// SetDefaultEffort 设默认思考强度：model 是完整模型 ID，空是没单独设的模型共用的那一档；level 空是删掉。
func (cl *Client) SetDefaultEffort(ctx context.Context, model, level string) (Defaults, error) {
	return cl.defaults(ctx, "config.defaults.setEffort", obj{"model": model, "value": level})
}

// SetCompactWindow 设上下文窗口（token 数）；0 是按模型的。
func (cl *Client) SetCompactWindow(ctx context.Context, tokens int64) (Defaults, error) {
	return cl.defaults(ctx, "config.defaults.setCompactWindow", obj{"value": tokens})
}

// ---------- 记忆 ----------

// ConfigFile 是一个配置文件在不在、多大。
type ConfigFile struct {
	Path   string `json:"path"`
	Exists bool   `json:"exists"`
	Size   int64  `json:"size"`
}

// MemoryProject 是记忆页上可选的一个项目。
type MemoryProject struct {
	Key     string `json:"key"`
	Label   string `json:"label"`
	Count   int    `json:"count"`
	Default bool   `json:"default"`
}

// MemoryEntry 是一条自动记忆。
type MemoryEntry struct {
	Path        string `json:"path"`
	FileName    string `json:"fileName"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Type        string `json:"type"`
	Size        int64  `json:"size"`
}

// Memory 是记忆页的内容。
type Memory struct {
	AutoMemory   bool            `json:"autoMemory"`
	ForcedOff    bool            `json:"forcedOff"`
	CustomDir    string          `json:"customDir"`
	Instructions ConfigFile      `json:"instructions"`
	Projects     []MemoryProject `json:"projects"`
	Selected     string          `json:"selected"`
	Dir          string          `json:"dir"`
	Index        *ConfigFile     `json:"index"`
	Files        []MemoryEntry   `json:"files"`
}

// Memory 读记忆页；project 空是默认工作目录所在的项目。
func (cl *Client) Memory(ctx context.Context, project string) (Memory, error) {
	var m Memory
	err := cl.call(ctx, "config.memory.read", obj{"cwd": cl.Cwd, "project": project}, &m)
	return m, err
}

// Document 是读到的一个文本文件；SHA 是读到时的版本。
type Document struct {
	Content string `json:"content"`
	SHA     string `json:"sha"`
	Exists  bool   `json:"exists"`
}

// MaxDocument 是记忆页上的文件能写多大。
const MaxDocument = 1 << 20

// ReadFile 读记忆页上的一个文件。
func (cl *Client) ReadFile(ctx context.Context, path string) (Document, error) {
	var d Document
	err := cl.call(ctx, "config.memory.readFile", obj{"path": path}, &d)
	return d, err
}

// ErrConflict：文件在编辑期间被别人改过了（多半是 Claude 在对话里改的），这次没写。
var ErrConflict = errors.New("文件在编辑期间被改过了")

// WriteFile 写记忆页上的一个文件，返回新版本。sha 是读到时的版本（原来不存在时为空）；force 时不比对直接覆盖。
func (cl *Client) WriteFile(ctx context.Context, path, content, sha string, force bool) (string, error) {
	var r struct {
		SHA      string `json:"sha"`
		Conflict bool   `json:"conflict"`
	}
	if err := cl.call(ctx, "config.memory.writeFile", obj{"path": path, "content": content, "sha": sha, "force": force}, &r); err != nil {
		return "", err
	}
	if r.Conflict {
		return "", ErrConflict
	}
	return r.SHA, nil
}

// DeleteMemory 删一条记忆，连同 MEMORY.md 里指向它的那一行。
func (cl *Client) DeleteMemory(ctx context.Context, path string) error {
	return cl.call(ctx, "config.memory.delete", obj{"path": path}, nil)
}

// SetAutoMemory 打开或关掉自动记忆。
func (cl *Client) SetAutoMemory(ctx context.Context, on bool) error {
	return cl.call(ctx, "config.memory.setAuto", obj{"on": on}, nil)
}

// ---------- Skills ----------

// Skill 是用户装的一个 Skill。
type Skill struct {
	Name        string `json:"name"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Enabled     bool   `json:"enabled"`
	Valid       bool   `json:"valid"`
}

// SyncedSkill 是账号同步的一个 Skill。
type SyncedSkill struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Enabled     bool   `json:"enabled"`
}

// Skills 是 Skills 页的内容。
type Skills struct {
	User   []Skill       `json:"user"`
	Synced []SyncedSkill `json:"synced"`
}

// SkillImport 是装 Skill 的结果；Exists 时什么都没装，要用户确认替换。
type SkillImport struct {
	Name                string `json:"name"`
	Exists              bool   `json:"exists"`
	ExistingDescription string `json:"existingDescription"`
	Replaced            bool   `json:"replaced"`
	Enabled             bool   `json:"enabled"`
}

// MaxSkillZip 是能导入的 ZIP 的上限：经 serve 的一行最长 64 MiB，base64 以后要放得下。
const MaxSkillZip = 45 << 20

// Skills 读 Skills 页。
func (cl *Client) Skills(ctx context.Context) (Skills, error) {
	var s Skills
	err := cl.call(ctx, "config.skills.list", nil, &s)
	return s, err
}

// ImportSkill 从 ZIP 装一个 Skill；replace 时替换同名的。
func (cl *Client) ImportSkill(ctx context.Context, fileName string, zip []byte, replace bool) (SkillImport, error) {
	if len(zip) > MaxSkillZip {
		return SkillImport{}, errors.New("文件超过 45 MB，不像是一个 Skill 包")
	}
	var r SkillImport
	err := cl.call(ctx, "config.skills.import", obj{"fileName": fileName, "zip": zip, "replace": replace}, &r)
	return r, err
}

// SetSkillEnabled 启用或停用一个 Skill（写 skillOverrides，和 Claude Code 里 /skills 一样）。
func (cl *Client) SetSkillEnabled(ctx context.Context, s Skill, enabled bool) error {
	return cl.call(ctx, "config.skills.setEnabled", obj{"name": s.Name, "title": s.Title, "enabled": enabled}, nil)
}

// DeleteSkill 删掉一个 Skill 的文件夹。
func (cl *Client) DeleteSkill(ctx context.Context, name string) error {
	return cl.call(ctx, "config.skills.delete", obj{"name": name}, nil)
}

// ---------- Subagent ----------

// Subagent 是一个 Subagent 文件。
type Subagent struct {
	File        string   `json:"file"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Model       string   `json:"model"`
	Tools       []string `json:"tools"` // nil 是全部工具
	Prompt      string   `json:"prompt"`
	ExtraKeys   []string `json:"extraKeys"`
	SHA         string   `json:"sha"`
}

// SubagentDraft 是编辑页上的内容。
type SubagentDraft struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Model       string   `json:"model"`
	Tools       []string `json:"tools"` // nil 是全部工具
	Prompt      string   `json:"prompt"`
}

// Agents 列出 ~/.claude/agents 下的 Subagent。
func (cl *Client) Agents(ctx context.Context) ([]Subagent, error) {
	var list []Subagent
	err := cl.call(ctx, "config.agents.list", nil, &list)
	return list, err
}

// SaveAgent 保存；original 为 nil 是新建。返回保存后的文件。
func (cl *Client) SaveAgent(ctx context.Context, original *Subagent, d SubagentDraft) (string, error) {
	var r struct {
		File string `json:"file"`
	}
	err := cl.call(ctx, "config.agents.save", obj{"original": original, "draft": d}, &r)
	return r.File, err
}

// DeleteAgent 删掉一个 Subagent 文件。
func (cl *Client) DeleteAgent(ctx context.Context, file string) error {
	return cl.call(ctx, "config.agents.delete", obj{"file": file}, nil)
}
