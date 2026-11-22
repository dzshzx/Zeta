package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/core"
)

// build 是构建序号：helper/ 最后一次提交的时刻（Unix 秒），编的时候用 -ldflags "-X main.build=…" 写进来
// （app/build.gradle.kts 的 GoProgramTask、windows/scripts/assets.sh），各端从同一个提交编出来的相同。
// 没写入（go build、测试）时为空，当 0：比谁都旧，不会让写了序号的核心交班。
var build string

// programPrefix 是装好的程序文件名在版本前面的部分（~/.cache/<ZETA_NAME>/bin/zeta-helper-<版本>）。
const programPrefix = "zeta-helper-"

// buildInfo 是这个程序的构建序号和版本（装好的文件名里的内容哈希；不是装好的程序时是整个文件名）。
func buildInfo() core.Info {
	n, _ := strconv.ParseInt(build, 10, 64)
	info := core.Info{Build: n}
	if exe, err := os.Executable(); err == nil {
		info.Version = strings.TrimPrefix(filepath.Base(exe), programPrefix)
	}
	return info
}
