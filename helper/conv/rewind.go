package conv

// 编辑、重新生成、删除一轮、还原代码：都靠 Claude Code 自己的两个控制请求。
//
//   - rewind_conversation：给一条用户消息的 uuid，Claude Code 把会话剪到这条消息之前，连同它一起丢掉之后的全部内容。
//     会话号不变，记录文件只追加一行剪切记号，和终端里的 /rewind 一样；剪到第一条之前也行。剪不成时照样答 success，
//     看 rewound 和 reason。编辑、重新生成是剪完再发新消息，删除一轮是只剪不发。
//   - rewind_files：把文件改回某条用户消息之前的样子（终端 /rewind 的 Restore code）。快照要起进程时打开（codeRewindEnv）；
//     dry_run 只报会动哪些文件、加减几行。
//
// 剪的期间（rewind 不为空）不收新消息：这时发出的消息可能被 Claude Code 算在剪之前。进程退出、对话关闭时还在等的按失败收掉。

import (
	"cmp"
	"encoding/json"
	"errors"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/transcript"
)

var (
	errRewinding  = errors.New("正在等 Claude Code 剪会话，等它剪完再发")
	errNotNow     = errors.New("现在不能改：等这一轮结束、回应完等着的请求再试")
	errNoTarget   = errors.New("找不到这条消息")
	errUnknownMsg = errors.New("这条消息 Claude Code 不认得（还没有 uuid），改不了")
)

// pendingRewind 是在等 Claude Code 应答的一次剪会话或还原代码：答应了做 done，没答应做 failed。
type pendingRewind struct {
	done   func()
	failed func(reason string)
}

// canRewind：现在能编辑、重新生成、删除一轮、还原代码或分支。这一轮已经结束，也没有别的事在等。
func (c *Conversation) canRewind() bool {
	return !c.busy && len(c.prompts) == 0 && c.rewind == nil && !c.loadingHistory && !c.blocked && len(c.queued) == 0
}

// rewindTarget 是条目 id 为 target 的那条用户消息在列表里的位置和 uuid；现在不能改、找不到或它没有 uuid 时报错。
func (c *Conversation) rewindTarget(target string) (int, string, error) {
	if !c.canRewind() {
		return 0, "", errNotNow
	}
	i := slices.IndexFunc(c.r.Items, func(it *transcript.Item) bool { return it.ID == target })
	switch {
	case i < 0 || !isUser(c.r.Items[i]):
		return 0, "", errNoTarget
	case !rewindable(c.r.Items[i]):
		return 0, "", errUnknownMsg
	}
	return i, c.r.Items[i].UUID, nil
}

// rewrite 编辑或重新生成：丢掉 target 这条用户消息和之后的内容，从那里用新消息接着跑，会话号不变。
// 先请 Claude Code 剪会话，它答应了才把消息交出去；没答应就恢复原样，新消息放回输入框。
func (c *Conversation) rewrite(client int, target string, m Message) error {
	if m.empty() {
		return nil
	}
	text := strings.TrimSpace(m.Text)
	i, uuid, err := c.rewindTarget(target)
	if err != nil {
		return err
	}
	c.startedBy(client)
	p := c.authorize("", nil)
	if p == nil {
		return nil
	}
	before := slices.Clone(c.r.Items)
	lastSeen := c.lastSeen(before)
	c.r.Items = c.r.Items[:i]
	msg := c.stage(text, m.Images, m.Files)
	c.startRewind(p, rewindConversation(uuid, lastSeen), rewindFailure,
		func() {
			// 在还没发过消息的分支里改写：这一刀已经把分支要剪的部分一起剪掉了
			c.forkCutDone(p)
			c.transmit(p, msg)
		},
		func(reason string) {
			c.r.Items = before
			c.abandon(msg, reason)
		})
	return nil
}

// drop 删除 target 开始的这一轮和之后的内容（只剪不发）。
func (c *Conversation) drop(client int, target string) error {
	i, uuid, err := c.rewindTarget(target)
	if err != nil {
		return err
	}
	c.startedBy(client)
	p := c.ensureProcess(c.modes.forUser())
	if p == nil {
		return nil
	}
	before := slices.Clone(c.r.Items)
	lastSeen := c.lastSeen(before)
	c.r.Items = c.r.Items[:i]
	c.changed()
	c.startRewind(p, rewindConversation(uuid, lastSeen), rewindFailure,
		func() { c.forkCutDone(p) },
		func(reason string) {
			c.r.Items = before
			c.r.AddNotice("没有删除："+reason, true)
		})
	return nil
}

