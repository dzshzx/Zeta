package main

import (
	"os"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

// sessionWatch 是 serve 里会话列表的订阅：和 list 同样的范围、同样的每一项（最新的 listLimit 个会话，加上不在里面的置顶会话，见 listedFiles），
// 先发整份，之后只发变了的。
//
// 发给客户端的一行：{"type":"zeta_sessions","full":<是否整份>,"upsert":[<项>…],"remove":[<id>…]}。
// 整份时 upsert 是全部、按时间从新到旧，remove 为空；不是整份时 upsert 是新出现或有字段变了的项，
// remove 是不再列出的（删了、挤出了最新的 listLimit 个又没置顶）。同一个 id 只算最新的那一份记录。
//
// 什么时候重读：用 inotify 盯着会话目录（~/.claude/projects 和其下各项目）、Claude Code 的进程登记
// （~/.claude/sessions，决定 external）和归档、置顶文件所在的目录；任何变化之后等 debounce 再重读，其间的变化合成一次。
// 要盯的目录还不在时盯它最近的、已有的上级目录，等它建出来；inotify 用不了时每 poll 重读一次。
// 进程被杀时登记文件常常留着（目录不变），所以盯得上时也每 poll 核对一次登记的进程还在不在（live），变了才重读。
// 重读时按文件大小和修改时刻认出没变的记录，只重新读变了的那几份的头尾（summarize）。
//
// 侧栏的写操作（改名、归档、置顶、删除）写完由 force 叫它马上重读，不等 debounce，那几项不管和上次发的同不同都发：
// 不在列表里的发 remove。两个客户端在一次推送之前先后改同一项时，先改的那边界面上已经是自己改的样子，
// 只发差异的话它收不到改回去的这一项。
type sessionWatch struct {
	e     *env
	opts  watchOptions
	send  func(any) error
	again chan struct{} // 客户端又要一份整份的
	now   chan struct{} // 有 force 的项要马上发
	stop  chan struct{}
	done  chan struct{}

	mu     sync.Mutex
	forced map[string]bool // 下次发的时候不管变没变都要发的 id

	// 下面的只在 run 的 goroutine 里碰
	notify  *os.File          // inotify；用不了时是 nil
	watched map[string]watch  // 正盯着的目录
	cache   map[string]cached // 记录文件 → 上次读到的概要
	shown   map[string]string // 上次发出去的：id → 那一项的 JSON
	blind   bool              // 有要盯的目录盯不上（没有已有的上级、inotify 加不上）：靠定时重读
	live    string            // 上次重读时有活着的进程的会话（liveKey）
}

type watch struct {
	wd   int32
	mask uint32
}

type cached struct {
	size  int64
	mtime int64
	s     summary
	ok    bool
}

const (
	// 盯着的目录里有东西建、删、改、挪、改时间都算变化（Chtimes、touch 只改属性）
	fullMask = syscall.IN_CREATE | syscall.IN_DELETE | syscall.IN_MODIFY | syscall.IN_ATTRIB |
		syscall.IN_MOVED_FROM | syscall.IN_MOVED_TO | syscall.IN_DELETE_SELF | syscall.IN_MOVE_SELF
	// 等目录建出来时，上级目录只看建和挪进来（家目录这类里的别的文件天天在改，不必醒）
	parentMask = syscall.IN_CREATE | syscall.IN_MOVED_TO | syscall.IN_DELETE_SELF | syscall.IN_MOVE_SELF
)

func startSessionWatch(e *env, opts watchOptions, send func(any) error) *sessionWatch {
	w := &sessionWatch{
		e: e, opts: opts, send: send,
		again: make(chan struct{}, 1), now: make(chan struct{}, 1), stop: make(chan struct{}), done: make(chan struct{}),
		watched: map[string]watch{}, cache: map[string]cached{},
	}
	go w.run()
	return w
}

func (w *sessionWatch) resend() { wake(w.again) }

// force 让 ids 这几项马上发一次，不管和上次发的同不同（见 sessionWatch）。
func (w *sessionWatch) force(ids []string) {
	w.mu.Lock()
	if w.forced == nil {
		w.forced = map[string]bool{}
	}
	for _, id := range ids {
		w.forced[id] = true
	}
	w.mu.Unlock()
	wake(w.now)
}

// takeForced 取走 force 记下的 id。
func (w *sessionWatch) takeForced() map[string]bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	forced := w.forced
	w.forced = nil
	return forced
}

