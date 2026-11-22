// Package testinput 让 go test 的测试缓存认得测试经子进程用到的本仓文件，只给测试用。
//
// 合入门禁的 go test 用测试缓存：测试程序没变、测试进程读过的文件和环境变量也没变时，直接取上次的结果。
// 缓存只记测试进程自己打开或 stat 过的、模块目录里的路径（打开目录时连同其中每一项的大小和修改时间）；
// 子进程（go build、脚本）读的文件它看不见，模块目录外的路径它不认。这类输入由测试自己先打开一遍，
// 模块目录外的经模块 testdata 里指过去的符号链接打开：缓存按路径的字面判断在不在模块里，比对时跟着链接看目标。
package testinput

import (
	"io/fs"
	"path/filepath"
)

// Tree 打开 dir 和它下面的每一层目录：其中任何文件增删改，用到它的测试都重跑。dir 可以是指向目录的符号链接，
// 下面的符号链接不跟进。
func Tree(dir string) error {
	// 末尾带上分隔符，dir 是符号链接时也当目录走
	return filepath.WalkDir(dir+string(filepath.Separator), func(_ string, _ fs.DirEntry, err error) error { return err })
}
