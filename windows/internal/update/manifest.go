package update

import (
	"encoding/json"
	"errors"
	"regexp"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// LatestURL 是最新正式版的清单：GitHub 把 latest 跳到最新发布的那个 Release。手机正式版和桌面版都读它。
const LatestURL = "https://github.com/dzshzx/Zeta/releases/latest/download/update.json"

const (
	// maxManifestBytes：清单只有一两 KB，比这大的不当它是清单。
	maxManifestBytes = 256 << 10
	// maxMSIBytes：MSI 大小的上限，挡住写错的清单把磁盘写满。MSI 只有二十来 MB，留足余量。
	maxMSIBytes = 2 << 30
)

// Package 是清单里写的一个安装包。
type Package struct {
	File   string // 文件名，下载到本地也用它
	URL    string // 下载地址；GitHub 上的连不上时再走镜像（见 GitHubMirrors）
	Size   int64  // 字节数
	SHA256 string // 小写十六进制
}

// Manifest 是每次发版 Release 里的 update.json（tools/release 的 package 写的，字段见 docs/release.md「Release 的资产」）。
// 桌面版只取用得到的几项，不认识的忽略：以后加字段只加不改。
type Manifest struct {
	VersionCode int
	VersionName string // X.Y.Z
	Notes       string // 上一个正式版以来的提交标题，Markdown 列表
	PublishedAt string // 打包的时间（RFC 3339），旧的清单没有时为空
	// MSI 是桌面版的安装包：msi、msiUrl、msiSize、msiSha256 四项都在且合规才有。v0.7.5 及更早的清单没有。
	MSI *Package
}

var (
	hex64   = regexp.MustCompile(`^[0-9a-f]{64}$`)
	xyz     = regexp.MustCompile(`^\d+\.\d+\.\d+$`)
	msiName = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.msi$`)
	webURL  = regexp.MustCompile(`^https?://`)
)

// ParseManifest 读 update.json。版本两项不对时返回错误，说哪一项不对；桌面版那四项不对时只是没有 MSI，不算清单坏了。
func ParseManifest(data []byte) (*Manifest, error) {
	var f fields
	if err := json.Unmarshal(data, &f); err != nil || f == nil {
		return nil, errors.New(locales.UpdateManifestNotObject())
	}
	m := &Manifest{Notes: f.str("notes"), PublishedAt: f.str("publishedAt")}
	code, ok := f.num("versionCode")
	if !ok || code < 1 || code > 1<<31-1 {
		return nil, errors.New(locales.UpdateManifestBadCode())
	}
	m.VersionCode = int(code)
	if m.VersionName = f.str("versionName"); !xyz.MatchString(m.VersionName) {
		return nil, errors.New(locales.UpdateManifestBadName())
	}
	m.MSI = f.msi()
	return m, nil
}

// fields 按类型取清单里的一项：字符串只认 JSON 字符串，数字只认不带引号的整数。
type fields map[string]json.RawMessage

func (f fields) str(key string) string {
	var s string
	if json.Unmarshal(f[key], &s) != nil {
		return ""
	}
	return s
}

func (f fields) num(key string) (int64, bool) {
	var n int64
	return n, json.Unmarshal(f[key], &n) == nil
}

func (f fields) msi() *Package {
	p := &Package{File: f.str("msi"), URL: f.str("msiUrl"), SHA256: f.str("msiSha256")}
	size, ok := f.num("msiSize")
	if !ok || size < 1 || size > maxMSIBytes || !msiName.MatchString(p.File) || !webURL.MatchString(p.URL) || !hex64.MatchString(p.SHA256) {
		return nil
	}
	p.Size = size
	return p
}
