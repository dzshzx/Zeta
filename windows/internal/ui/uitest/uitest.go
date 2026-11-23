// Package uitest 是界面测试共用的等待：界面接着真的后台（假 serve、核心、假 Claude）时，等它在真的时间里走到某个状态。只给测试用。
package uitest

import (
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// Until 执行交回主线程的函数（posts）、画帧，直到 done 为真；limit 内没等到就失败，打出界面上的文字。
//
// Tester 的时钟不拨不动，这里每一圈把它拨过真实流逝的时间：后台的东西按真的时间送来，界面上随时间走的
// （回答的逐字显现、淡入、Context.After）也得跟着走。不拨的话，一帧碰巧画在回答还在送来时，逐字显现就停在
// 头几个字，显现完才有的操作键（复制、重新生成）永远不出来。
func Until(t testing.TB, tt *ui.Tester, posts <-chan func(), what string, limit time.Duration, done func() bool) {
	t.Helper()
	deadline := time.After(limit)
	last := time.Now()
	for {
		now := time.Now()
		tt.Advance(now.Sub(last))
		last = now
		tt.Frame()
		if done() {
			return
		}
		select {
		case fn := <-posts:
			fn()
		case <-time.After(10 * time.Millisecond):
		case <-deadline:
			t.Fatalf("%v 内没等到%s；界面上：%q", limit, what, tt.Texts())
		}
	}
}
