package about

import (
	"os"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/windows/internal/update"
)

// 源码那一行：正式版是 tag，测试版是版本名里的提交号，认不出时是仓库首页（同手机版 ZetaInfo.sourceRef）。
func TestSourceRef(t *testing.T) {
	for _, c := range []struct{ name, ref, url string }{
		{"0.7.6", "v0.7.6", repo + "/tree/v0.7.6"},
		{"0.7.7-dev.3+gabc1234", "abc1234", repo + "/tree/abc1234"},
		{"未知", "", repo},
		{"0.7.6-rc1", "", repo},
	} {
		if got := sourceRef(c.name); got != c.ref {
			t.Errorf("sourceRef(%q) = %q，要 %q", c.name, got, c.ref)
		}
		if got := sourceURL(c.name); got != c.url {
			t.Errorf("sourceURL(%q) = %q，要 %q", c.name, got, c.url)
		}
	}
}

// 点「源码」用浏览器打开这个版本的源码；点鸣谢里的一项打开它的主页。
func TestSourceAndCreditsOpenLinks(t *testing.T) {
	_, _, _, tt := newPage(appversion.Version{Code: 815, Name: "0.7.7-dev.3+gabc1234"}, update.Status{})
	for _, s := range []string{"源码", "abc1234", "鸣谢", "Eta", "mygo", "Go x/crypto"} {
		if !tt.HasText(s) {
			t.Errorf("no %q in %q", s, tt.Texts())
		}
	}
	if err := tt.Click("源码"); err != nil {
		t.Fatal(err)
	}
	if err := tt.Click("mygo"); err != nil {
		t.Fatal(err)
	}
	want := []string{repo + "/tree/abc1234", "https://github.com/egoist/mygo"}
	if got := tt.OpenedURLs(); !slices.Equal(got, want) {
		t.Fatalf("打开了 %q，要 %q", got, want)
	}
}

// 鸣谢里的 Go 依赖就是 windows/go.mod 和它经 replace 链接的 hub/go.mod、client/go.mod 直接依赖的第三方模块（仓里的 hub、client、helper 除外）：
// 加了、去掉一个直接依赖而鸣谢没跟着改，这里失败。COUI 2 那一项靠图标还照搬着 coui-icons，图标换掉时也失败。
func TestCreditsMatchGoMod(t *testing.T) {
	var direct []string
	// hub/、client/ 的 go.mod 经 windows/testdata 里指过去的符号链接读：模块目录外的路径 go test 的缓存不认
	for _, path := range []string{"../../../go.mod", "../../../testdata/hub/go.mod", "../../../testdata/client/go.mod"} {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range directRequires(string(data)) {
			if !slices.Contains(direct, m) {
				direct = append(direct, m)
			}
		}
	}
	if len(direct) == 0 {
		t.Fatal("go.mod 里没读出直接依赖")
	}
	var listed []string
	for _, c := range credits {
		if c.module != "" {
			listed = append(listed, c.module)
		}
	}
	slices.Sort(direct)
	slices.Sort(listed)
	if !slices.Equal(direct, listed) {
		t.Errorf("go.mod 直接依赖 %q，鸣谢列的是 %q", direct, listed)
	}
	icons, err := os.ReadFile("../widget/icons.go")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(icons), "coui-icons") {
		t.Error("widget/icons.go 不再提 coui-icons：图标不照搬 COUI 2 了的话，鸣谢里去掉 COUI 2 这一项")
	}
}

// directRequires 是 go.mod 的 require 块里直接依赖的第三方模块（不带 // indirect 的行，仓里的 github.com/dzshzx/Zeta/… 除外）。
func directRequires(gomod string) []string {
	var out []string
	in := false
	for _, line := range strings.Split(gomod, "\n") {
		line = strings.TrimSpace(line)
		switch {
		case line == "require (":
			in = true
		case line == ")":
			in = false
		case in && line != "" && !strings.Contains(line, "// indirect"):
			path := strings.Fields(line)[0]
			if !strings.HasPrefix(path, "github.com/dzshzx/Zeta/") {
				out = append(out, path)
			}
		}
	}
	return out
}
