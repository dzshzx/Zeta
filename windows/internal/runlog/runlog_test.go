package runlog

import (
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/redact"
)

// clock 是测试用的时钟：每取一次往后走一毫秒。
func clock() func() time.Time {
	at := time.Date(2026, 11, 23, 10, 0, 0, 0, time.Local)
	return func() time.Time {
		at = at.Add(time.Millisecond)
		return at
	}
}

func TestAddWritesAndReadsBack(t *testing.T) {
	dir := t.TempDir()
	l := Open(dir, clock())
	l.Add(Info, App, "  Zeta 0.8.0 启动  ")
	l.Add(Error, Chat, "第一行\n第二行")
	data, err := os.ReadFile(filepath.Join(dir, FileName))
	if err != nil {
		t.Fatal(err)
	}
	want := "2026-11-23 10:00:00.001 信息 [应用] Zeta 0.8.0 启动\n2026-11-23 10:00:00.002 出错 [对话] 第一行\n    第二行\n"
	if string(data) != want {
		t.Fatalf("文件：%q，要 %q", data, want)
	}
	// 重新打开读回同样的两条（多行的拼回去）
	got := Open(dir, clock()).Entries()
	if len(got) != 2 || got[1].Message != "第一行\n第二行" || got[1].Level != Error || got[1].Topic != Chat || got[0].Message != "Zeta 0.8.0 启动" {
		t.Fatalf("读回：%+v", got)
	}
}

// 标准库 log 接过来：按开头分类、去掉开头，记成警告；别的开头算应用、原样留着。
func TestStandardLogGoesIn(t *testing.T) {
	l := Open(t.TempDir(), clock())
	std := log.New(l, "", 0)
	std.Printf("chat: %s 的 conv.state 解不开：%v", "w1", "boom")
	std.Printf("host: 会话列表的推送解不开")
	std.Printf("zeta: 设置没存上")
	std.Printf("mygo: 别的库")
	got := l.Entries()
	want := []struct {
		topic Topic
		text  string
	}{{Chat, "w1 的 conv.state 解不开：boom"}, {Device, "会话列表的推送解不开"}, {App, "设置没存上"}, {App, "mygo: 别的库"}}
	if len(got) != len(want) {
		t.Fatalf("记了 %d 条：%+v", len(got), got)
	}
	for i, w := range want {
		if got[i].Topic != w.topic || got[i].Message != w.text || got[i].Level != Warn {
			t.Errorf("第 %d 条：%+v，要 %v %q 警告", i, got[i], w.topic, w.text)
		}
	}
}

// 记之前打码：文件和内存里都没有 token、密码。
func TestRedactsBeforeWriting(t *testing.T) {
	dir := t.TempDir()
	l := Open(dir, clock())
	l.Add(Warn, Device, "连不上：Authorization: Bearer fake-token-1 password=fake-pass-1")
	data, _ := os.ReadFile(filepath.Join(dir, FileName))
	for _, s := range []string{string(data), l.Entries()[0].Message} {
		if strings.Contains(s, "fake-token-1") || strings.Contains(s, "fake-pass-1") || !strings.Contains(s, redact.Mask) {
			t.Errorf("没打码：%q", s)
		}
	}
}

// zeta.log 满了换名成 zeta.log.1（旧的那份替掉），两份加起来不超过约两倍上限；读回时两份都读，旧的在前。
func TestRotates(t *testing.T) {
	dir := t.TempDir()
	l := Open(dir, clock())
	msg := strings.Repeat("长", 300) // 一条约 950 字节
	n := 3 * MaxBytes / 950
	for i := 0; i < n; i++ {
		l.Add(Info, App, msg)
	}
	cur, err := os.Stat(filepath.Join(dir, FileName))
	if err != nil {
		t.Fatal(err)
	}
	old, err := os.Stat(filepath.Join(dir, FileName+".1"))
	if err != nil {
		t.Fatal("没换下旧的一份：", err)
	}
	if cur.Size() > MaxBytes || old.Size() > MaxBytes {
		t.Fatalf("超过上限：%d %d", cur.Size(), old.Size())
	}
	back := Open(dir, clock()).Entries()
	if len(back) != Capacity {
		t.Fatalf("读回 %d 条，要 %d（内存里只留这么多）", len(back), Capacity)
	}
	if !back[0].At.Before(back[len(back)-1].At) {
		t.Fatal("旧的要在前")
	}
}

func TestTruncatesLongMessages(t *testing.T) {
	l := Open("", clock())
	l.Add(Info, App, strings.Repeat("字", MaxMessage+10))
	if got := []rune(l.Entries()[0].Message); len(got) != MaxMessage+len([]rune("…（截断）")) {
		t.Fatalf("截断后 %d 字", len(got))
	}
}

func TestClear(t *testing.T) {
	dir := t.TempDir()
	l := Open(dir, clock())
	changes := 0
	l.OnChange(func() { changes++ })
	l.Add(Info, App, "一")
	if err := os.WriteFile(filepath.Join(dir, FileName+".1"), []byte("旧的\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := l.Clear(); err != nil {
		t.Fatal(err)
	}
	if len(l.Entries()) != 0 || changes != 2 {
		t.Fatalf("清空后 %d 条，通知 %d 次", len(l.Entries()), changes)
	}
	for _, name := range []string{FileName, FileName + ".1"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Errorf("%s 还在：%v", name, err)
		}
	}
	// 清空后接着记，从头写
	l.Add(Warn, App, "二")
	if got := Open(dir, clock()).Entries(); len(got) != 1 || got[0].Message != "二" {
		t.Fatalf("清空后再记：%+v", got)
	}
}

// 认不出的行跳过，它后面的缩进行也不接到上一条上。
func TestParseSkipsGarbage(t *testing.T) {
	data := "2026-11-23 10:00:00.001 信息 [应用] 好的\n半行坏的\n    坏的后续\n2026-11-23 10:00:00.002 未知 [应用] 认不出的轻重\n" +
		"2026-11-23 10:00:00.003 警告 [设备] 也好\n    第二行\n"
	got := Parse([]byte(data))
	if len(got) != 2 || got[0].Message != "好的" || got[1].Message != "也好\n第二行" || got[1].Topic != Device {
		t.Fatalf("%+v", got)
	}
}

func TestMatches(t *testing.T) {
	e := Entry{Level: Warn, Topic: Chat}
	for _, c := range []struct {
		topic Topic
		min   Level
		want  bool
	}{{"", Info, true}, {Chat, Warn, true}, {App, Info, false}, {"", Error, false}} {
		if got := e.Matches(c.topic, c.min); got != c.want {
			t.Errorf("Matches(%q, %v) = %v", c.topic, c.min, got)
		}
	}
}
