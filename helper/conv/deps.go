package conv

import (
	"time"

	"github.com/dzshzx/Zeta/helper/usage"
)

// Deps 是对话用到的外部部件：核心进程里接上真的（托管进程经 zeta_host.sh、历史经 zeta-helper history），测试里换成假的。
type Deps struct {
	// Start 起一个 Claude Code 进程并接上（zeta_host.sh start）。
	// 事件在 Events 的回调里按到达顺序交回，回调在别的 goroutine 上。
	Start func(StartOptions, Events) (Link, error)
	// Attach 接上一个已在跑的托管进程（zeta_host.sh attach），从输出日志的第 from 行（从 1 起）读起
	Attach func(key string, from int, ev Events) (Link, error)
	// History 跑 zeta-helper history 的参数，返回它的输出（第一行 zeta_history，之后一行一条消息）
	History func(args ...string) ([]byte, error)
	// DefaultMode 是这台主机上 Claude Code 设置里的默认权限模式（permissions.defaultMode），不知道时为空
	DefaultMode func() string
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
}

// StartOptions 是起进程用的：托管标识、工作目录、标题、claude 的参数，和只给这个进程的环境变量。
type StartOptions struct {
	Key   string
	Cwd   string
	Title string
	Args  []string
	Env   map[string]string
	// OnJoin 是接上了本包已在跑这个会话的进程时先写的几行（排在别的消息前面），把那个进程调成这边要的样子
	OnJoin [][]byte
}

// Link 是和一个托管进程的连接。
type Link interface {
	// Send 写一行进 Claude Code 的标准输入；接上之前写的存着，接上后按序写
	Send(line []byte)
	// Stop 结束进程（等于终端里 /exit），Detach 只断开自己；都立即返回，之后不再回调
	Stop()
	Detach()
	// Key 是托管标识：接上本包现成的进程时换成那个进程的
	Key() string
}

// Events 是托管连接交回的事件。
type Events struct {
	Line func(line []byte) // Claude Code 输出的一行
	Exit func(Exit)        // 连接到此为止（只来一次）
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
