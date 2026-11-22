package hubtest

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
)

// ChatLink 是假的对话连接，扮主机上的 serve 和核心：记下发来的请求，按 OnCall 应答（没给时答成功、没有 result）；
// 推送、连接的变化由测试经 Push、Connect、Drop 送来，像 serve 那样在调用者的 goroutine 上交给中枢。
type ChatLink struct {
	Host string

	devices *Devices
	seq     int64
	mu      sync.Mutex
	calls   []Call
	onCall  func(Call) (json.RawMessage, error)
	push    func([]byte)
	problem func(string)
	closed  bool
}

// Call 是发来的一个请求。
type Call struct {
	Method string
	Params map[string]any
}

// Conv 是请求里的对话名。
func (c Call) Conv() string { s, _ := c.Params["conv"].(string); return s }

// Str 是参数 key 的字符串值。
func (c Call) Str(key string) string { s, _ := c.Params[key].(string); return s }

// JSON 是参数的 JSON，键按字母排好。
func (c Call) JSON() string {
	b, _ := json.Marshal(c.Params)
	return string(b)
}

// Call 见 host.ChatLink。
func (l *ChatLink) Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	return l.CallTagged(ctx, method, params, nil)
}

// CallTagged 见 host.ChatLink：请求依次编号。sessions.* 由假设备的会话方法答（sessions.go），不记进 Took。
func (l *ChatLink) CallTagged(_ context.Context, method string, params map[string]any, tag func(int64)) (json.RawMessage, error) {
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		return nil, errors.New("和 zeta-helper serve 的连接已关闭")
	}
	l.seq++
	id := l.seq
	if tag != nil {
		tag(id)
	}
	if strings.HasPrefix(method, "sessions.") {
		l.mu.Unlock()
		return l.devices.answerSessions(l, id, Call{Method: method, Params: params})
	}
	c := Call{Method: method, Params: params}
	l.calls = append(l.calls, c)
	onCall := l.onCall
	l.mu.Unlock()
	if onCall == nil {
		return nil, nil
	}
	return onCall(c)
}

// Close 见 host.ChatLink。
func (l *ChatLink) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.closed = true
	return nil
}

// Closed：关掉了。
func (l *ChatLink) Closed() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.closed
}

// OnCall 定怎么应答之后的请求：fn 返回 result 和错误；它可以先 Push 几行，像核心在应答之前推来的。
func (l *ChatLink) OnCall(fn func(Call) (json.RawMessage, error)) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.onCall = fn
}

// Took 取走到现在发来的请求。
func (l *ChatLink) Took() []Call {
	l.mu.Lock()
	defer l.mu.Unlock()
	c := l.calls
	l.calls = nil
	return c
}

// Methods 取走到现在发来的请求，只留方法名。
func (l *ChatLink) Methods() []string {
	var out []string
	for _, c := range l.Took() {
		out = append(out, c.Method)
	}
	return out
}

// Push 推来一行（serve 原样转来的核心推送）。
func (l *ChatLink) Push(line string) { l.push([]byte(line)) }

// Connect 报连上了（serve 收到招呼）；Drop 报断开了，why 是原因。
func (l *ChatLink) Connect()        { l.problem("") }
func (l *ChatLink) Drop(why string) { l.problem(why) }

// OpenChat 见 hub.Devices：起一条假连接，记下来，测试用 Chat 拿到它。
func (d *Devices) OpenChat(h sshlink.Host, push func([]byte), problem func(string)) host.ChatLink {
	l := &ChatLink{Host: h.ID, devices: d, push: push, problem: problem}
	d.mu.Lock()
	d.chats = append(d.chats, l)
	onOpen := d.OnChat
	d.mu.Unlock()
	if onOpen != nil {
		onOpen(l)
	}
	return l
}

// Chat 是设备 id 最近一条对话连接；没有时是 nil。
func (d *Devices) Chat(id string) *ChatLink {
	d.mu.Lock()
	defer d.mu.Unlock()
	for i := len(d.chats) - 1; i >= 0; i-- {
		if d.chats[i].Host == id {
			return d.chats[i]
		}
	}
	return nil
}

// Chats 是起过的对话连接数。
func (d *Devices) Chats() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.chats)
}

// CheckDir 见 hub.Devices：照 DirProblems 答（没列的进得去）。
func (d *Devices) CheckDir(_ sshlink.Host, path string) (string, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.checked = append(d.checked, path)
	return d.DirProblems[path], nil
}

// Checked 是查过的目录。
func (d *Devices) Checked() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.checked)
}

//go:embed recorded
var recorded embed.FS

// Recorded 是录好的一段协议消息（recorded/<name>.jsonl，从真的 serve 和核心录的，见 hub 的 TestConversationThroughCore），
// 对话名换成 conv。空行不算。
func Recorded(name, conv string) []string {
	data, err := recorded.ReadFile("recorded/" + name + ".jsonl")
	if err != nil {
		panic(err)
	}
	var out []string
	for _, line := range strings.Split(string(data), "\n") {
		if line != "" {
			out = append(out, strings.ReplaceAll(line, `"conv":"`+RecordedConv+`"`, `"conv":"`+conv+`"`))
		}
	}
	return out
}

// RecordedConv 是录的时候的对话名。
const RecordedConv = "w1"

// Upload 见 hub.Devices：不连设备，内容读进 Uploaded，路径照真的格式编（/home/<名字>/.cache/zeta/files/<序号>/<名字>）。
// UploadErr 给了时答它；UploadGate 给了时先等它关上（测试里看「正在传」的样子），ctx 取消时返回 ctx 的错误。
func (d *Devices) Upload(ctx context.Context, h sshlink.Host, u host.Upload, progress func(int64)) (string, error) {
	if d.UploadGate != nil {
		select {
		case <-d.UploadGate:
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}
	name, size := u.Name, int64(len(u.Data))
	if u.Local != "" {
		name = filepath.Base(u.Local)
		if st, err := os.Stat(u.Local); err == nil && !u.Folder {
			size = st.Size()
		}
	}
	if progress != nil {
		progress(size)
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.UploadErr != nil {
		return "", d.UploadErr
	}
	d.uploaded = append(d.uploaded, u)
	return fmt.Sprintf("%s/.cache/zeta/files/%08d/%s", h.Home, len(d.uploaded), hostlink.CleanName(name)), nil
}

// Uploaded 是传上去的东西，按传的先后。
func (d *Devices) Uploaded() []host.Upload {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.uploaded)
}

// CheckPath 见 hub.Devices：照 Paths 答，没列的是没有。
func (d *Devices) CheckPath(_ sshlink.Host, path string) (hostlink.PathKind, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if kind, ok := d.Paths[path]; ok {
		return kind, nil
	}
	return hostlink.PathMissing, nil
}
