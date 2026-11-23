package locales

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"unicode"
)

// 界面上的字从文字表来（docs/i18n.md「怎么加一句话」）：这个测试扫桌面版界面一侧的非测试 Go 文件，含汉字的字符串字面量
// 只能是下面例外清单里的。新加的给人看的话进文字表；确实不该翻译的（日志、交给 Claude 或主机的、文件名）加一条例外，写明理由。

// scanned 是要扫的目录（相对 windows/，含子目录）。
var scanned = []string{"internal/ui", "internal/hub", "internal/chat", "internal/accounts", "internal/update", "cmd/zeta"}

// literalException 是一处允许含汉字的字面量：文件（相对 windows/）、字面量的值（解开引号后的）和理由。
// 同一个文件里同样的字面量出现几次都算这一条。
type literalException struct{ file, literal, why string }

const (
	whyLog     = "写进日志（log 的输出接到运行日志），日志不翻译"
	whyRunlog  = "运行日志里的一行，日志不翻译"
	whyInner   = "只进日志或只在程序有缺陷时出现的内部错误"
	whyBundle  = "诊断包里的文件名：维护者按固定的名字找，不随界面语言变"
	whyTestAid = "测试辅助包（uitest）里的失败信息，不进程序"
)

var literalExceptions = []literalException{
	{"internal/ui/drafts.go", "zeta: 读草稿：%v", whyLog},
	{"internal/ui/drafts.go", "zeta: 存草稿：%v", whyLog},
	{"internal/ui/models.go", "host: 没要到「%s」上 Claude Code 的模型清单：%v", whyLog},
	{"internal/hub/chat.go", "zeta: 关掉对话：%v", whyLog},
	{"internal/hub/chat.go", "zeta: 重连后重新打开对话：%v", whyLog},
	{"internal/hub/hub.go", "zeta: 设置没存上：%v", whyLog},
	{"internal/hub/pins.go", "zeta: 置顶没迁到「%s」：%v", whyLog},
	{"internal/hub/remote.go", "zeta: 换号前结束对话进程：%v", whyLog},
	{"internal/hub/remote.go", "zeta: 对话的设置没改成：%v", whyLog},
	{"internal/chat/conv.go", "chat: %s 的 conv.state 解不开：%v", whyLog},
	{"internal/chat/conv.go", "chat: %s 的 conv.items 解不开：%v", whyLog},
	{"internal/chat/conv.go", "chat: %s：%v，重新打开", whyLog},
	{"internal/chat/conv.go", "chat: %s 重新打开失败：%v", whyLog},
	{"internal/chat/subagent.go", "chat: %s 的 conv.subagent 解不开：%v", whyLog},
	{"internal/chat/subagent.go", "chat: %s 的子代理 %s：%v，重新看", whyLog},
	{"internal/chat/taskoutput.go", "chat: %s 的 conv.task_output 解不开：%v", whyLog},
	{"internal/chat/watch.go", "chat: %s 重新看 %v 没成：%v", whyLog},
	{"cmd/zeta/main.go", "zeta: 找不到放设置的目录：%v", whyLog},
	{"cmd/zeta/tray.go", "zeta: 托盘图标起不来，关窗口就退出：%v", whyLog},
	{"cmd/zeta/window.go", "zeta: 通知没发出去：%v", whyLog},
	{"cmd/zeta/main.go", " 启动", whyRunlog},
	{"cmd/zeta/main.go", "起不来：", whyRunlog},
	{"cmd/zeta/endsession.go", "系统要求结束（注销、关机或安装程序要换文件），退出", whyRunlog},
	{"internal/chat/items/items.go", "解码聊天条目：%w", whyInner + "（解不开的推送只记日志）"},
	{"internal/chat/items/items.go", "聊天条目的替换越界：从 %d 起去掉 %d 项，列表只有 %d 项", whyInner + "（对不上时记日志、整份重取）"},
	{"internal/ui/chatview/images.go", "没有取图片的办法", whyInner + "（调用方没给取这种图的函数）"},
	{"internal/ui/chatview/images.go", "不认识的图片种类 %q", whyInner + "（条目里的图片种类只有那几种）"},
	{"internal/accounts/host.go", "没有开着的 Claude Code 进程", whyInner + "（ErrNoProcess 由 Quota 认出来改读存着的数，不显示）"},
	{"internal/ui/diag/export.go", "环境.txt", whyBundle},
	{"internal/ui/diag/export.go", "/读取失败.txt", whyBundle},
	{"internal/ui/diag/export.go", "本机/", whyBundle},
	{"internal/ui/diag/export.go", "设备", whyBundle + "（设备名为空时的目录名）"},
	{"internal/ui/uitest/uitest.go", "%v 内没等到%s；界面上：%q", whyTestAid},
}

func TestNoHanLiteralsOutsideTheCatalog(t *testing.T) {
	root := filepath.Join("..", "..")
	allowed := map[[2]string]bool{}
	for _, e := range literalExceptions {
		if e.why == "" {
			t.Errorf("例外没写理由：%s %q", e.file, e.literal)
		}
		allowed[[2]string{e.file, e.literal}] = false
	}
	files := 0
	for _, dir := range scanned {
		err := filepath.WalkDir(filepath.Join(root, dir), func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
				return err
			}
			files++
			rel, _ := filepath.Rel(root, path)
			rel = filepath.ToSlash(rel)
			for _, lit := range hanLiterals(t, path) {
				key := [2]string{rel, lit.value}
				if _, ok := allowed[key]; ok {
					allowed[key] = true
					continue
				}
				t.Errorf("%s:%d：含汉字的字面量 %q。给人看的话放进文字表（docs/i18n.md），不该翻译的在 literals_test.go 的例外清单里加一条", rel, lit.line, lit.value)
			}
			return nil
		})
		if err != nil {
			t.Fatalf("扫 %s：%v", dir, err)
		}
	}
	if files == 0 {
		t.Fatal("一个 Go 文件也没扫到，目录对不上了")
	}
	for key, used := range allowed {
		if !used {
			t.Errorf("例外清单里的 %s %q 对不上任何字面量了，删掉这一条", key[0], key[1])
		}
	}
}

type literal struct {
	value string
	line  int
}

// hanLiterals 是文件里含汉字的字符串字面量（注释不算）。
func hanLiterals(t *testing.T, path string) []literal {
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, path, nil, parser.SkipObjectResolution)
	if err != nil {
		t.Fatalf("解析 %s：%v", path, err)
	}
	var out []literal
	ast.Inspect(f, func(n ast.Node) bool {
		lit, ok := n.(*ast.BasicLit)
		if !ok || lit.Kind != token.STRING {
			return true
		}
		value, err := strconv.Unquote(lit.Value)
		if err != nil {
			t.Fatalf("%s：解不开字面量 %s：%v", path, lit.Value, err)
		}
		if strings.ContainsFunc(value, func(r rune) bool { return unicode.Is(unicode.Han, r) }) {
			out = append(out, literal{value, fset.Position(lit.Pos()).Line})
		}
		return true
	})
	return out
}
