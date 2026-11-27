package widget

import (
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestStep(t *testing.T) {
	base := time.Unix(1_000, 0) // 整秒：一圈的起点
	for _, c := range []struct {
		in    time.Duration
		phase float32
		next  time.Duration
	}{
		{0, 0, 50 * time.Millisecond},
		{49 * time.Millisecond, 0, time.Millisecond},
		{50 * time.Millisecond, 0.05, 50 * time.Millisecond},
		{999 * time.Millisecond, 0.95, time.Millisecond},
		{time.Second, 0, 50 * time.Millisecond},
	} {
		phase, next := Step(base.Add(c.in), time.Second, 20)
		if phase != c.phase || next != c.next {
			t.Errorf("一圈 1 秒 20 步，过了 %v：相位 %v、下一步 %v，要 %v、%v", c.in, phase, next, c.phase, c.next)
		}
	}
}

// 桌面版的界面代码里不用 Loop 和 AnimationFrame 做一直转的动画：它们让窗口按显示器的刷新率一直出帧（Loop 还每帧重建
// 整个界面），回答时一直占着 CPU。一直转的提示用 Step 分步。
func TestNoRefreshRateLoops(t *testing.T) {
	banned := regexp.MustCompile(`\.(Loop|AnimationFrame|AnimationFrameRate)\(`)
	root, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	seen := 0
	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return err
		}
		seen++
		src, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		for i, line := range strings.Split(string(src), "\n") {
			if banned.MatchString(line) {
				t.Errorf("%s:%d 用了按刷新率出帧的动画：%s", path, i+1, strings.TrimSpace(line))
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if seen < 50 {
		t.Fatalf("只扫到 %d 个文件，没找到界面代码", seen)
	}
}
