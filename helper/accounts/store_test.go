package accounts

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/agent-usage/reading"
	"github.com/dzshzx/claude-switch/account"
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

// 额度的套餐名取在用登录的 planDisplayName：get_usage 只报订阅类型 max，分不出 Max 与 Ultra。读和事件并进的结果也带着它，
// 没有套餐名的旧快照读出来时补上；登录资料里没有时，留着快照里已有的。
func TestStoreReportsPlanNameOfTheLogin(t *testing.T) {
	h, s := storeHome(t)
	h.login("uuid-a", "a@example.com", "max")
	if snap, _, err := s.Usage([]byte(usageAnswer), now); err != nil || snap.Plan != "" || snap.Subscription != "max" {
		t.Fatalf("资料里没有套餐名：%+v %v", snap, err)
	}

	h.setPlan("Ultra")
	snap, fresh, err := s.Usage([]byte(usageAnswer), now.Add(time.Minute))
	if err != nil || !fresh || snap.Plan != "Ultra" || snap.Subscription != "max" {
		t.Fatalf("Usage：%+v %v %v", snap, fresh, err)
	}
	if got, err := s.Get(); err != nil || got == nil || got.Plan != "Ultra" {
		t.Fatalf("Get：%+v %v", got, err)
	}
	if ev, changed, err := s.Event(event(0.5, "allowed", "allowed"), now.Add(2*time.Minute)); err != nil || !changed || ev.Plan != "Ultra" {
		t.Fatalf("Event：%+v %v %v", ev, changed, err)
	}

	// 存着的快照没有套餐名（旧版本存的）：读出来补上，不用等下一次 get_usage
	old := snap
	old.Plan = ""
	if err := s.save("uuid-a", old); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.Get(); got == nil || got.Plan != "Ultra" {
		t.Fatalf("旧快照没补上：%+v", got)
	}
	// 事件并进没有套餐名的旧快照：推给客户端的那份也带着，不先露出订阅类型再变
	if ev, changed, err := s.Event(event(0.6, "allowed", "allowed"), now.Add(3*time.Minute)); err != nil || !changed || ev.Plan != "Ultra" {
		t.Fatalf("事件并进旧快照：%+v %v %v", ev, changed, err)
	}
	if err := s.save("uuid-a", old); err != nil {
		t.Fatal(err)
	}
	// 套餐变了（升级、降级）：以登录资料的为准
	h.setPlan("Max")
	if got, _ := s.Get(); got == nil || got.Plan != "Max" {
		t.Fatalf("套餐变了：%+v", got)
	}
	// 登录资料里没有套餐名：快照里已有的留着
	if err := s.save("uuid-a", snap); err != nil {
		t.Fatal(err)
	}
	h.setPlan("")
	if got, _ := s.Get(); got == nil || got.Plan != "Ultra" {
		t.Fatalf("资料里没有时该留着已有的：%+v", got)
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
	data, err := os.ReadFile(reading.LogPath(reading.Dir(h.home), "claude"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], `"used_percentage":86`) || !strings.Contains(lines[1], `"used_percentage":40`) {
		t.Errorf("读数 = %q，期望 86%% 一行、40%% 一行（第二个事件没变，不记）", lines)
	}

	h.writeBytes(account.StateDir+"/current", []byte("claude-b\n"), 0o644)
	if _, _, err := s.Event(event(0.25, "allowed", "allowed"), now.Add(3*time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(reading.LogPath(reading.Dir(h.home), "claude-b")); err != nil {
		t.Errorf("换到 claude-b 后读数要记在它名下：%v", err)
	}
}