// codeChanges 是还原到 target 之前会动到的文件和加减行数（只看不改）；应答到了从返回的通道里给出来，
// 没有可还原的、现在不能还原时给 nil。
func (c *Conversation) codeChanges(client int, target string) <-chan answer {
	out := make(chan answer, 1)
	_, uuid, err := c.rewindTarget(target)
	if err != nil {
		out <- answer{}
		return out
	}
	c.startedBy(client)
	p := c.ensureProcess(c.modes.forUser())
	if p == nil {
		out <- answer{}
		return out
	}
	p.request(rewindFiles(uuid, true), func(r controlResponse) {
		if ch := dryRunChanges(r); ch != nil {
			out <- answer{v: ch}
			return
		}
		out <- answer{}
	})
	return out
}

// restoreCode 把文件改回 target 这条消息之前的样子。还原成了再接着做：带了新消息 m 时照 rewrite 改写重发，
// 否则 drop 时删这一轮。先还原代码再剪会话：剪掉以后 Claude Code 就找不到这条消息了。
// 没还原成就不剪，在列表里说明原因；带着的新消息放回输入框（recalled）。
func (c *Conversation) restoreCode(client int, target string, drop bool, m Message) error {
	_, uuid, err := c.rewindTarget(target)
	if err != nil {
		return err
	}
	c.startedBy(client)
	p := c.ensureProcess(c.modes.forUser())
	if p == nil {
		c.recall(m)
		return nil
	}
	// 和剪会话一样占着 rewind：期间不收新消息，进程退出、对话关闭时按失败收掉
	c.startRewind(p, rewindFiles(uuid, false), restoreFailure,
		func() {
			c.r.AddNotice("代码已还原到这条消息之前", false)
			switch {
			case !m.empty():
				if err := c.rewrite(client, target, m); err != nil {
					c.r.AddNotice("没有发出："+err.Error(), true)
					c.recall(m)
				}
			case drop:
				if err := c.drop(client, target); err != nil {
					c.r.AddNotice("没有删除："+err.Error(), true)
				}
			}
		},
		func(reason string) {
			c.r.AddNotice(reason, true)
			c.recall(m)
		})
	return nil
}

// recall：没能交出去的新消息放回输入框（还没开始这一轮，不推 conv.turn）。空消息什么也不做。
func (c *Conversation) recall(m Message) {
	if m.empty() {
		return
	}
	c.recalled = merge(c.recalled, &outgoing{uuid: newUUID(), text: strings.TrimSpace(m.Text), images: m.Images, files: m.Files})
	c.changed()
}

// abandon：没能发出的消息，这一轮作罢，文字和附件放回输入框。
func (c *Conversation) abandon(m *outgoing, reason string) {
	c.busy = false
	c.recalled = merge(c.recalled, m)
	c.r.AddNotice("没有发出："+reason, true)
	c.turnFinished(outcomeFailed)
	c.changed()
}

// startRewind 发出 body，等 Claude Code 应答；failure 从应答里读出没成的原因（成了为空）。
func (c *Conversation) startRewind(p *process, body any, failure func(controlResponse) string, done func(), failed func(reason string)) {
	pending := &pendingRewind{done: done, failed: failed}
	c.rewind = pending
	p.request(body, func(r controlResponse) { c.finishRewind(pending, failure(r)) })
	c.changed()
}

// finishRewind：pending 有了结果，reason 是没成的原因（成了为空）。已经按失败收掉的（进程退出、对话关闭）不再处理。
func (c *Conversation) finishRewind(pending *pendingRewind, reason string) {
	if pending == nil || pending != c.rewind {
		return
	}
	c.rewind = nil
	if reason == "" {
		pending.done()
	} else {
		pending.failed(reason)
	}
	c.changed()
	// 成了接着又起了一次（还原代码后接着删这一轮）时，等那一次也剪完
	if c.rewind == nil {
		c.afterRewind()
	}
}

// abortRewind：进程没了，还在等的剪切按失败收掉，原因是 reason。
func (c *Conversation) abortRewind(reason string) {
	c.finishRewind(c.rewind, reason)
}

// afterRewind 是剪完以后要做的事：剪的期间读回来的更早一页（见 conv.earlier），列表已经是剪完（或恢复）的样子，现在接上。
func (c *Conversation) afterRewind() {
	if f := c.deferredEarlier; f != nil {
		c.deferredEarlier = nil
		f()
	}
}

// lastSeen 是剪的时候报给 Claude Code 的最后一条用户消息：它据此确认界面没有漏看别处新加的轮次。
// 还没剪过的分支，进程里是原会话的全部内容，要报原会话的。
func (c *Conversation) lastSeen(items []*transcript.Item) string {
	if c.forkPending() {
		return c.fork.lastSeen
	}
	return lastSeenUser(items)
}

