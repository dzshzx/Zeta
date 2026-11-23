package about

import (
	"regexp"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// repo 是 Zeta 的源码仓库（同手机版 ZetaInfo.REPO）。
const repo = "https://github.com/dzshzx/Zeta"

var (
	releaseName = regexp.MustCompile(`^\d+\.\d+\.\d+$`)
	devCommit   = regexp.MustCompile(`\+g([0-9a-f]{7,40})$`)
)

// sourceRef 是这个版本的源码在 GitHub 上的哪个 tag 或提交（同手机版 ZetaInfo.sourceRef）：正式版 0.7.6 是 tag v0.7.6，
// 测试版 0.7.6-dev.3+gabc1234 是版本名里的提交号，认不出时是空。
func sourceRef(versionName string) string {
	if releaseName.MatchString(versionName) {
		return "v" + versionName
	}
	if m := devCommit.FindStringSubmatch(versionName); m != nil {
		return m[1]
	}
	return ""
}

// sourceURL 是这个版本的源码：sourceRef 那个 tag 或提交的目录，认不出时是仓库首页。
func sourceURL(versionName string) string {
	if ref := sourceRef(versionName); ref != "" {
		return repo + "/tree/" + ref
	}
	return repo
}

// credit 是鸣谢里的一项：名字、它在桌面版里是什么（带许可证），都按界面当时的语言取；点了打开的主页。
type credit struct {
	name, summary func() string
	url           string
	module        string // windows/go.mod 里直接依赖的模块（Go 依赖才有），TestCreditsMatchGoMod 拿它和 go.mod 对
}

// credits 是桌面版参照和依靠的项目（同手机版的「鸣谢」，只列桌面版用到的）：
// Eta 是整个项目的参考来源；mygo、x/crypto、x/image、wazero 是 windows/go.mod 直接依赖的模块；
// 图标的路径数据有一部分照搬 COUI 2 的 coui-icons（见 widget/icons.go）；公式用 RaTeX 的 wasm 和 KaTeX 字体（见 mathtex）。
var credits = []credit{
	{fixed("Eta"), locales.AboutCreditEta, "https://github.com/Mangi-11/Eta", ""},
	{fixed("Claude Code"), locales.AboutCreditClaudeCode, "https://github.com/anthropics/claude-code", ""},
	{fixed("mygo"), locales.AboutCreditMygo, "https://github.com/egoist/mygo", "github.com/egoist/mygo"},
	{fixed("Go x/crypto"), locales.AboutCreditCrypto, "https://github.com/golang/crypto", "golang.org/x/crypto"},
	{fixed("Go x/image"), locales.AboutCreditImage, "https://github.com/golang/image", "golang.org/x/image"},
	{fixed("COUI 2"), locales.AboutCreditCoui, "https://github.com/suqi8/coui", ""},
	{fixed("RaTeX"), locales.AboutCreditRatex, "https://github.com/erweixin/RaTeX", ""},
	{locales.AboutCreditKatexFonts, locales.AboutCreditKatex, "https://github.com/KaTeX/KaTeX", ""},
	{fixed("wazero"), locales.AboutCreditWazero, "https://github.com/tetratelabs/wazero", "github.com/tetratelabs/wazero"},
}

// fixed 是不分语言的名字（项目名）。
func fixed(s string) func() string { return func() string { return s } }
