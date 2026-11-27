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
	"github.com/dzshzx/Zeta/hub/host"
)

// ChatLink 是假的对话连接，扮主机上的 serve 和核心：记下发来的请求，按 OnCall 应答（没给时答成功、没有 result）；
// 推送、招呼、连接的变化由测试经 Push、Greet、Connect、Drop 送来，像 serve 那样在调用者的 goroutine 上交给中枢。
// 像 serve 那条连接上一行行写出那样，sessions.image 的应答没写完时，推送等着它（wire）。
type ChatLink struct {
	Host string

	devices *Devices
	wire    sync.Mutex
	mu      sync.Mutex
	calls   []Call
	onCall  func(Call) (json.RawMessage, error)
	push    func([]byte)
	hello   func(hostlink.Hello)
	problem func(string)
	closed  bool
	// pushed 是推过几行（在 wire 锁里加），看 conv.prepare 的应答里推没推东西。
	pushed int
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

// Strs 是参数 key 的字符串列表（客户端给的 []string）；没有时是 nil。
func (c Call) Strs(key string) []string {
	v, _ := c.Params[key].([]string)
	return v
}

// JSON 是参数的 JSON，键按字母排好。
func (c Call) JSON() string {
	b, _ := json.Marshal(c.Params)
	return string(b)
}

// Call 见 host.ChatLink。sessions.* 和 images.file 由假设备答（sessions.go），不记进 Took。
func (l *ChatLink) Call(_ context.Context, method string, params map[string]any) (json.RawMessage, error) {
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		return nil, errors.New("和 zeta-helper serve 的连接已关闭")
	}
	if strings.HasPrefix(method, "sessions.") || method == "images.file" {
		l.mu.Unlock()
		if method == "sessions.image" {
			l.wire.Lock()
			defer l.wire.Unlock()
		}
		return l.devices.answerSessions(l, Call{Method: method, Params: params})
	}
	c := Call{Method: method, Params: params}
	if method != "system.cpu" { // 输入栏的负载标识隔几秒问一次，不记，免得各测试核对的请求清单里混进它
		l.calls = append(l.calls, c)
	}
	onCall := l.onCall
	l.mu.Unlock()
	before := l.pushes()
	var result json.RawMessage
	var err error
	if onCall != nil {
		result, err = onCall(c)
	}
	if method == "conv.prepare" && err == nil && l.pushes() == before {
		// 测试没让它推东西：当作这台上起不来 Claude Code（同核心，条目里加一条「启动失败」），要模型清单的不用等到超时
		l.Push(`{"type":"conv.items","conv":"` + c.Conv() + `","full":true,"items":[{"kind":"notice","id":"n1","text":"启动失败：假设备上没有 claude",` +
			`"code":"noticeStartFailed","args":{"reason":"假设备上没有 claude"}}]}`)
	}
	return result, err
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
func (l *ChatLink) Push(line string) {
	l.wire.Lock()
	defer l.wire.Unlock()
	l.pushed++
	l.push([]byte(line))
}

// Greet 交来 serve 的招呼（像每次连上时那样），带主机名 hostname。
func (l *ChatLink) Greet(hostname string) {
	if l.hello != nil {
		l.hello(hostlink.Hello{Hostname: hostname})
	}
}

// Connect 报连上了（serve 收到招呼）；Drop 报断开了，why 是原因。
func (l *ChatLink) Connect()        { l.problem("") }
func (l *ChatLink) Drop(why string) { l.problem(why) }

// OpenChat 见 hub.Devices：起一条假连接，记下来，测试用 Chat 拿到它。
func (d *Devices) OpenChat(h sshlink.Host, push func([]byte), hello func(hostlink.Hello), problem func(string)) host.ChatLink {
	l := &ChatLink{Host: h.ID, devices: d, push: push, hello: hello, problem: problem}
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

// OpenBulk 见 hub.Devices：起一条假连接，只答 sessions.*，不收推送；记下来，测试用 Bulks 拿到。
func (d *Devices) OpenBulk(h sshlink.Host) host.ChatLink {
	l := &ChatLink{Host: h.ID, devices: d, push: func([]byte) {}, problem: func(string) {}}
	d.mu.Lock()
	defer d.mu.Unlock()
	d.bulks = append(d.bulks, l)
	return l
}

// Bulks 是起过的取大块数据的连接，按先后。
func (d *Devices) Bulks() []*ChatLink {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.bulks)
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

// RemoveUploaded 见 hub.Devices：不连设备，只把路径记进 Removed。
func (d *Devices) RemoveUploaded(_ sshlink.Host, path string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.removed = append(d.removed, path)
	if d.removedMore != nil {
		d.removedMore.Broadcast()
	}
}

// Removed 是叫删掉的已传附件的路径，按先后。
func (d *Devices) Removed() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.removed)
}

// WaitRemoved 等到叫删掉的已传附件有 n 个，返回它们的路径（删是在后台做的）。
func (d *Devices) WaitRemoved(n int) []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.removedMore == nil {
		d.removedMore = sync.NewCond(&d.mu)
	}
	for len(d.removed) < n {
		d.removedMore.Wait()
	}
	return slices.Clone(d.removed)
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

// pushes 是到现在推过几行。
func (l *ChatLink) pushes() int {
	l.wire.Lock()
	defer l.wire.Unlock()
	return l.pushed
}
