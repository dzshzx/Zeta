package accounts

import (
	"testing"
	"time"

	"github.com/egoist/mygo/i18n"
)

var now = time.UnixMilli(1_795_000_000_000)

func TestTexts(t *testing.T) {
	zone := time.FixedZone("CST", 8*3600)
	at := now.In(zone)
	cases := []struct{ got, want string }{
		{ResetText(at.Add(45*time.Minute-10*time.Millisecond), at), "45 分钟后重置"},
		{ResetText(at.Add(125*time.Minute), at), "2 小时 5 分钟后重置"},
		{ResetText(at.Add(180*time.Minute), at), "3 小时后重置"},
		{ResetText(time.Date(2026, 11, 22, 1, 0, 0, 0, zone), at), "周日 01:00 重置"},
		{ResetText(at.Add(8*24*time.Hour), at), [...]string{"周日", "周一", "周二", "周三", "周四", "周五", "周六"}[at.Add(8*24*time.Hour).Weekday()] + at.Format(" 15:04") + " 重置（8 天后）"},
		{ResetText(at.Add(-time.Millisecond), at), "已重置"},
		{ResetText(time.Time{}, at), ""},
		{AgeText(now.Add(-30*time.Second), now), "刚刚"},
		{AgeText(now.Add(-5*time.Minute), now), "5 分钟前"},
		{AgeText(now.Add(-3*time.Hour), now), "3 小时前"},
		{AgeText(now.Add(-50*time.Hour), now), "2 天前"},
		{Money(new(1234.0), "USD"), "$12.34"},
		{Money(new(500.0), "eur"), "5.00 EUR"},
		{Money(nil, "USD"), ""},
	}
	for i, c := range cases {
		if c.got != c.want {
			t.Errorf("第 %d 项：%q，要 %q", i, c.got, c.want)
		}
	}
}

// 英文里重置时刻按英文写，天数分单复数。
func TestTextsInEnglish(t *testing.T) {
	i18n.SetLocaleFunc(func() string { return "en" })
	defer i18n.SetLocaleFunc(nil)
	zone := time.FixedZone("CST", 8*3600)
	at := now.In(zone)
	cases := []struct{ got, want string }{
		{ResetText(at.Add(125*time.Minute), at), "Resets in 2 hr 5 min"},
		{ResetText(time.Date(2026, 11, 22, 1, 0, 0, 0, zone), at), "Resets Sun 1:00 AM"},
		{ResetText(time.Date(2026, 11, 30, 13, 0, 0, 0, zone), at), "Resets Mon 1:00 PM (in 11 days)"},
		{AgeText(now.Add(-26*time.Hour), now), "1 day ago"},
	}
	for i, c := range cases {
		if c.got != c.want {
			t.Errorf("第 %d 项：%q，要 %q", i, c.got, c.want)
		}
	}
}
