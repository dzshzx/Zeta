package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestReadCPUTimes(t *testing.T) {
	stat := filepath.Join(t.TempDir(), "stat")
	// user nice system idle iowait irq softirq steal guest guest_nice
	if err := os.WriteFile(stat, []byte("cpu  100 5 50 800 20 3 2 0 9 9\ncpu0 1 2 3 4 5 6 7 8 9 10\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := readCPUTimes(stat)
	if err != nil || got != (cpuTimes{idle: 820, total: 980}) {
		t.Fatalf("got %+v %v", got, err)
	}
	if err := os.WriteFile(stat, []byte("intr 1 2 3 4 5 6 7 8 9\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := readCPUTimes(stat); err == nil {
		t.Fatal("第一行不是 cpu 也读出来了")
	}
}

func TestCPUPct(t *testing.T) {
	for _, c := range []struct {
		prev, cur cpuTimes
		pct       int
		ok        bool
	}{
		{cpuTimes{0, 0}, cpuTimes{75, 100}, 25, true},
		{cpuTimes{0, 0}, cpuTimes{0, 100}, 100, true},
		{cpuTimes{0, 0}, cpuTimes{100, 100}, 0, true},
		{cpuTimes{0, 0}, cpuTimes{55, 200}, 72, true},    // 72.5 逢五取偶
		{cpuTimes{0, 0}, cpuTimes{53, 200}, 74, true},    // 73.5 逢五取偶
		{cpuTimes{50, 100}, cpuTimes{50, 100}, 0, false}, // 计数器没动
		{cpuTimes{50, 100}, cpuTimes{10, 90}, 0, false},  // 倒退
	} {
		if pct, ok := cpuPct(c.prev, c.cur); pct != c.pct || ok != c.ok {
			t.Errorf("%+v → %+v：%d %v，要 %d %v", c.prev, c.cur, pct, ok, c.pct, c.ok)
		}
	}
}

// fakeCPU 给 cpuMeter 一串读数，记下读了几次、睡了多久；时钟由测试拨。
type fakeCPU struct {
	readings []cpuTimes
	reads    int
	slept    time.Duration
	clock    time.Time
}

func (f *fakeCPU) meter() *cpuMeter {
	return &cpuMeter{
		read: func() (cpuTimes, error) {
			if f.reads >= len(f.readings) {
				return cpuTimes{}, errors.New("no more readings")
			}
			f.reads++
			return f.readings[f.reads-1], nil
		},
		now:   func() time.Time { return f.clock },
		sleep: func(d time.Duration) { f.slept += d; f.clock = f.clock.Add(d) },
	}
}

func TestCPUMeter(t *testing.T) {
	f := &fakeCPU{
		clock: time.Unix(1000, 0),
		readings: []cpuTimes{
			{0, 0}, {50, 100}, // 第一次：就地采样，50%
			{140, 200}, // 两秒后：和上一次相减，10%
			{140, 300}, // 十分钟后：上一次太旧，就地采样
			{150, 400},
		},
	}
	m := f.meter()
	if pct, ok := m.percent(); !ok || pct != 50 || f.reads != 2 || f.slept != cpuSample {
		t.Fatalf("第一次：%d %v，读 %d 次，睡 %v", pct, ok, f.reads, f.slept)
	}
	// 不到 0.5 秒再问：不读，答上一次的数
	f.clock = f.clock.Add(200 * time.Millisecond)
	if pct, ok := m.percent(); !ok || pct != 50 || f.reads != 2 {
		t.Fatalf("隔 0.2 秒：%d %v，读 %d 次", pct, ok, f.reads)
	}
	f.clock = f.clock.Add(2 * time.Second)
	if pct, ok := m.percent(); !ok || pct != 10 || f.reads != 3 || f.slept != cpuSample {
		t.Fatalf("隔两秒：%d %v，读 %d 次，睡 %v", pct, ok, f.reads, f.slept)
	}
	f.clock = f.clock.Add(10 * time.Minute)
	if pct, ok := m.percent(); !ok || pct != 90 || f.reads != 5 || f.slept != 2*cpuSample {
		t.Fatalf("隔十分钟：%d %v，读 %d 次，睡 %v", pct, ok, f.reads, f.slept)
	}
	// 读不了：ok 为 false
	f.clock = f.clock.Add(time.Second)
	if _, ok := m.percent(); ok {
		t.Fatal("读不了也答了数")
	}
}

// 经真的 serve 和核心问一次：这台机器的 /proc/stat 读得到，数在 0 到 100 之间，有核数。
func TestSystemCPUThroughServe(t *testing.T) {
	e := testEnv(t)
	c := startSessions(t, e)
	got := asObj(c.ok("system.cpu", ""))
	if pct := num(t, got["percent"]); pct < 0 || pct > 100 || num(t, got["cores"]) < 1 {
		t.Fatalf("system.cpu：%v", got)
	}
	if r := c.call("system.nope", ""); r["ok"] != false {
		t.Fatalf("不认识的方法：%v", r)
	}
}
