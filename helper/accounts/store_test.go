package accounts

import (
	"os"
	"path/filepath"
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
