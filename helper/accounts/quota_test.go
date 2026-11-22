package accounts

import (
	"encoding/json"
	"slices"
	"testing"
	"time"
)

var now = time.UnixMilli(1_795_000_000_000)

// usageAnswer 是 get_usage 的应答，形状照 Claude Code 2.1.328 在真账号上的实测（数改过，多余的键删了一些）。
const usageAnswer = `{"session":{"total_cost_usd":0},"subscription_type":"max","rate_limits_available":true,
 "rate_limits":{"five_hour":null,
   "seven_day":{"utilization":86,"resets_at":"2026-11-21T17:00:00.685462+00:00","limit_dollars":null},
   "seven_day_opus":null,"seven_day_sonnet":null,
   "extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null,"currency":null},
   "limits":[
     {"kind":"weekly_all","group":"weekly","percent":86,"severity":"warning","resets_at":"2026-11-21T17:00:00.685462+00:00","scope":null,"is_active":true},
     {"kind":"weekly_scoped","group":"weekly","percent":78,"severity":"warning","resets_at":"2026-11-21T16:59:59.685664+00:00",
      "scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false}
   ],
   "model_scoped":[{"display_name":"Fable","utilization":78,"resets_at":"2026-11-21T16:59:59.685664+00:00"}]},
 "behaviors":null}`

func labels(s Snapshot) []string {
	var out []string
	for _, w := range s.Windows {
		out = append(out, w.Label)
	}
	return out
}

func TestServerRowsAreShownAsSent(t *testing.T) {
	s, err := ParseUsage([]byte(usageAnswer), now)
	if err != nil {
		t.Fatal(err)
	}
	if got := labels(s); !slices.Equal(got, []string{"每周 · 全部模型", "每周 · Fable"}) {
		t.Fatalf("窗口 %q", got)
	}
	w := s.Windows[0]
	want := time.Date(2026, 11, 21, 17, 0, 0, 685462000, time.UTC)
	if w.Percent() != 86 || w.Severity != Warning || !w.ResetsAt.Equal(want) || !w.Active {
		t.Fatalf("第一个窗口 %+v", w)
	}
	if s.Subscription != "max" || s.Extra == nil || s.Extra.Enabled || s.Unavailable {
		t.Fatalf("%+v", s)
	}
}

func TestFixedKeysWhenNoServerRows(t *testing.T) {
	s, err := ParseUsage([]byte(`{"subscription_type":"max","rate_limits_available":true,"rate_limits":{
	   "five_hour":{"utilization":25,"resets_at":"2026-11-19T03:05:10.000Z"},
	   "seven_day":{"utilization":40,"resets_at":"2026-11-22T02:05:10.000Z"},
	   "seven_day_sonnet":{"utilization":5,"resets_at":"2026-11-22T02:05:10.000Z"},
	   "model_scoped":[{"display_name":"Fable","utilization":10,"resets_at":null}]}}`), now)
	if err != nil {
		t.Fatal(err)
	}
	if got := labels(s); !slices.Equal(got, []string{"5 小时", "每周 · 全部模型", "每周 · Sonnet", "每周 · Fable"}) {
		t.Fatalf("窗口 %q", got)
	}
	if s.Windows[0].Used != 0.25 || !s.Windows[3].ResetsAt.IsZero() {
		t.Fatalf("%+v", s.Windows)
	}
}

func TestNotAUsageAnswerOrNoPlan(t *testing.T) {
	if _, err := ParseUsage([]byte(`{"models":[]}`), now); err != ErrNotUsage {
		t.Fatalf("别的应答：%v", err)
	}
	none, err := ParseUsage([]byte(`{"subscription_type":null,"rate_limits_available":false,"rate_limits":null}`), now)
	if err != nil || !none.Unavailable || len(none.Windows) != 0 {
		t.Fatalf("没有订阅额度：%+v %v", none, err)
	}
	empty, err := ParseUsage([]byte(`{"subscription_type":"max","rate_limits_available":true,"rate_limits":null}`), now)
	if err != nil || empty.Unavailable || len(empty.Windows) != 0 {
		t.Fatalf("没取到数：%+v %v", empty, err)
	}
}

