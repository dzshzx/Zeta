package appversion

import "regexp"

// Repo 是 Zeta 的源码仓库（同手机版 ZetaInfo.REPO）。
const Repo = "https://github.com/dzshzx/Zeta"

var devCommit = regexp.MustCompile(`\+g([0-9a-f]{7,40})$`)

// SourceRef 是版本 name 的源码在 GitHub 上的哪个 tag 或提交（同手机版 ZetaInfo.sourceRef）：正式版 0.7.6 是 tag v0.7.6，
// 测试版 0.7.6-dev.3+gabc1234 是版本名里的提交号，认不出时是空。
func SourceRef(name string) string {
	if releaseName.MatchString(name) {
		return "v" + name
	}
	if m := devCommit.FindStringSubmatch(name); m != nil {
		return m[1]
	}
	return ""
}

// SourceURL 是版本 name 的源码：SourceRef 那个 tag 或提交的目录，认不出时是仓库首页。
func SourceURL(name string) string {
	if ref := SourceRef(name); ref != "" {
		return Repo + "/tree/" + ref
	}
	return Repo
}
