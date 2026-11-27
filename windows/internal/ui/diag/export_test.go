package diag

import (
	"testing"
	"time"
)

func TestWhen(t *testing.T) {
	now := time.Date(2026, 11, 23, 15, 0, 0, 0, time.Local)
	for _, c := range []struct {
		at   time.Time
		want string
	}{
		{time.Date(2026, 11, 23, 9, 5, 0, 0, time.Local), "09:05"},
		{time.Date(2026, 11, 2, 9, 5, 0, 0, time.Local), "11 月 2 日 09:05"},
		{time.Date(2025, 11, 23, 9, 5, 0, 0, time.Local), "2025 年 11 月 23 日"},
	} {
		if got := when(c.at, now); got != c.want {
			t.Errorf("when(%v) = %q，要 %q", c.at, got, c.want)
		}
	}
}

func TestSizeText(t *testing.T) {
	for n, want := range map[int64]string{0: "0 B", 1023: "1023 B", 1536: "1.5 KB", 3 << 20: "3.0 MB"} {
		if got := sizeText(n); got != want {
			t.Errorf("sizeText(%d) = %q，要 %q", n, got, want)
		}
	}
}