// event 是 rate_limit_event 的 rate_limit_info，照 2.1.328 实测的形状。
func event(five float64, status, fiveStatus string) []byte {
	b, _ := json.Marshal(map[string]any{
		"status": status, "resetsAt": 1795057510, "rateLimitType": "five_hour", "isUsingOverage": false,
		"unifiedWindows": map[string]any{
			"five_hour": map[string]any{"utilization": five, "resetsAt": 1795057510, "status": fiveStatus},
			"seven_day": map[string]any{"utilization": 0.4, "resetsAt": 1795313110, "status": "allowed"},
		},
	})
	return b
}

func TestEventsUpdateTheirWindowsAndKeepTheRest(t *testing.T) {
	fromEvent, changed, err := ApplyEvent(nil, event(0.25, "allowed", "allowed"), now)
	if err != nil || !changed {
		t.Fatal(changed, err)
	}
	kinds := func(s Snapshot) []string {
		var out []string
		for _, w := range s.Windows {
			out = append(out, w.Kind)
		}
		return out
	}
	if got := kinds(fromEvent); !slices.Equal(got, []string{"session", "weekly_all"}) || fromEvent.Blocked {
		t.Fatalf("%q %+v", got, fromEvent)
	}
	if !fromEvent.Windows[0].ResetsAt.Equal(time.UnixMilli(1_795_057_510_000)) {
		t.Fatalf("重置时刻 %v", fromEvent.Windows[0].ResetsAt)
	}

	server, _ := ParseUsage([]byte(usageAnswer), now)
	merged, _, _ := ApplyEvent(&server, event(0.9, "allowed", "allowed_warning"), now.Add(time.Millisecond))
	// 5 小时窗口是新出现的，放在最前；每周的被事件的数替换，服务器挑的头条标记留着；按模型分的那行不动
	if got := kinds(merged); !slices.Equal(got, []string{"session", "weekly_all", "weekly_scoped"}) {
		t.Fatalf("合并以后 %q", got)
	}
	if merged.Windows[0].Severity != Warning || merged.Windows[1].Used != 0.4 || !merged.Windows[1].Active || merged.Windows[2].Used != 0.78 {
		t.Fatalf("%+v", merged.Windows)
	}
	if len(server.Windows) != 2 {
		t.Fatal("合并不该改原来的")
	}

	rejected, _, _ := ApplyEvent(&merged, event(1.0, "rejected", "rejected"), now.Add(2*time.Millisecond))
	if !rejected.Blocked || !rejected.BlockedUntil.Equal(time.UnixMilli(1_795_057_510_000)) {
		t.Fatalf("被拒：%+v", rejected)
	}
	// 过了解除时刻：不再算被拒，5 小时窗口从零开始
	later := rejected.At(time.UnixMilli(1_795_057_510_001))
	if later.Blocked || later.Windows[0].Used != 0 || !later.Windows[0].ResetsAt.IsZero() {
		t.Fatalf("过了重置时刻：%+v", later)
	}
	if rejected.Windows[0].Used != 1.0 {
		t.Fatal("At 不该改原来的")
	}

	if _, changed, _ := ApplyEvent(&merged, []byte(`{}`), now); changed {
		t.Fatal("空事件不算变化")
	}
}

func TestSnapshotRoundTrips(t *testing.T) {
	s, _ := ParseUsage([]byte(usageAnswer), now)
	s, _, _ = ApplyEvent(&s, event(1.0, "rejected", "rejected"), now)
	data, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	var back Snapshot
	if err := json.Unmarshal(data, &back); err != nil {
		t.Fatal(err)
	}
	again, _ := json.Marshal(back)
	if string(again) != string(data) {
		t.Fatalf("存了再读变了：\n%s\n%s", data, again)
	}
}
