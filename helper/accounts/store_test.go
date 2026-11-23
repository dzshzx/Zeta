package accounts

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func storeHome(t *testing.T) (*testHost, QuotaStore) {
	t.Helper()
	home := t.TempDir()
	return &testHost{t: t, home: home}, QuotaStore{Home: home}
}

func TestStoreKeepsQuotaPerAccount(t *testing.T) {
	h, s := storeHome(t)
	if snap, err := s.Get(); err != nil || snap != nil {
		t.Fatalf("没登录：%v %v", snap, err)
	}
	// 没登录时照样读出来，只是不存
	if snap, fresh, err := s.Usage([]byte(usageAnswer), now); err != nil || !fresh || len(snap.Windows) != 2 {
		t.Fatalf("%+v %v %v", snap, fresh, err)
	}
	if _, err := os.Stat(filepath.Join(h.home, quotaDir)); !os.IsNotExist(err) {
		t.Fatal("没登录时不该存")
	}

	h.login("uuid-a", "a@example.com", "max")
	if _, _, err := s.Usage([]byte(usageAnswer), now); err != nil {
		t.Fatal(err)
	}
	got, err := s.Get()
	if err != nil || got == nil || len(got.Windows) != 2 || got.Windows[0].Label != "每周 · 全部模型" {
		t.Fatalf("存了再读：%+v %v", got, err)
	}

	// 换了号：读不到上一个号的；换回来还在
	h.login("uuid-b", "b@example.com", "pro")
	if got, _ := s.Get(); got != nil {
		t.Fatalf("新号不该看到旧号的额度：%+v", got)
	}
	h.login("uuid-a", "a@example.com", "max")
	if got, _ := s.Get(); got == nil {
		t.Fatal("换回来应还在")
	}
}

func TestStoreUsageKeepsWindowsWhenAnswerIsEmpty(t *testing.T) {
	h, s := storeHome(t)
	h.login("uuid-a", "a@example.com", "max")
	if _, _, err := s.Usage([]byte(usageAnswer), now); err != nil {
		t.Fatal(err)
	}
	// 被拒的状态只有事件里有
	blocked, changed, err := s.Event(event(1.0, "rejected", "rejected"), now.Add(time.Second))
	if err != nil || !changed || !blocked.Blocked || len(blocked.Windows) != 3 {
		t.Fatalf("%+v %v %v", blocked, changed, err)
	}
	snap, fresh, err := s.Usage([]byte(`{"subscription_type":"max","rate_limits_available":true,"rate_limits":null}`), now.Add(time.Minute))
	if err != nil || fresh || len(snap.Windows) != 3 {
		t.Fatalf("这次没取到数时留着已有的：%+v %v %v", snap, fresh, err)
	}
	snap, fresh, err = s.Usage([]byte(usageAnswer), now.Add(2*time.Minute))
	if err != nil || !fresh || !snap.Blocked || len(snap.Windows) != 2 {
		t.Fatalf("取到新数时沿用被拒：%+v %v %v", snap, fresh, err)
	}
	if _, _, err := s.Usage([]byte(`{"models":[]}`), now); err != ErrNotUsage {
		t.Fatalf("别的应答：%v", err)
	}
	// 坏了的文件当作没有
	h.writeBytes(filepath.Join(quotaDir, filepath.Base(s.path("uuid-a"))), []byte("{"), 0o600)
	if got, err := s.Get(); err != nil || got != nil {
		t.Fatalf("%+v %v", got, err)
	}
}

// 收到的每周额度也记给 agent-usage：家目录下它的数据目录里按 claude-switch 的切换记录分账号，读数没变不重记。
func TestStoreRecordsWeeklyReadingsForAgentUsage(t *testing.T) {
	t.Setenv("AGENT_USAGE_DATA_DIR", "")
	h, s := storeHome(t)
	h.login("uuid-a", "a@example.com", "max")
	if _, _, err := s.Usage([]byte(usageAnswer), now); err != nil {
		t.Fatal(err)
	}
	// 事件的每周窗口（0.4，即 40%）重置得比 get_usage 那份晚：算新的周期，记一行
	if _, _, err := s.Event(event(0.25, "allowed", "allowed"), now.Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Event(event(0.25, "allowed", "allowed"), now.Add(2*time.Second)); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(h.home, ".local/share/agent-usage/rate-limits.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], `"used_percentage":86`) || !strings.Contains(lines[1], `"used_percentage":40`) {
		t.Errorf("读数 = %q，期望 86%% 一行、40%% 一行（第二个事件没变，不记）", lines)
	}

	h.writeBytes(".local/state/claude-switch/current", []byte("claude-b\n"), 0o644)
	if _, _, err := s.Event(event(0.25, "allowed", "allowed"), now.Add(3*time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(h.home, ".local/share/agent-usage/rate-limits.claude-b.jsonl")); err != nil {
		t.Errorf("换到 claude-b 后读数要记在它名下：%v", err)
	}
}
