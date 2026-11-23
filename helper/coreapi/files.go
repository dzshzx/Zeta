package coreapi

import (
	"context"
	"encoding/json"
)

// 设置里的存储页（storage.*）、文件浏览（files.*）和按路径取图片（images.file），见 docs/core.md「存储与文件」。
// keep 是客户端认为正在进行、要保护的会话号，可以为空。

// StorageKind 是存储页的一类：一共占多少、几个文件，能清掉多少、几项，按来源的小计。
type StorageKind struct {
	Key      string `json:"key"`
	Bytes    int64  `json:"bytes"`
	Files    int64  `json:"files"`
	Freeable int64  `json:"freeable"`
	Items    int    `json:"items"`
	// Busy 是正在运行、这一类先不清的工具，没有时为 nil
	Busy  *string       `json:"busy"`
	Parts []StoragePart `json:"parts"`
	// Error：这一类没统计成（别的字段都是 0）
	Error bool `json:"error"`
}

// StoragePart 是一类里按来源的小计。
type StoragePart struct {
	Name  string `json:"name"`
	Bytes int64  `json:"bytes"`
	Items int64  `json:"items"`
}

// StorageProject 是 ~/.claude/projects 下的一个项目目录：占多少、几个文件、几个会话记录，对应的工作目录（看不出来时是空串）。
type StorageProject struct {
	Dir      string `json:"dir"`
	Bytes    int64  `json:"bytes"`
	Files    int64  `json:"files"`
	Sessions int    `json:"sessions"`
	Cwd      string `json:"cwd"`
}

// StorageReport 是 storage.scan 的结果；CleanupPeriodDays 是 settings.json 的 cleanupPeriodDays，没设时为 nil。
type StorageReport struct {
	Kinds             []StorageKind    `json:"kinds"`
	Projects          []StorageProject `json:"projects"`
	CleanupPeriodDays *json.Number     `json:"cleanupPeriodDays"`
}

// ScanStorage 统计存储页的各类和各项目（storage.scan）。慢，几秒到几分钟。
func (cl *Client) ScanStorage(ctx context.Context, keep []string) (StorageReport, error) {
	return get[StorageReport](ctx, cl.c, "storage.scan", params{"keep": keep})
}

// SessionUsage 是项目里的一个会话：记录加同名目录一共占多少，修改时刻（Unix 毫秒），Live 是正在进行（这时不能删）。
type SessionUsage struct {
	ID      string `json:"id"`
	File    string `json:"file"`
	Bytes   int64  `json:"bytes"`
	Updated int64  `json:"updated"`
	Live    bool   `json:"live"`
}

// ProjectUsage 是 storage.sessions 的结果：项目里各会话的占用，Rest 是不属于任何会话的那部分。
type ProjectUsage struct {
	Sessions []SessionUsage `json:"sessions"`
	Rest     int64          `json:"rest"`
}

// StorageSessions 是项目 project（StorageProject.Dir）里每个会话占多少（storage.sessions）。
func (cl *Client) StorageSessions(ctx context.Context, project string, keep []string) (ProjectUsage, error) {
	return get[ProjectUsage](ctx, cl.c, "storage.sessions", params{"project": project, "keep": keep})
}

// Cleaned 是 storage.clean 的结果：腾出多少字节、删了几项、几项核对不过没删；Busy 不为 nil 时那个工具正在运行，整类没清。
type Cleaned struct {
	Key     string  `json:"key"`
	Freed   int64   `json:"freed"`
	Removed int64   `json:"removed"`
	Failed  int64   `json:"failed"`
	Busy    *string `json:"busy"`
}

// CleanStorage 清掉 kind（StorageKind.Key，apt 除外）这类里能安全删的（storage.clean）。
func (cl *Client) CleanStorage(ctx context.Context, kind string, keep []string) (Cleaned, error) {
	return get[Cleaned](ctx, cl.c, "storage.clean", params{"kind": kind, "keep": keep})
}

// files.ls、files.read 读不了时错误带的 code。
const (
	FileMissing = "missing"  // 不存在
	FileNotDir  = "not_dir"  // 不是目录
	FileNotFile = "not_file" // 不是普通文件
	FileDenied  = "denied"   // 没有权限
	FileError   = "error"    // 别的原因
)

// Listing 是 files.ls 的结果：Path 是规整过的路径，Total 是一共几项，Entries 只有前 2000 项（Truncated）。
type Listing struct {
	Path      string     `json:"path"`
	Total     int        `json:"total"`
	Truncated bool       `json:"truncated"`
	Entries   []DirEntry `json:"entries"`
}

// DirEntry 是目录里的一项：Kind 是 dir、file、link、other；Dir 为真的能点进去（指向目录的符号链接也算）；Size 是普通文件的大小；
// Mtime 是修改时间（秒）；Target、Broken 只有符号链接有。
type DirEntry struct {
	Name   string `json:"name"`
	Dir    bool   `json:"dir"`
	Kind   string `json:"kind"`
	Size   int64  `json:"size"`
	Mtime  int64  `json:"mtime"`
	Target string `json:"target"`
	Broken bool   `json:"broken"`
}

// ListDir 列主机上的目录 path（绝对路径，files.ls）：目录在前、再按名字排。
func (cl *Client) ListDir(ctx context.Context, path string) (Listing, error) {
	return get[Listing](ctx, cl.c, "files.ls", params{"path": path})
}

// FilePreview 是 files.read 的结果：Size 是文件大小；看着像二进制时 Binary 为真、没有 Text；Truncated 是只读了开头。
type FilePreview struct {
	Size      int64  `json:"size"`
	Truncated bool   `json:"truncated"`
	Text      string `json:"text"`
	Binary    bool   `json:"binary"`
}

// ReadFile 读主机上文本文件 path（绝对路径）的开头（files.read）：读 bytes 字节，0 是核心的默认（256 KiB），最多 4 MiB。
func (cl *Client) ReadFile(ctx context.Context, path string, bytes int64) (FilePreview, error) {
	p := params{"path": path}
	if bytes > 0 {
		p["bytes"] = bytes
	}
	return get[FilePreview](ctx, cl.c, "files.read", p)
}

// FileImage 取主机上的一个图片文件（images.file）；path 以 ~ 开头的从家目录算。
func (cl *Client) FileImage(ctx context.Context, path string) (Image, error) {
	return get[Image](ctx, cl.c, "images.file", params{"path": path})
}
