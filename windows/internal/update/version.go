// Package update 是桌面版的自动更新：读最新正式版 Release 的 update.json，下载、核对 MSI，再起一个 PowerShell 等本程序退出后装上、
// 重新打开。做法见 docs/release.md「应用内更新」。
package update

import (
	"regexp"
	"strconv"
)

// 构建时由 installer/build.sh 经 -ldflags -X 写入；go run、go test 编出来的是空的，算开发版。
var (
	versionName string
	versionCode string
)

// Version 是这个程序的版本。
type Version struct {
	// Code 是构建时 HEAD 的提交数，和手机版的 versionCode 是同一个数；update.json 的 versionCode 比它大才算新版本。
	Code int
	// Name 是版本名：发版 tag 上是 0.7.6，别的提交是 0.7.6-dev.3+gabc1234。
	Name string
}

var releaseName = regexp.MustCompile(`^\d+\.\d+\.\d+$`)

// Current 是本程序的版本；构建时没写进来或写坏了时是 Code 为 0 的开发版。
func Current() Version { return parseVersion(versionCode, versionName) }

func parseVersion(code, name string) Version {
	n, err := strconv.Atoi(code)
	if err != nil || n <= 0 || name == "" {
		return Version{Name: "未知"}
	}
	return Version{Code: n, Name: name}
}

// Release 说这是不是正式发版打的包（版本名是干净的 X.Y.Z）：只有它检查更新，开发构建不查。
func (v Version) Release() bool { return v.Code > 0 && releaseName.MatchString(v.Name) }
