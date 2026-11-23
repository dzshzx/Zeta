package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// gradleRepo 搭一个假的仓跑真的 tools/gradle：JDK、systemd 分组和 gradlew 都是假的。假 gradlew 起来时写
// started-<名字>，等 release-<名字> 出现才退出，所以测试能控制每个构建占着名额多久。
func gradleRepo(t *testing.T) string {
	t.Helper()
	script, err := os.ReadFile(filepath.Join("testdata", "gradle"))
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	for _, sub := range []string{"tools", "run"} {
		if err := os.Mkdir(filepath.Join(dir, sub), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "tools/gradle"), script, 0o755); err != nil {
		t.Fatal(err)
	}
	writeExec(t, filepath.Join(dir, "tools/java-home"), "echo /nonexistent")
	if err := os.WriteFile(filepath.Join(dir, "tools/own-scope.sh"), []byte("own_scope() { OWN_SCOPE=(); }\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	writeExec(t, filepath.Join(dir, "gradlew"),
		`touch "$ZETA_TEST_DIR/started-$1"; while [ ! -e "$ZETA_TEST_DIR/release-$1" ]; do sleep 0.05; done`)
	return dir
}

// startGradle 在假仓里起一个名叫 name 的构建（两个名额），标准错误写到 err-<名字>。
func startGradle(t *testing.T, dir, name string) *exec.Cmd {
	t.Helper()
	stderr, err := os.Create(filepath.Join(dir, "err-"+name))
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(filepath.Join(dir, "tools/gradle"), name)
	cmd.Env = append(os.Environ(), "XDG_RUNTIME_DIR="+filepath.Join(dir, "run"), "ZETA_GRADLE_SLOTS=2", "ZETA_TEST_DIR="+dir)
	cmd.Stderr = stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		_ = stderr.Close()
	})
	return cmd
}

// awaitGradle 等到 cond 成立；30 秒只防卡死，正常时几百毫秒内就成立。
func awaitGradle(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("30 秒内没等到：%s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func queued(dir, name string) func() bool {
	return func() bool {
		b, _ := os.ReadFile(filepath.Join(dir, "err-"+name))
		return strings.Contains(string(b), "排队等待")
	}
}

// 名额满时先来先得：C 先排上、D 后到，空出来的名额不管是哪一个都轮到 C，D 接着等下一个。
// 曾经排队的只盯着按进程号挑中的那一个名额，后到的却逐个试，于是合入门禁一再被后到的本地构建挤掉。
func TestGradleQueueFirstComeFirstServed(t *testing.T) {
	for _, freed := range []string{"a", "b"} {
		t.Run("先空出 "+freed, func(t *testing.T) {
			dir := gradleRepo(t)
			at := func(f string) string { return filepath.Join(dir, f) }
			release := func(name string) {
				if err := os.WriteFile(at("release-"+name), nil, 0o644); err != nil {
					t.Fatal(err)
				}
			}
			started := func(name string) func() bool { return func() bool { return exists(at("started-" + name)) } }

			startGradle(t, dir, "a")
			startGradle(t, dir, "b")
			awaitGradle(t, "a、b 拿到名额", func() bool { return started("a")() && started("b")() })
			startGradle(t, dir, "c")
			awaitGradle(t, "c 排上队", queued(dir, "c"))
			startGradle(t, dir, "d")
			awaitGradle(t, "d 排上队", queued(dir, "d"))

			release(freed)
			awaitGradle(t, "空出的名额有人拿", func() bool { return started("c")() || started("d")() })
			if !started("c")() || started("d")() {
				t.Fatalf("空出的名额该给先排上的 c：c 起来了 %v，d 起来了 %v", started("c")(), started("d")())
			}
			release("c")
			awaitGradle(t, "d 拿到下一个名额", started("d"))
			for _, name := range []string{"a", "b", "d"} {
				release(name)
			}
		})
	}
}