// wake 往 ch 放一个信号；已经有一个没取走时不再放。
func wake(ch chan<- struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

func (w *sessionWatch) close() {
	close(w.stop)
	<-w.done
}

func (w *sessionWatch) run() {
	defer close(w.done)
	events := w.open()
	defer func() {
		if w.notify != nil {
			_ = w.notify.Close()
		}
	}()
	// 先盯上再读，读的时候改了的也不会漏
	w.ensureWatches()
	if w.publish(true) != nil {
		return
	}
	poll := time.NewTicker(w.opts.poll)
	defer poll.Stop()
	var settle <-chan time.Time
	for {
		select {
		case <-w.stop:
			return
		case ok := <-events:
			if !ok {
				events = nil
				w.blind = true
			}
			if settle == nil {
				settle = time.After(w.opts.debounce)
			}
			continue
		case <-settle:
			settle = nil
		case <-w.now:
			// 等着的 settle 留着：到时再看一遍，没有新的差异就不发
		case <-poll.C:
			if !w.blind && liveKey(w.e.liveSessions()) == w.live {
				continue
			}
		case <-w.again:
			w.ensureWatches()
			if w.publish(true) != nil {
				return
			}
			continue
		}
		w.ensureWatches()
		if w.publish(false) != nil {
			return
		}
	}
}

// open 建 inotify，返回它的变化通知（读不了时关掉）；建不起来时是 nil，只靠定时重读。
func (w *sessionWatch) open() <-chan bool {
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC | syscall.IN_NONBLOCK)
	if err != nil {
		w.blind = true
		return nil
	}
	// 非阻塞的文件描述符交给 os.File 后由 Go 的轮询器等，Close 能叫醒正等着的 Read
	w.notify = os.NewFile(uintptr(fd), "inotify")
	events := make(chan bool, 1)
	go w.readEvents(events)
	return events
}

// readEvents 读 inotify：有变化就往 events 放一个（放不下说明还没处理，不必再放），读不了时关掉 events。
// 被系统撤掉的盯法（目录删了，IN_IGNORED）由 ensureWatches 按目录在不在重新核对，这里不记。
func (w *sessionWatch) readEvents(events chan<- bool) {
	defer close(events)
	buf := make([]byte, 64*1024)
	for {
		n, err := w.notify.Read(buf)
		if err != nil {
			return
		}
		if n < syscall.SizeofInotifyEvent {
			continue
		}
		select {
		case events <- true:
		default:
		}
	}
}

// wantedDirs 是要盯的目录和各自的盯法：在的目录整个盯；不在的盯它最近的已有上级，等它建出来。
// 找不到已有上级的（家目录本身都没有）记进 blind。
func (w *sessionWatch) wantedDirs() map[string]uint32 {
	e := w.e
	dirs := []string{e.root, e.sessions, dirName(e.archive)}
	for _, project := range globNames(e.root, true, nil) {
		dirs = append(dirs, joinPath(e.root, project))
	}
	want := map[string]uint32{}
	for _, d := range dirs {
		if isDirFollow(d) {
			want[d] = fullMask
			continue
		}
		parent := existingParent(d, e.home)
		if parent == "" {
			w.blind = true
			continue
		}
		if _, ok := want[parent]; !ok {
			want[parent] = parentMask
		}
	}
	return want
}

// existingParent 是 p 最近的、已经在的上级目录，不出 home；都不在时为空串。
func existingParent(p, home string) string {
	for {
		up := dirName(p)
		if up == "" || up == p || (home != "" && !strings.HasPrefix(up+"/", home+"/")) {
			return ""
		}
		if isDirFollow(up) {
			return up
		}
		p = up
	}
}

