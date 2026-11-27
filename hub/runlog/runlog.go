// Package runlog 是桌面版自己的运行日志（同手机版的 DiagLog）：一条一条带时间、轻重和类别的记录，追加到设置目录 diag 下的
// zeta.log；它满 MaxBytes 时换名成 zeta.log.1（旧的那份被替掉），所以两份加起来最多约 2×MaxBytes。内存里留最近 Capacity 条，
// 给设置里诊断页的「运行日志」看；导出诊断包时这两个文件和 diag 下别的文件一起带上。
//
// 标准库 log 的输出也接到这里（入口 log.SetOutput 成 Log，见 Write）：各处的 log.Printf 不用改，按消息开头的「zeta:」「chat:」
// 「host:」分类，记成警告（它们都是出了岔子、程序接着跑的事）。记之前先打码（helper/redact），文件里不留 token、密码。
//
// 文件一条一行：「2026-11-23 10:00:00.123 警告 [对话] 消息」，消息里的换行换成换行加四个空格，读回时按此拼回去。
package runlog

import (
	"bufio"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/redact"
	"github.com/dzshzx/Zeta/hub/locales"
)

// Level 是一条记录的轻重。
type Level int

// 三档轻重，从轻到重。
const (
	Info Level = iota
	Warn
	Error
)

// levelLabels 是写进文件的轻重（不分界面语言，读回时按它认）。
var levelLabels = []string{"信息", "警告", "出错"}

// Label 是写进文件的名字；给人看的用 Name。
func (l Level) Label() string { return levelLabels[l] }

// Name 是按界面当前的语言给人看的名字。
func (l Level) Name() string {
	return [...]func() string{locales.RunlogLevelInfo, locales.RunlogLevelWarn, locales.RunlogLevelError}[l]()
}

// Topic 是一条记录说的是哪方面的事，诊断页按它筛。值原样写进文件、读回时照它认（不分界面语言）；给人看的用 Name。
type Topic string

// 各类别。
const (
	App    Topic = "应用" // 程序本身：启动、设置、草稿、更新
	Chat   Topic = "对话" // 对话的推送、重连
	Device Topic = "设备" // 和设备的连接、会话列表
)

// Topics 是各类别，按诊断页上列的先后。
var Topics = []Topic{App, Chat, Device}

// Name 是按界面当前的语言给人看的名字；认不出的（别的版本写的）原样。
func (t Topic) Name() string {
	switch t {
	case App:
		return locales.RunlogTopicApp()
	case Chat:
		return locales.RunlogTopicChat()
	case Device:
		return locales.RunlogTopicDevice()
	}
	return string(t)
}

// prefixes 是 log.Printf 的消息开头 → 类别。
var prefixes = map[string]Topic{"zeta": App, "chat": Chat, "host": Device}

// Entry 是一条记录。
type Entry struct {
	At      time.Time
	Level   Level
	Topic   Topic
	Message string
}

// Matches：topic 为空是全部类别；比 min 轻的不要。
func (e Entry) Matches(topic Topic, min Level) bool {
	return (topic == "" || e.Topic == topic) && e.Level >= min
}

const (
	// Capacity 是内存里最多留几条（诊断页能看到的）。
	Capacity = 1000
	// MaxBytes 是 zeta.log 长到多大换名成 zeta.log.1。
	MaxBytes = 512 << 10
	// MaxMessage 是一条消息最多多少字，多的截掉（进程的错误输出之类可能很长）。
	MaxMessage = 2000
	// FileName 是日志文件在 diag 目录里的名字，换下来的旧的一份是 FileName + ".1"。
	FileName = "zeta.log"
)

const stamp = "2006-01-02 15:04:05.000"

// Log 是运行日志。各 goroutine 都能用。
type Log struct {
	dir string
	now func() time.Time

	mu      sync.Mutex
	entries []Entry
	size    int64 // zeta.log 现在多大
	// changed 在每记一条、清空以后调（不持锁），界面据此重画；入口设。
	changed func()
}

// Open 打开目录 dir 下的运行日志：读回上次留下的记录。目录不在时第一次记的时候建。now 为 nil 时用 time.Now。
func Open(dir string, now func() time.Time) *Log {
	if now == nil {
		now = time.Now
	}
	l := &Log{dir: dir, now: now}
	for _, name := range []string{FileName + ".1", FileName} {
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		l.entries = append(l.entries, Parse(data)...)
		if name == FileName {
			l.size = int64(len(data))
		}
	}
	l.entries = keepLast(l.entries)
	return l
}

// OnChange 设每记一条、清空以后调的函数（不能回头记日志）。
func (l *Log) OnChange(fn func()) {
	l.mu.Lock()
	l.changed = fn
	l.mu.Unlock()
}

