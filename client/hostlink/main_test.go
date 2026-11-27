package hostlink

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"

	"github.com/dzshzx/Zeta/client/testinput"
)

// testName 是测试里 Zeta 在那边的目录名（ZETA_NAME）；用哪个都行，客户端各自定。
const testName = "zeta-test"

// helperSource 是仓里的 helper/，经 client/testdata 里的符号链接找：这样 go test 的缓存认得它（见 testinput）。
var helperSource = filepath.Join("..", "testdata", "helper")

// helperBinaries 是测试前从仓里的 helper/ 编出的 zeta-helper（linux 的 arm64、amd64 两份，编法同 hub/scripts/assets.sh），
// 装程序、订阅会话列表、serve 重连的测试在 sshtest 的设备上真跑它。
var helperBinaries map[string][]byte

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "hostlink-helper-")
	if err == nil {
		helperBinaries, err = buildHelper(dir)
	}
	if err != nil {
		// 编不出就失败，不跳过：没跑到的测试不算通过
		fmt.Fprintln(os.Stderr, "hostlink: 编不出测试用的 zeta-helper：", err)
		os.Exit(1)
	}
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}

// buildHelper 在 dir 里编出 helperSource 的两份，返回各自的内容。go 用 PATH 上的（go test 把它的工具链放在 PATH 最前）。
func buildHelper(dir string) (map[string][]byte, error) {
	binaries := map[string][]byte{}
	for _, arch := range Arches {
		out := filepath.Join(dir, "zeta-helper-"+arch)
		cmd := exec.Command("go", "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w -buildid=", "-o", out, ".")
		cmd.Dir = helperSource
		cmd.Env = append(os.Environ(), "CGO_ENABLED=0", "GOOS=linux", "GOARCH="+arch)
		if text, err := cmd.CombinedOutput(); err != nil {
			return nil, fmt.Errorf("%s：%w\n%s", arch, err, text)
		}
		data, err := os.ReadFile(out)
		if err != nil {
			return nil, err
		}
		binaries[arch] = data
	}
	return binaries, nil
}

// helperRead 打开一遍 helper 的源码：TestMain 编它时 go test 的缓存还没开始记，测试用到它时在这里补上。
var helperRead = sync.OnceValue(func() error { return testinput.Tree(helperSource) })

// testHelper 是编好的 zeta-helper。
func testHelper() *Program {
	if err := helperRead(); err != nil {
		panic(err)
	}
	return NewProgram(ZetaHelper, helperBinaries)
}
