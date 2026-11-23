package accounts

import (
	"fmt"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// ResetText 是「45 分钟后重置」「2 小时 5 分钟后重置」「周六 01:00 重置」；不知道重置时刻时为空。
func ResetText(at, now time.Time) string {
	if at.IsZero() {
		return ""
	}
	left := at.Sub(now)
	if left <= 0 {
		return locales.AccountsResetDone()
	}
	minutes := int((left + time.Minute - time.Millisecond) / time.Minute)
	switch {
	case minutes < 60:
		return locales.AccountsResetInMinutes(minutes)
	case minutes < 24*60 && minutes%60 == 0:
		return locales.AccountsResetInHours(minutes / 60)
	case minutes < 24*60:
		return locales.AccountsResetInHoursMinutes(minutes/60, minutes%60)
	}
	local := at.In(now.Location())
	if days := int(left / (24 * time.Hour)); days >= 7 {
		return locales.AccountsResetAtFar(local, days)
	}
	return locales.AccountsResetAt(local)
}

// AgeText 是「刚刚」「3 分钟前」「2 小时前」「1 天前」。
func AgeText(at, now time.Time) string {
	minutes := int(now.Sub(at) / time.Minute)
	switch {
	case minutes < 1:
		return locales.AccountsAgeJustNow()
	case minutes < 60:
		return locales.AccountsAgeMinutes(minutes)
	case minutes < 24*60:
		return locales.AccountsAgeHours(minutes / 60)
	}
	return locales.AccountsAgeDays(minutes / (24 * 60))
}

// Money 把最小单位的金额写成「$12.34」或「12.34 EUR」；不知道金额时为空。
func Money(minor *float64, currency string) string {
	if minor == nil {
		return ""
	}
	amount := fmt.Sprintf("%.2f", *minor/100)
	if c := strings.ToUpper(currency); c != "" && c != "USD" {
		return amount + " " + c
	}
	return "$" + amount
}
