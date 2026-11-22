package conv

import "github.com/dzshzx/Zeta/helper/transcript"

// 各轮能做什么：用户消息条目上的 rewindable、forkable、round_last（见 docs/core.md 的 conv.items）。
// 和 conv.rewrite、conv.fork 执行时的核对（rewindTarget、branchEnd）用同一套判断，只是提前算好，客户端照着显示按钮，不再自己分轮。
// 一轮从一条用户消息起，到下一条用户消息之前；第一条用户消息之前的内容不属于任何一轮。

// isUser：这一项是用户消息，一轮从它开始。
func isUser(it *transcript.Item) bool { return it.Kind == transcript.KindUser }

// rewindable：Claude Code 认得这条用户消息（有 uuid），能从它剪会话、还原代码。
func rewindable(it *transcript.Item) bool { return isUser(it) && it.UUID != "" }

// cuttableAt：从下标 end 之前分支时剪得准：end 是列表末尾（最后一轮整个复制），或那里的用户消息 Claude Code 认得。
func cuttableAt(items []*transcript.Item, end int) bool {
	return end == len(items) || items[end].UUID != ""
}

// roundLast 是一轮里操作行挂的那一项：最后一项不是灰色提示的（回答、工作过程、出错提示）；只有灰色提示时为空。
// rest 是这一轮用户消息之后的各项。
func roundLast(rest []*transcript.Item) string {
	for i := len(rest) - 1; i >= 0; i-- {
		if it := rest[i]; it.Kind != transcript.KindNotice || it.Error {
			return it.ID
		}
	}
	return ""
}

// markRounds 给各条用户消息算好这一轮能做什么，变了的随下次推送出去。分支还要有会话号（forkPlan 的核对）。
func (c *Conversation) markRounds() {
	items := c.r.Items
	start := -1
	for i := 0; i <= len(items); i++ {
		if i < len(items) && !isUser(items[i]) {
			continue
		}
		if start >= 0 {
			items[start].SetRound(transcript.Round{
				Rewindable: rewindable(items[start]),
				Forkable:   c.session != "" && cuttableAt(items, i),
				Last:       roundLast(items[start+1 : i]),
			})
		}
		start = i
	}
}
