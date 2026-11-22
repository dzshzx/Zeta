package conv

import (
	"bytes"
	"errors"
	"time"

	"github.com/dzshzx/Zeta/helper/tasks"
)

// 后台命令的输出页：客户端发 conv.task_output {task, watch} 开始或停止看一个后台任务的输出，核心在有人看着时
// 隔 taskOutputPoll 问一次 Claude Code（get_task_output），输出变了才推 conv.task_output。任务结束后再读一次就不再问。
// 不带 watch 的 conv.task_output 照旧只读一次（taskOutput）。

// taskOutputPoll 是有人看着时多久问一次输出；测试里调快。
var taskOutputPoll = time.Second

// taskOutputWatch 是一个后台任务的输出和看着它的客户端。
type taskOutputWatch struct {
	watchers map[int]bool
	last     []byte // 上次推出去的输出（编好的 JSON）；还没读到时为 nil
	output   tasks.Output
	reading  bool        // 在等 get_task_output 的应答
	timer    *time.Timer // 下一次读
	settled  bool        // 任务结束后已经读过最后一次
}

// taskOutputMsg 是 conv.task_output 推送。
type taskOutputMsg struct {
	Type string `json:"type"`
	Conv string `json:"conv"`
	Task string `json:"task"`
	tasks.Output
}

// watchTaskOutput：客户端 client 开始（watch）或停止看后台任务 id 的输出。开始时有读到过的先推一份，再马上读一次。
func (c *Conversation) watchTaskOutput(client int, id string, watch bool) error {
	if id == "" {
		return errors.New("缺 task")
	}
	w := c.taskOutputs[id]
	if !watch {
		if w != nil {
			c.unwatchTaskOutput(id, w, client)
		}
		return nil
	}
	if w == nil {
		if c.taskOutputs == nil {
			c.taskOutputs = map[string]*taskOutputWatch{}
		}
		w = &taskOutputWatch{watchers: map[int]bool{}}
		c.taskOutputs[id] = w
	}
	w.watchers[client] = true
	if w.last != nil {
		c.sendTaskOutput(client, id, w.output)
	}
	if !w.settled && w.timer != nil {
		w.timer.Stop()
		w.timer = nil
	}
	c.readTaskOutput(id, w)
	return nil
}

func (c *Conversation) unwatchTaskOutput(id string, w *taskOutputWatch, client int) {
	delete(w.watchers, client)
	if len(w.watchers) > 0 {
		return
	}
	if w.timer != nil {
		w.timer.Stop()
	}
	delete(c.taskOutputs, id)
}

// unwatchTaskOutputs：客户端 client 放开了这个对话（或断开了），它看着的输出都不再推给它。
func (c *Conversation) unwatchTaskOutputs(client int) {
	for id, w := range c.taskOutputs {
		c.unwatchTaskOutput(id, w, client)
	}
}

// taskRunning：后台任务 id 还在跑。
func (c *Conversation) taskRunning(id string) bool {
	for _, t := range c.tasks.Tasks() {
		if t.ID == id {
			return t.State == tasks.StateRunning
		}
	}
	return false
}

// readTaskOutput 问一次任务 id 的输出（已经在问、已经读过最后一次时不问），应答到了变了才推，再定下一次。
// 没有进程时读不了：还没推过就推一份说明原因的。
func (c *Conversation) readTaskOutput(id string, w *taskOutputWatch) {
	if w.reading || w.settled || c.taskOutputs[id] != w {
		return
	}
	p := c.proc
	if p == nil {
		w.settled = true
		if w.last == nil {
			c.publishTaskOutput(id, w, tasks.Output{Error: errNoProcess.Error()})
		}
		return
	}
	running := c.taskRunning(id)
	w.reading = true
	p.request(control("get_task_output", "task_id", id), func(r controlResponse) {
		w.reading = false
		o := tasks.ParseOutput(raw(r))
		o.Text = tasks.PlainTerminalText(o.Text)
		c.publishTaskOutput(id, w, o)
		if !running {
			w.settled = true
			return
		}
		c.scheduleTaskOutput(id, w)
	})
}

func (c *Conversation) scheduleTaskOutput(id string, w *taskOutputWatch) {
	if c.taskOutputs[id] != w {
		return
	}
	var t *time.Timer
	t = time.AfterFunc(taskOutputPoll, func() {
		c.do(func() {
			if w.timer != t {
				return
			}
			w.timer = nil
			c.readTaskOutput(id, w)
		})
	})
	w.timer = t
}

// publishTaskOutput 把读到的输出 o 推给看着的客户端；和上次推的一样时不推。
func (c *Conversation) publishTaskOutput(id string, w *taskOutputWatch, o tasks.Output) {
	encoded := raw(o)
	if bytes.Equal(encoded, w.last) {
		return
	}
	w.last, w.output = encoded, o
	for client := range w.watchers {
		c.sendTaskOutput(client, id, o)
	}
}

func (c *Conversation) sendTaskOutput(client int, id string, o tasks.Output) {
	if name, ok := c.holders[client]; ok {
		c.send(client, &taskOutputMsg{Type: "conv.task_output", Conv: name, Task: id, Output: o})
	}
}

// taskOutputsProcessEnded：进程没了，还在等的应答不会来了，看着的输出不再读（任务跟着进程没了）。
func (c *Conversation) taskOutputsProcessEnded() {
	for _, w := range c.taskOutputs {
		if w.timer != nil {
			w.timer.Stop()
			w.timer = nil
		}
		w.reading, w.settled = false, true
	}
}
