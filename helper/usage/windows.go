package usage

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
)

// Windows 是这台主机上从 Claude Code 学到的各模型的上下文窗口（模型 → 窗口），记在一个 JSON 文件里，
// 给还没起进程的旧会话算占比用。窗口跟着这台 Claude Code 的设置（autoCompactWindow）走，所以每台主机各记各的。
// 文件只是缓存：丢了、坏了都只是要等下次 get_context_usage 再学一遍。几个对话（goroutine）共用一个，自带锁。
type Windows struct {
	path  string
	mu    sync.Mutex
	known map[string]int64
}

// LoadWindows 读 path 处的记录。文件不存在时是空的；读不了或不是这个格式时也当空的用，同时返回原因。
// 返回的 *Windows 总能用。
func LoadWindows(path string) (*Windows, error) {
	w := &Windows{path: path, known: map[string]int64{}}
	known, err := readWindows(path)
	if known != nil {
		w.known = known
	}
	return w, err
}

func readWindows(path string) (map[string]int64, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return map[string]int64{}, nil
	}
	if err != nil {
		return nil, err
	}
	known := map[string]int64{}
	if err := json.Unmarshal(data, &known); err != nil {
		return nil, err
	}
	return known, nil
}

// windowKey 把引擎名与模型名拼成缓存键：Claude 沿用裸模型名兼容旧缓存，其余引擎加 "<harness>:" 前缀隔离。
func windowKey(harness, model string) string {
	if harness == "" || harness == "claude" || model == "" {
		return model
	}
	return harness + ":" + model
}

// Window 是以前学到的模型 model 的窗口。w 是 nil 时什么也没有。
func (w *Windows) Window(model string) (int64, bool) {
	return w.WindowFor("", model)
}

// WindowFor 是指定引擎下以前学到的模型 model 的窗口。
func (w *Windows) WindowFor(harness, model string) (int64, bool) {
	if w == nil {
		return 0, false
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	v, ok := w.known[windowKey(harness, model)]
	return v, ok
}

// Learn 记下模型 model 的窗口。和记着的一样时什么也不做；不一样时先重读文件（同一台主机上别的核心进程可能刚写过），
// 并上这一项再整份替换写回。写不成时内存里照样记着，返回原因。
func (w *Windows) Learn(model string, window int64) error {
	return w.LearnFor("", model, window)
}

// LearnFor 记下指定引擎下模型 model 的窗口。
func (w *Windows) LearnFor(harness, model string, window int64) error {
	key := windowKey(harness, model)
	w.mu.Lock()
	defer w.mu.Unlock()
	if v, ok := w.known[key]; ok && v == window {
		return nil
	}
	if fresh, err := readWindows(w.path); err == nil {
		w.known = fresh
	}
	w.known[key] = window
	return writeAtomic(w.path, w.known)
}

// writeAtomic 把 v 的 JSON 写到同目录的临时文件再改名替换 path，读的一方不会看到写了一半的文件。
func writeAtomic(path string, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	_, err = f.Write(data)
	if closeErr := f.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(f.Name(), path)
	}
	if err != nil {
		_ = os.Remove(f.Name())
	}
	return err
}
