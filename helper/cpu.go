package main

import (
	"errors"
	"math"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/core"
)

// system.cpu：这台主机整机的 CPU 忙碌百分比，聊天页输入栏上的负载标识隔几秒问一次（docs/core.md「主机」）。
// 算法和 Claude Code 状态栏常用的 cpu% 一样：两次读 /proc/stat 汇总行，忙碌占比 = 1 − 空闲增量 / 总增量（空闲含 iowait）。
// 上一次的读数放在核心的内存里，几个客户端共用（CPU 是整机的）；离上一次不到 cpuMinWindow 直接答上一次的数，
// 没有上一次、或上一次太旧（用户走开过）时就地采样 cpuSample（花的是延迟，不是 CPU）。
const (
	cpuMinWindow = 500 * time.Millisecond
	cpuMaxAge    = 5 * time.Minute
	cpuSample    = 100 * time.Millisecond
)

// cpuTimes 是 /proc/stat 汇总行里的（空闲，总计）jiffies。
type cpuTimes struct{ idle, total int64 }

// readCPUTimes 读 stat 文件第一行：空闲含 iowait；总计是 user 到 steal 八项之和（guest 时间已经算在 user、nice 里）。
func readCPUTimes(stat string) (cpuTimes, error) {
	data, err := os.ReadFile(stat)
	if err != nil {
		return cpuTimes{}, err
	}
	line, _, _ := strings.Cut(string(data), "\n")
	fields := strings.Fields(line)
	if len(fields) < 9 || fields[0] != "cpu" {
		return cpuTimes{}, errors.New("no cpu line in " + stat)
	}
	var values [8]int64
	var total int64
	for i := range values {
		if values[i], err = strconv.ParseInt(fields[i+1], 10, 64); err != nil {
			return cpuTimes{}, err
		}
		total += values[i]
	}
	return cpuTimes{idle: values[3] + values[4], total: total}, nil
}

// cpuPct 是两次读数之间的忙碌占比，夹在 0 到 100，逢五取偶；计数器没前进（或倒退）时 ok 为 false。
func cpuPct(prev, cur cpuTimes) (pct int, ok bool) {
	total := cur.total - prev.total
	if total <= 0 {
		return 0, false
	}
	busy := total - (cur.idle - prev.idle)
	return max(0, min(100, int(math.RoundToEven(100*float64(busy)/float64(total))))), true
}

// cpuMeter 记着上一次的读数。read、now、sleep 测试里换掉。
type cpuMeter struct {
	read  func() (cpuTimes, error)
	now   func() time.Time
	sleep func(time.Duration)

	mu   sync.Mutex
	at   time.Time // 上一次读的时刻，零值是还没读过
	last cpuTimes
	pct  int
	ok   bool // pct 算出来了
}

func newCPUMeter() *cpuMeter {
	return &cpuMeter{
		read:  func() (cpuTimes, error) { return readCPUTimes("/proc/stat") },
		now:   time.Now,
		sleep: time.Sleep,
	}
}

// percent 给出整机 CPU 忙碌百分比；读不了 /proc/stat 时 ok 为 false。几个客户端同时问时排队，就地采样的那 0.1 秒只等一次。
func (m *cpuMeter) percent() (pct int, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	age := now.Sub(m.at)
	if !m.at.IsZero() && age >= 0 && age < cpuMinWindow && m.ok {
		return m.pct, true
	}
	cur, err := m.read()
	if err != nil {
		return 0, false
	}
	if !m.at.IsZero() && age >= cpuMinWindow && age <= cpuMaxAge {
		pct, ok = cpuPct(m.last, cur)
	}
	if !ok {
		prev := cur
		m.sleep(cpuSample)
		if cur, err = m.read(); err != nil {
			return 0, false
		}
		pct, ok = cpuPct(prev, cur)
		now = now.Add(cpuSample)
	}
	m.at, m.last, m.pct, m.ok = now, cur, pct, ok
	return pct, ok
}

// cpuReply 是 system.cpu 的结果：percent 读不了时为 null；cores 是这个进程能用的逻辑核数。
type cpuReply struct {
	Percent *int `json:"percent"`
	Cores   int  `json:"cores"`
}

// systemHandler 做 system.*（现在只有 system.cpu）。就地采样要等 0.1 秒，挂在 HandleAsync 上。
type systemHandler struct{ cpu *cpuMeter }

func (h systemHandler) Request(_ *core.Client, r core.Request) (any, error) {
	if r.Method != "system.cpu" {
		return nil, errors.New("unknown method: " + r.Method)
	}
	reply := cpuReply{Cores: runtime.NumCPU()}
	if pct, ok := h.cpu.percent(); ok {
		reply.Percent = &pct
	}
	return reply, nil
}

func (systemHandler) Gone(*core.Client) {}
func (systemHandler) Idle() bool        { return true }
