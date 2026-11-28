package conv

import (
	"encoding/json"
	"time"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/helper/usage"
)

// Deps 是对话用到的外部部件：核心进程里接上真的（托管进程经 zeta_host.sh、历史经 zeta-helper history），测试里换成假的。
type Deps struct {
	// Start 起一个 Claude Code 进程并接上（zeta_host.sh start）。
	// 事件在 Events 的回调里按到达顺序交回，回调在别的 goroutine 上。
	Start func(StartOptions, Events) (Link, error)
	// Attach 接上一个已在跑的托管进程（zeta_host.sh attach），从输出日志的第 from 行（从 1 起）读起
	Attach func(key string, from int, ev Events) (Link, error)
	// SessionFile 是会话号 session 的会话记录（几份时取最新的），没有时为空；可以是 nil
	SessionFile func(session string) string
	// SessionHarness 识别已有会话 session 属于哪个引擎（claude 或 agy）；可以是 nil
	SessionHarness func(session string) string
	// HostOutput 是托管标识 key 的进程的输出日志（zeta_host.sh 的 out.jsonl）；可以是 nil
	HostOutput func(key string) string
	// History 跑 zeta-helper history 的参数，返回它的输出（第一行 zeta_history，之后一行一条消息）
	History func(args ...string) ([]byte, error)
	// Subagent 跑 zeta-helper subagent 的参数（子代理查看页增量读记录），返回它的输出（第一行 zeta_subagent，之后一行一个事件）
	Subagent func(args ...string) ([]byte, error)
	// DefaultMode 是这台主机上 Claude Code 设置里的默认权限模式（permissions.defaultMode），不知道时为空
	DefaultMode func() string
	// DefaultModeFor 按引擎读取默认权限模式；不设时回落到 DefaultMode
	DefaultModeFor func(harness string) string
	// Windows 是这台主机上学到的各模型的上下文窗口（用量条用），几个对话共用；可以是 nil
	Windows *usage.Windows
	// Now 是现在的时刻，测试里换成假的；不给时用 time.Now
	Now func() time.Time
	// SessionTitles 是这台主机上工作目录 cwd 里已有会话的标题，分支起标题时避开重名；可以是 nil
	SessionTitles func(cwd string) []string
	// SessionChanged：对话拿到了（或换了）会话号；由 Manager 填
	SessionChanged func(c *Conversation, before, after string)
	// Desk 在这台主机的「Zeta」tmux 会话里给会话开窗口（zeta-helper desk-open，已经有就不动）；可以是 nil
	Desk func(session, cwd string)
	// ActivityChanged：对话的会话号或忙闲变了（conv.activity）；由 Manager 填
	ActivityChanged func(c *Conversation, session string, a Activity)
	// Occupant 查 Zeta 之外有没有进程占着会话 session（zeta-helper occupant）；可以是 nil，那时当没人占着
	Occupant func(session string) sessionproto.Occupancy
	// Handoff 把会话 session 从别处接过来（zeta-helper handoff），要等那边退出；可以是 nil，那时当已交出来
	Handoff func(session string) sessionproto.Handoff
	// EndHosted 结束本包托管、正跑着会话 session 的进程（zeta_host.sh stop），不等它退出，返回有没有这样的进程。
	// 对话没接着进程时结束会话用（conv.end）：没人拿着闲够了核心会放开进程、交班时新核心不接闲着的，进程却要等看门狗按闲置超时才收。
	// 可以是 nil，那时只结束对话接着的进程
	EndHosted func(session string) bool
	// Quota 收下 Claude Code 给的这台主机的订阅额度（核心里接到额度存储，quota.get 读的那份）；可以是 nil：
	// 那样输出里的 rate_limit_event 不管，起进程时也不问额度
	Quota Quota
	// QuotaFor 按引擎返回订阅额度存储；不设时回落到 Quota
	QuotaFor func(harness string) Quota
	// QueryAgyQuota 在 agy 对话尚未起子进程时直接查询订阅额度；可以是 nil
	QueryAgyQuota func() (json.RawMessage, error)
	// KeepMode 把用户在这个对话里的权限模式记到托管进程 key 上（托管目录的 user_mode），核心重启后接回时恢复；
	// 没写成（比如托管脚本还没建好目录）时出错，下次推送时再写。可以是 nil
	KeepMode func(key, mode string) error
	// ClientDevice 是客户端 client 是哪台设备（按它的 core.client 定），没报过时是零值；可以是 nil
	ClientDevice func(client int) Device
	// ClientLang 是客户端 client 界面的语言（core.client 报的 lang，没报过时为空）：会存下来的字（分支标题、新对话的临时标题）
	// 按发起操作的那个客户端的语言写；可以是 nil，那时用源语言
	ClientLang func(client int) string
	// KeepDevice 把设备记到托管进程 key 上（托管目录的 client），另一个包查别处占用时说「在哪」；
	// 没写成（托管脚本还没建好目录）时出错，下次推送时再写。可以是 nil
	KeepDevice func(key string, d Device) error
	// Rename 给会话记录 file 改名（追加一行 custom-title，同 zeta-helper rename），返回实际写下的标题；可以是 nil
	Rename func(file, title string) (string, error)
	// SessionImage 取回会话记录 file 里位置 ref 的一张图（同 sessions.image），重新生成时原样重发用；可以是 nil，那时会话记录里的图发不了
	SessionImage func(file, ref string) (Image, error)
	// Commands 记着这台主机上各工作目录最近一次拿到的斜杠命令，还没起进程时打开对话也给得出来；可以是 nil
	Commands *CommandStore
}