// ensureWatches 让 inotify 盯着的目录和 wantedDirs 对上：新出现的加上，不再要的撤掉，盯法变了的换掉。
func (w *sessionWatch) ensureWatches() {
	if w.notify == nil {
		w.blind = true
		return
	}
	w.blind = false
	want := w.wantedDirs()
	fd := int(w.notify.Fd())
	for dir, have := range w.watched {
		if mask, ok := want[dir]; !ok || mask != have.mask || !isDirFollow(dir) {
			_, _ = syscall.InotifyRmWatch(fd, uint32(have.wd))
			delete(w.watched, dir)
		}
	}
	for dir, mask := range want {
		if _, ok := w.watched[dir]; ok {
			continue
		}
		wd, err := syscall.InotifyAddWatch(fd, dir, mask)
		if err != nil {
			w.blind = true
			continue
		}
		w.watched[dir] = watch{wd: int32(wd), mask: mask}
	}
}

// snapshot 是现在要列出的各项，按时间从新到旧，同一个 id 只留最新的那份。
func (w *sessionWatch) snapshot() []obj {
	marks := w.e.sessionMarks()
	w.live = liveKey(marks.live)
	files := listedFiles(w.e, marks.pinned)
	seen := map[string]bool{}
	kept := make(map[string]cached, len(files))
	var items []obj
	for _, path := range files {
		c, ok := w.summaryOf(path)
		if !ok {
			continue
		}
		kept[path] = c
		if !c.ok || seen[c.s.id] {
			continue
		}
		seen[c.s.id] = true
		items = append(items, c.s.item(marks))
	}
	w.cache = kept
	return items
}

// summaryOf 是记录 path 的概要：大小和修改时刻都没变时用上次读的。只是变长了时，最新回答时间只往回找到上次的大小（replyMark）。读不了时 ok 为假。
func (w *sessionWatch) summaryOf(path string) (cached, bool) {
	fi, err := os.Stat(path)
	if err != nil {
		return cached{}, false
	}
	size, mtime := fi.Size(), fi.ModTime().UnixNano()
	if c, ok := w.cache[path]; ok && c.size == size && c.mtime == mtime {
		return c, true
	}
	var known replyMark
	if c, ok := w.cache[path]; ok && c.ok && c.size < size {
		known = replyMark{size: c.size, at: c.s.replied}
	}
	s, ok, err := summarizeAfter(path, &known)
	if err != nil {
		return cached{}, false
	}
	return cached{size: size, mtime: mtime, s: s, ok: ok}, true
}

// publish 重读一遍，发给客户端：full 时发整份，否则只发和上次不同的和 force 过的，都没有就不发。
func (w *sessionWatch) publish(full bool) error {
	// 先取走再重读：写操作在 force 之前就写完了，重读看得到
	forced := w.takeForced()
	items := w.snapshot()
	next := make(map[string]string, len(items))
	upsert := []obj{}
	for _, it := range items {
		id := it["id"].(string)
		j := compactJSON(it)
		next[id] = j
		if full || forced[id] || w.shown[id] != j {
			upsert = append(upsert, it)
		}
	}
	remove := []string{}
	if !full {
		remove = unlisted(next, w.shown, forced)
	}
	w.shown = next
	if !full && len(upsert) == 0 && len(remove) == 0 {
		return nil
	}
	return w.send(obj{"type": "zeta_sessions", "full": full, "upsert": upsert, "remove": remove})
}

// unlisted 是上次发过的（shown）和 force 过的（forced）里这次不在列表（next）里的 id，排好序。
func unlisted(next, shown map[string]string, forced map[string]bool) []string {
	gone := map[string]bool{}
	for id := range shown {
		gone[id] = true
	}
	for id := range forced {
		gone[id] = true
	}
	remove := []string{}
	for id := range gone {
		if _, ok := next[id]; !ok {
			remove = append(remove, id)
		}
	}
	sort.Strings(remove)
	return remove
}
