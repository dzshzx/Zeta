package accounts

import (
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
)

// 账号一行的说明里写套餐的展示名（Ultra 的号订阅类型仍是 max），核心没给时回落到订阅类型。
func TestSummaryShowsPlanName(t *testing.T) {
	t.Parallel()
	now := time.Now()
	cases := []struct {
		account coreapi.Account
		want    string
	}{
		{coreapi.Account{Name: "claude", Organization: "Org A", Subscription: "max", Plan: "Ultra"}, "claude · Org A · Ultra"},
		{coreapi.Account{Name: "claude", Subscription: "max"}, "claude · Max"},
		{coreapi.Account{Name: "claude-b", Subscription: "pro", Plan: "Pro"}, "claude-b · Pro"},
	}
	for _, c := range cases {
		if got := summary(c.account, now); got != c.want {
			t.Errorf("%+v：%q，期望 %q", c.account, got, c.want)
		}
	}
}
