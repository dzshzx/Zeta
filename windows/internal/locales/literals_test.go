package locales

import (
	"testing"

	"github.com/dzshzx/Zeta/hub/locales/literalcheck"
)

// 界面上的字从文字表来（docs/i18n.md「怎么加一句话」）：扫桌面版界面一侧的非测试 Go 文件（中枢一侧由 hub/locales 的同名测试扫），
// 含汉字的字符串字面量只能是下面例外清单里的。新加的给人看的话进文字表；确实不该翻译的（日志、交给 Claude 或主机的、文件名）加一条例外，写明理由。

// scanned 是要扫的目录（相对 windows/，含子目录）。
var scanned = []string{"internal/ui", "internal/update", "cmd/zeta"}

const (
	whyRunlog  = "运行日志里的一行，日志不翻译"
	whyBundle  = "诊断包里的文件名：维护者按固定的名字找，不随界面语言变"
	whyTestAid = "测试辅助包（uitest）里的失败信息，不进程序"
)

var literalExceptions = []literalcheck.Exception{
	{"internal/ui/drafts.go", "zeta: 读草稿：%v", literalcheck.WhyLog},
	{"internal/ui/drafts.go", "zeta: 存草稿：%v", literalcheck.WhyLog},
	{"cmd/zeta/main.go", "zeta: 找不到放设置的目录：%v", literalcheck.WhyLog},
	{"cmd/zeta/tray.go", "zeta: 托盘图标起不来，关窗口就退出：%v", literalcheck.WhyLog},
	{"cmd/zeta/window.go", "zeta: 通知没发出去：%v", literalcheck.WhyLog},
	{"cmd/zeta/main.go", " 启动", whyRunlog},
	{"cmd/zeta/main.go", "起不来：", whyRunlog},
	{"cmd/zeta/endsession.go", "系统要求结束（注销、关机或安装程序要换文件），退出", whyRunlog},
	{"internal/ui/chatview/images.go", "没有取图片的办法", literalcheck.WhyInner + "（调用方没给取这种图的函数）"},
	{"internal/ui/chatview/images.go", "不认识的图片种类 %q", literalcheck.WhyInner + "（条目里的图片种类只有那几种）"},
	{"internal/ui/diag/export.go", "环境.txt", whyBundle},
	{"internal/ui/diag/export.go", "/读取失败.txt", whyBundle},
	{"internal/ui/diag/export.go", "本机/", whyBundle},
	{"internal/ui/diag/export.go", "设备", whyBundle + "（设备名为空时的目录名）"},
	{"internal/ui/uitest/uitest.go", "%v 内没等到%s；界面上：%q", whyTestAid},
}

func TestNoHanLiteralsOutsideTheCatalog(t *testing.T) {
	literalcheck.Check(t, "../..", scanned, literalExceptions)
}
