package coreapi

import (
	"context"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 会话列表的订阅和侧栏对会话的操作（sessions.*），见 docs/core.md「协议」里的会话列表与「会话」。出错时认得出的 code
// （sessionproto.CodeNoSession、CodeActive）在错误里，客户端照它连接的写法取（hostlink.ReplyCode）。

// WatchSessions 订阅会话列表（sessions.watch）：之后推来的 zeta_sessions 由客户端自己解；已经订阅着时再要一份整份的。
func (cl *Client) WatchSessions(ctx context.Context) error {
	return call(ctx, cl.c, "sessions.watch", nil)
}

// UnwatchSessions 退订会话列表（sessions.unwatch），同一条连接上可以接着做别的。
func (cl *Client) UnwatchSessions(ctx context.Context) error {
	return call(ctx, cl.c, "sessions.unwatch", nil)
}

// SessionSummary 是会话列表里的一项：sessions.lookup 的每一项，和 zeta_sessions 推送里 upsert 的一项相同。
type SessionSummary struct {
	ID   string `json:"id"`
	File string `json:"file"`
	// Cwd 照会话记录原样：一般是字符串，也可能是 null 或别的值
	Cwd     any    `json:"cwd"`
	Title   string `json:"title"`
	Preview string `json:"preview"`
	// Updated 是记录的修改时刻（Unix 毫秒）
	Updated int64 `json:"updated"`
	// External：正被 Zeta 之外的 Claude Code 进程占着（比如终端里开着）
	External bool `json:"external"`
	Archived bool `json:"archived"`
	Pinned   bool `json:"pinned"`
	// Live：有还活着的 Claude Code 进程登记着它；为假是已结束的会话
	Live bool `json:"live"`
}

// RenameSession 给会话改名（sessions.rename），返回实际写下的标题（sessionproto.CleanTitle 清理过的）。
func (cl *Client) RenameSession(ctx context.Context, session, title string) (string, error) {
	r, err := get[struct {
		Title string `json:"title"`
	}](ctx, cl.c, "sessions.rename", params{"session": session, "title": title})
	return r.Title, err
}

// ArchiveSession 归档（archived 为真）或取消归档会话（sessions.archive）。
func (cl *Client) ArchiveSession(ctx context.Context, session string, archived bool) error {
	return call(ctx, cl.c, "sessions.archive", params{"session": session, "archived": archived})
}

// PinSession 置顶（pinned 为真）或取消置顶会话（sessions.pin）。
func (cl *Client) PinSession(ctx context.Context, session string, pinned bool) error {
	return call(ctx, cl.c, "sessions.pin", params{"session": session, "pinned": pinned})
}

// DeletedRecord 是删掉的一份会话记录：Dir 是它的同名目录删没删掉。
type DeletedRecord struct {
	File string `json:"file"`
	Dir  bool   `json:"dir"`
}

// Deletion 是 sessions.delete 的结果：删掉的各份记录（已经没有记录时为空），和关掉的 tmux 窗口数。
type Deletion struct {
	Deleted []DeletedRecord `json:"deleted"`
	Closed  int             `json:"closed"`
}

// DeleteSession 删掉会话（sessions.delete）。会话正在进行时不删，答出错，code 为 sessionproto.CodeActive。
func (cl *Client) DeleteSession(ctx context.Context, session string) (Deletion, error) {
	return get[Deletion](ctx, cl.c, "sessions.delete", params{"session": session})
}

// Export 是 sessions.export 的结果：合成的一份和建议的文件名，另有导出成的、没导出成的会话号和各自的导出（按给的先后）。
type Export struct {
	Title    string                    `json:"title"`
	Markdown string                    `json:"markdown"`
	FileName string                    `json:"file_name"`
	Exported []string                  `json:"exported"`
	Failed   []string                  `json:"failed"`
	Parts    []sessionproto.ExportPart `json:"parts"`
}

// ExportSessions 把同一台主机上的几个会话导出成 Markdown（sessions.export，总是带 sessions）。utcOffset 是客户端所在时区
// 比 UTC 快多少分钟，文件名里的时刻照它。几台设备的怎么合成见 docs/core.md「会话」。
func (cl *Client) ExportSessions(ctx context.Context, sessions []string, utcOffset int) (Export, error) {
	return get[Export](ctx, cl.c, "sessions.export", params{"sessions": sessions, "utc_offset": utcOffset})
}

// LookupSessions 按会话号查列表项（sessions.lookup），按请求的先后，找不到的不在里面。
func (cl *Client) LookupSessions(ctx context.Context, sessions []string) ([]SessionSummary, error) {
	return get[[]SessionSummary](ctx, cl.c, "sessions.lookup", params{"sessions": sessions})
}

// SearchSummary 是一次搜索找完的汇总；Complete 为假是到了时限、个数被截断，或被取消。
type SearchSummary struct {
	Scanned  int  `json:"scanned"`
	Total    int  `json:"total"`
	Matched  int  `json:"matched"`
	Complete bool `json:"complete"`
}

// SearchSessions 在最近的会话里找 query（sessions.search），找完返回汇总。命中只经推送 sessions.match 给，带着 search
// （客户端给这次搜索起的名字，任意能写成 JSON 的值），由客户端自己解；同一条连接再搜时核心取消上一个。
func (cl *Client) SearchSessions(ctx context.Context, query string, search any) (SearchSummary, error) {
	return get[SearchSummary](ctx, cl.c, "sessions.search", params{"query": query, "search": search})
}

// Image 是取回的一张图片（sessions.image、images.file）：Data 是解开 base64 后的内容。
type Image struct {
	MediaType string `json:"media_type"`
	Data      []byte `json:"data"`
}

// SessionImage 取会话记录 file 里 ref 处的图片（sessions.image）。记录已经不在时 code 为 sessionproto.CodeNoSession。
func (cl *Client) SessionImage(ctx context.Context, file, ref string) (Image, error) {
	return get[Image](ctx, cl.c, "sessions.image", params{"file": file, "ref": ref})
}

// SessionOccupant 是 Zeta 之外占着会话的进程的样子（sessions.occupant）。
func (cl *Client) SessionOccupant(ctx context.Context, session string) (sessionproto.Occupancy, error) {
	return get[sessionproto.Occupancy](ctx, cl.c, "sessions.occupant", params{"session": session})
}

// HandOffSession 把会话从别处接过来（sessions.handoff），最多十几秒。
func (cl *Client) HandOffSession(ctx context.Context, session string) (sessionproto.Handoff, error) {
	return get[sessionproto.Handoff](ctx, cl.c, "sessions.handoff", params{"session": session})
}

// EndElsewhere 结束 Zeta 之外占着会话的 Claude Code 进程（sessions.end_elsewhere），不管那边闲不闲，返回结束了几个。
func (cl *Client) EndElsewhere(ctx context.Context, session string) (int, error) {
	r, err := get[struct {
		Ended int `json:"ended"`
	}](ctx, cl.c, "sessions.end_elsewhere", params{"session": session})
	return r.Ended, err
}

// OpenDeskWindow 在 tmux 会话 zeta 里给会话开窗口（sessions.desk_open），已经有就不动；返回 tmux 的窗口号，主机上没有 tmux 时为 nil。
func (cl *Client) OpenDeskWindow(ctx context.Context, session string) (*string, error) {
	r, err := get[struct {
		Window *string `json:"window"`
	}](ctx, cl.c, "sessions.desk_open", params{"session": session})
	return r.Window, err
}

// CloseDeskWindow 关掉会话在 tmux 会话 zeta 里的窗口（sessions.desk_close），返回关掉了几个。
func (cl *Client) CloseDeskWindow(ctx context.Context, session string) (int, error) {
	r, err := get[struct {
		Closed int `json:"closed"`
	}](ctx, cl.c, "sessions.desk_close", params{"session": session})
	return r.Closed, err
}