// Quota 是订阅额度的去处，几个对话共用一份。方法在各对话自己的 goroutine 上调用，几个对话可能同时调。
type Quota interface {
	// RateLimit 收下输出里一条 rate_limit_event 的 rate_limit_info（原样）。
	RateLimit(info json.RawMessage)
	// ClaimQuery：刚起或刚接上进程，要不要顺便问一次 get_usage；返回 true 时算作问过了。
	// 由它定多久问一次（Claude Code 取额度的接口会限流），对话只管问。
	ClaimQuery() bool
	// Usage 收下 get_usage 的应答（control_response 的 response.response，原样）。
	Usage(response json.RawMessage)
	// Subscribed 是这台主机在用订阅账号；在用中转（claude-switch 的非账号目标）时为假，那时没有订阅额度，conv.quota 不问。
	Subscribed() bool
}

// StartOptions 是起进程用的：托管标识、工作目录、标题、claude 的参数，和只给这个进程的环境变量。
type StartOptions struct {
	Key     string
	Cwd     string
	Title   string
	Harness string
	Args    []string
	Env     map[string]string
	// OnJoin 是接上了本包已在跑这个会话的进程时先写的几行（排在别的消息前面），把那个进程调成这边要的样子
	OnJoin [][]byte
}

// Link 是和一个托管进程的连接。
type Link interface {
	// Send 写一行进 Claude Code 的标准输入；接上之前写的存着，接上后按序写
	Send(line []byte)
	// Stop 结束进程（等于终端里 /exit）；Leave 只关掉这条连接、不动进程：没有别的一方接着、又闲着时，
	// 看门狗过这台主机设的闲置时长后收掉它（docs/core.md「主机」的 system.idle）。都立即返回，之后不再回调
	Stop()
	Leave()
	// Key 是托管标识：接上本包现成的进程时换成那个进程的
	Key() string
}

// Events 是托管连接交回的事件。
type Events struct {
	Attached func(Attached)    // 托管脚本接上了：第一个事件，之后才有输出行
	Line     func(line []byte) // Claude Code 输出的一行
	Exit     func(Exit)        // 连接到此为止（只来一次）
}

// Attached 是托管脚本接上时报的（zeta_attached）：接上的托管标识、接下来第一行在输出日志里的行号（从 1 起），
// 和是不是没起新进程、接上了本包已在跑这个会话的进程。
type Attached struct {
	Key    string
	From   int
	Joined bool
}

// Exit 是托管进程的结局：退出码、标准错误输出的最后一段，和会话去了别处的原因（released、held_elsewhere）。
type Exit struct {
	Code   int
	Stderr string
	Reason string
}

const (
	reasonReleased      = "released"       // 终端里接回去了，zeta-helper 让出会话时结束了这边的进程
	reasonHeldElsewhere = "held_elsewhere" // 要接着的会话正被 Zeta 之外的 Claude Code 占着，托管脚本没起进程
)

// now 是现在的时刻（毫秒）。
func (d Deps) now() int64 {
	if d.Now != nil {
		return d.Now().UnixMilli()
	}
	return time.Now().UnixMilli()
}
