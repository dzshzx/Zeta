package locales

import (
	"testing"

	"github.com/dzshzx/Zeta/hub/locales/literalcheck"
)

// 界面上的字从文字表来（docs/i18n.md「怎么加一句话」）：扫客户端共用的中枢一侧（桌面版和 iOS 版都用）的非测试 Go 文件，
// 含汉字的字符串字面量只能是下面例外清单里的。新加的给人看的话进文字表；确实不该翻译的（日志、交给 Claude 或主机的）加一条例外，写明理由。

// scanned 是要扫的目录（相对 hub/，含子目录；"." 是中枢的根包本身）。
var scanned = []string{".", "chat", "accounts", "diagbundle", "mobile", "cmd/zeta-ios"}

const (
	whyLog    = "写进日志（log 的输出接到运行日志），日志不翻译"
	whyInner  = "只进日志或只在程序有缺陷时出现的内部错误"
	whyBundle = "诊断包里的文件名：维护者按固定的名字找，不随界面语言变"
)

var literalExceptions = []literalcheck.Exception{
	{"chat.go", "zeta: 关掉对话：%v", whyLog},
	{"chat.go", "zeta: 重连后重新打开对话：%v", whyLog},
	{"hub.go", "zeta: 设置没存上：%v", whyLog},
	{"pins.go", "zeta: 置顶没迁到「%s」：%v", whyLog},
	{"remote.go", "zeta: 换号前结束对话进程：%v", whyLog},
	{"remote.go", "zeta: 对话的设置没改成：%v", whyLog},
	{"chat/conv.go", "chat: %s 的 conv.state 解不开：%v", whyLog},
	{"chat/conv.go", "chat: %s 的 conv.items 解不开：%v", whyLog},
	{"chat/conv.go", "chat: %s：%v，重新打开", whyLog},
	{"chat/conv.go", "chat: %s 重新打开失败：%v", whyLog},
	{"chat/subagent.go", "chat: %s 的 conv.subagent 解不开：%v", whyLog},
	{"chat/subagent.go", "chat: %s 的子代理 %s：%v，重新看", whyLog},
	{"chat/taskoutput.go", "chat: %s 的 conv.task_output 解不开：%v", whyLog},
	{"chat/watch.go", "chat: %s 重新看 %v 没成：%v", whyLog},
	{"chat/items/items.go", "解码聊天条目：%w", whyInner + "（解不开的推送只记日志）"},
	{"chat/items/items.go", "聊天条目的替换越界：从 %d 起去掉 %d 项，列表只有 %d 项", whyInner + "（对不上时记日志、整份重取）"},
	{"accounts/host.go", "没有开着的 Claude Code 进程", whyInner + "（ErrNoProcess 由 Quota 认出来改读存着的数，不显示）"},
	{"diagbundle/bundle.go", "环境.txt", whyBundle},
	{"diagbundle/bundle.go", "/读取失败.txt", whyBundle},
	{"diagbundle/bundle.go", "本机/", whyBundle},
	{"diagbundle/bundle.go", "设备", whyBundle + "（设备名为空时的目录名）"},
}

func TestNoHanLiteralsOutsideTheCatalog(t *testing.T) {
	literalcheck.Check(t, "..", scanned, literalExceptions)
}
