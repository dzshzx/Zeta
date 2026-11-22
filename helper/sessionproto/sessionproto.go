// Package sessionproto 是核心的会话方法（sessions.*、conv.occupant、conv.handoff）里客户端也要用的类型、常量和规则：
// 导出文件名、标题怎么清理、接管的结果与状态、错误代号。只放类型和纯函数，不依赖核心的其余部分，
// 核心（helper 的 package main、helper/conv）和 Go 写的客户端（windows/）都导入它，不各写一份。协议见 docs/core.md「会话」。
package sessionproto

import (
	"regexp"
	"strings"
	"time"
)

// 客户端要按原因分别处理的错误代号（zeta_reply 的 code）。
const (
	// CodeNoSession：这个会话号没有会话记录（会话在别处被删了），客户端把它从列表里拿掉
	CodeNoSession = "no_session"
	// CodeActive：sessions.delete 要删的会话正在进行，没删
	CodeActive = "active"
)

// TitleMax 是会话标题最多几个字（按字符数）。
const TitleMax = 200

// CleanTitle 是写进会话记录的标题：空白（含换行）压成一个空格、去掉首尾空白，最多 TitleMax 个字。
// 结果是空串时不能用作标题。
func CleanTitle(title string) string { return runePrefix(squash(title), TitleMax) }

// ExportTitleMax 是导出文件名里的标题最多几个字。
const ExportTitleMax = 40

// fileNameUnsafe 是文件名里不能用的字符：Windows 的保留字符和控制字符。
var fileNameUnsafe = regexp.MustCompile(`[\\/:*?"<>|\p{C}]`)

// ExportFileName 是导出文件的建议名字，照 Eta：Zeta-<标题>-<年月日>-<时分>.md，时刻照 at 自己的时区。
// 标题压掉多余空白、去掉文件名里不能用的字符，最多 ExportTitleMax 个字；空了是「对话」。
func ExportFileName(title string, at time.Time) string {
	clean := strings.TrimSpace(fileNameUnsafe.ReplaceAllString(squash(title), ""))
	clean = strings.TrimSpace(runePrefix(clean, ExportTitleMax))
	if clean == "" {
		clean = "对话"
	}
	return "Zeta-" + clean + "-" + at.Format("20060102-1504") + ".md"
}

// AtOffset 是 t 换到比 UTC 快 minutes 分钟的时区（sessions.export 的 utc_offset）。
func AtOffset(t time.Time, minutes int) time.Time {
	return t.In(time.FixedZone("", minutes*60))
}

// Claude Code 在 ~/.claude/sessions 登记的忙闲（Occupancy.Status），和接管不成时的 stuck。
const (
	StatusIdle    = "idle"    // 闲着
	StatusWaiting = "waiting" // 在等确认
	StatusBusy    = "busy"    // 正在跑一轮
	StatusShell   = "shell"   // 在跑 ! 命令
	StatusUnknown = "unknown" // 旧版 Claude Code 不报忙闲
	StatusStuck   = "stuck"   // 接管时发了中断，到时候还没退出
)

// HandsOver：别处的进程在状态 status 时能交给 Zeta：闲着、在等确认，或看不出忙闲（旧版）。
func HandsOver(status string) bool {
	return status == StatusIdle || status == StatusWaiting || status == StatusUnknown
}

// Occupancy 是 Zeta 之外占着一个会话的 Claude Code 进程的样子（终端里开着的，或另一个包托管的），「别处运行」卡片照它说话。
// conv.occupant、sessions.occupant 的 result。
type Occupancy struct {
	// Status：没有别处的进程占着时为 null；有时是 StatusIdle、StatusWaiting、StatusBusy、StatusShell，
	// 旧版 Claude Code 不报状态时是 StatusUnknown
	Status *string `json:"status"`
	// WaitingFor：在等确认时等的是什么（Claude Code 报了才有）
	WaitingFor *string `json:"waiting_for"`
	// HandsOver：现在能交给 Zeta（见 HandsOver）
	HandsOver bool `json:"hands_over"`
}

// Handoff 是把会话从别处接过来的结果：conv.handoff、sessions.handoff 的 result。
type Handoff struct {
	// Handed：交出来了（没人占着也算）
	Handed bool `json:"handed"`
	// Status：没交出来时那边的状态（同 Occupancy.Status），发了中断还不退的是 StatusStuck
	Status *string `json:"status"`
}

// squash 把连续的空白（含换行）折成一个空格，去掉首尾空白。
func squash(s string) string { return strings.Join(strings.Fields(s), " ") }

// runePrefix 是 s 的前 n 个字符（按 rune 数）。
func runePrefix(s string, n int) string {
	count := 0
	for i := range s {
		if count == n {
			return s[:i]
		}
		count++
	}
	return s
}