// lastSeenUser 是列表里最后一条有 uuid 的用户消息。
func lastSeenUser(items []*transcript.Item) string {
	for _, it := range slices.Backward(items) {
		if it.Kind == transcript.KindUser && it.UUID != "" {
			return it.UUID
		}
	}
	return ""
}

// rewindConversationRequest 是 rewind_conversation 控制请求。
type rewindConversationRequest struct {
	Subtype  string `json:"subtype"`
	Target   string `json:"target_message_uuid"`
	LastSeen string `json:"last_seen_user_message_uuid,omitempty"`
}

func rewindConversation(target, lastSeen string) rewindConversationRequest {
	return rewindConversationRequest{Subtype: "rewind_conversation", Target: target, LastSeen: lastSeen}
}

// rewindFilesRequest 是 rewind_files 控制请求。
type rewindFilesRequest struct {
	Subtype       string `json:"subtype"`
	UserMessageID string `json:"user_message_id"`
	DryRun        bool   `json:"dry_run,omitempty"`
}

func rewindFiles(target string, dryRun bool) rewindFilesRequest {
	return rewindFilesRequest{Subtype: "rewind_files", UserMessageID: target, DryRun: dryRun}
}

// rewindAnswer 是 rewind_conversation 应答的 response 一项。
type rewindAnswer struct {
	Rewound bool   `json:"rewound"`
	Reason  string `json:"reason"`
	Error   string `json:"error"`
}

// rewindFailure 读 rewind_conversation 的应答：没剪成时是给用户看的原因，剪成了为空。
func rewindFailure(r controlResponse) string {
	if !r.ok() {
		return "Claude Code 拒绝了这次操作：" + cmp.Or(r.Error, "未知错误")
	}
	var a rewindAnswer
	if !decodeAnswer(r, &a) {
		return "Claude Code 没有说剪没剪"
	}
	if a.Rewound {
		return ""
	}
	switch a.Reason {
	case "turn_running", "commands_queued", "prompt_pending":
		return "Claude 还在处理消息，等这一轮结束再试"
	case "target_not_found":
		return "会话记录里找不到这条消息（上下文压缩之前的消息改不了）"
	case "stale_target", "unseen_later_turn", "state_changed":
		return "这个会话在别处有了新内容，重新打开它再试"
	case "target_splits_tool_call", "poll_tool_result_target":
		return "这条消息在工具调用中间，不能从这里改"
	case "persist_failed":
		return "写不进会话记录"
	}
	return "Claude Code 没有剪：" + cmp.Or(a.Error, a.Reason, "未知原因")
}

// filesAnswer 是 rewind_files 应答的 response 一项。
type filesAnswer struct {
	CanRewind    bool     `json:"canRewind"`
	Error        string   `json:"error"`
	FilesChanged []string `json:"filesChanged"`
	Insertions   int      `json:"insertions"`
	Deletions    int      `json:"deletions"`
}

// CodeChanges 是还原代码会动到的：文件、加减行数（conv.code_changes 的 result）。
type CodeChanges struct {
	Files      []string `json:"files"`
	Insertions int      `json:"insertions"`
	Deletions  int      `json:"deletions"`
}

// dryRunChanges 读 dry_run 的应答：没有可还原的（没打开快照、这条消息之后没改过文件）为 nil。
func dryRunChanges(r controlResponse) *CodeChanges {
	var a filesAnswer
	if !r.ok() || !decodeAnswer(r, &a) || !a.CanRewind || len(a.FilesChanged) == 0 {
		return nil
	}
	return &CodeChanges{Files: a.FilesChanged, Insertions: a.Insertions, Deletions: a.Deletions}
}

// restoreFailure 读真还原的应答：没还原成时是给用户看的原因，成了为空。
func restoreFailure(r controlResponse) string {
	if !r.ok() {
		return "Claude Code 拒绝了：" + cmp.Or(r.Error, "未知错误")
	}
	var a filesAnswer
	if !decodeAnswer(r, &a) {
		return "Claude Code 没有说还原没还原"
	}
	if a.CanRewind {
		return ""
	}
	return "没有还原代码：" + cmp.Or(a.Error, "没有这条消息之前的快照")
}

// decodeAnswer 把应答的 response 一项解进 v；没有这一项或解不开时返回 false。
func decodeAnswer(r controlResponse, v any) bool {
	return len(r.Response) > 0 && string(r.Response) != "null" && json.Unmarshal(r.Response, v) == nil
}
