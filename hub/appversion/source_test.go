package appversion

import "testing"

// 源码那一行：正式版是 tag，测试版是版本名里的提交号，认不出时是仓库首页（同手机版 ZetaInfo.sourceRef）。
func TestSourceRef(t *testing.T) {
	for _, c := range []struct{ name, ref, url string }{
		{"0.7.6", "v0.7.6", Repo + "/tree/v0.7.6"},
		{"0.7.7-dev.3+gabc1234", "abc1234", Repo + "/tree/abc1234"},
		{"未知", "", Repo},
		{"0.7.6-rc1", "", Repo},
	} {
		if got := SourceRef(c.name); got != c.ref {
			t.Errorf("SourceRef(%q) = %q，要 %q", c.name, got, c.ref)
		}
		if got := SourceURL(c.name); got != c.url {
			t.Errorf("SourceURL(%q) = %q，要 %q", c.name, got, c.url)
		}
	}
}