// Add 记一条。消息去掉首尾空白、打码，太长的截掉。
func (l *Log) Add(level Level, topic Topic, message string) {
	e := Entry{At: l.now(), Level: level, Topic: topic, Message: clean(message)}
	l.mu.Lock()
	l.entries = keepLast(append(l.entries, e))
	l.append(Format(e))
	changed := l.changed
	l.mu.Unlock()
	if changed != nil {
		changed()
	}
}

// Write 接标准库 log 的输出（log.SetOutput）：一次是一条消息。开头是「zeta: 」「chat: 」「host: 」的按它分类、去掉它，
// 别的算应用、原样记；都记成警告。
func (l *Log) Write(p []byte) (int, error) {
	text := strings.TrimRight(string(p), "\n")
	topic := App
	if head, rest, ok := strings.Cut(text, ": "); ok {
		if t, known := prefixes[head]; known {
			topic, text = t, rest
		}
	}
	l.Add(Warn, topic, text)
	return len(p), nil
}

// Entries 是内存里的记录，旧的在前（副本）。
func (l *Log) Entries() []Entry {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]Entry(nil), l.entries...)
}

// Clear 清空：内存里的和两个文件都删掉。
func (l *Log) Clear() error {
	l.mu.Lock()
	l.entries, l.size = nil, 0
	var first error
	for _, name := range []string{FileName, FileName + ".1"} {
		if err := os.Remove(filepath.Join(l.dir, name)); err != nil && !os.IsNotExist(err) && first == nil {
			first = err
		}
	}
	changed := l.changed
	l.mu.Unlock()
	if changed != nil {
		changed()
	}
	return first
}

// append 把一行追加进 zeta.log，加上它会超过 MaxBytes 时先把它换名成 zeta.log.1。写不进去（盘满、没权限）时只是没记进文件，
// 内存里照样有；日志自己出的错没地方记。持锁调用。
func (l *Log) append(line string) {
	if l.dir == "" {
		return
	}
	path := filepath.Join(l.dir, FileName)
	if l.size > 0 && l.size+int64(len(line)) > MaxBytes {
		if os.Rename(path, path+".1") == nil {
			l.size = 0
		}
	}
	if err := os.MkdirAll(l.dir, 0o755); err != nil {
		return
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}
	n, _ := f.WriteString(line)
	_ = f.Close()
	l.size += int64(n)
}

func keepLast(list []Entry) []Entry {
	if n := len(list) - Capacity; n > 0 {
		return append(list[:0:0], list[n:]...)
	}
	return list
}

// clean 是记之前的规整：去掉首尾空白，打码，太长的截掉。
func clean(message string) string {
	s := redact.Text(strings.TrimSpace(message))
	if r := []rune(s); len(r) > MaxMessage {
		s = string(r[:MaxMessage]) + "…（截断）"
	}
	return s
}

// Format 是文件里的一行（带换行）：时间、轻重、[类别]、消息；消息里的换行后面缩进四个空格。
func Format(e Entry) string {
	return e.At.Local().Format(stamp) + " " + e.Level.Label() + " [" + string(e.Topic) + "] " +
		strings.ReplaceAll(e.Message, "\n", "\n    ") + "\n"
}

// Parse 读回 Format 写的各行：缩进四个空格的行接在上一条后面；认不出的行（写到一半断电的、别的版本的）跳过。
func Parse(data []byte) []Entry {
	var out []Entry
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	last := -1 // 上一行是不是认出来的那条（不是时它后面的缩进行也丢掉）
	for sc.Scan() {
		line := sc.Text()
		if rest, ok := strings.CutPrefix(line, "    "); ok {
			if last >= 0 {
				out[last].Message += "\n" + rest
			}
			continue
		}
		e, ok := parseLine(line)
		if !ok {
			last = -1
			continue
		}
		out = append(out, e)
		last = len(out) - 1
	}
	return out
}

// parseLine 读一条的第一行。
func parseLine(line string) (Entry, bool) {
	if len(line) < len(stamp)+1 {
		return Entry{}, false
	}
	at, err := time.ParseInLocation(stamp, line[:len(stamp)], time.Local)
	if err != nil {
		return Entry{}, false
	}
	label, rest, ok := strings.Cut(line[len(stamp)+1:], " [")
	if !ok {
		return Entry{}, false
	}
	level := -1
	for i, l := range levelLabels {
		if l == label {
			level = i
		}
	}
	topic, message, ok := strings.Cut(rest, "] ")
	if !ok || level < 0 {
		return Entry{}, false
	}
	return Entry{At: at, Level: Level(level), Topic: Topic(topic), Message: message}, true
}
