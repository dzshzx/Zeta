package conv

import (
	"bytes"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 斜杠命令（conv.state 的 commands）：Claude Code 在 initialize 的应答里给，核心滤掉 __ 开头的内部命令，按工作目录记下这台主机上
// 最近一次拿到的（CommandStore），还没起进程时打开对话也直接给出来，输入斜杠命令时不用先等进程起来。

// commandsKept 是记着几个工作目录的命令；多了丢掉最久没更新的。
const commandsKept = 32

// CommandStore 记着各工作目录最近一次拿到的斜杠命令，存在一个文件里（核心目录的 commands.json）。文件丢了、坏了只是少一份提示。
// 几个对话共用一份，方法可以在不同的 goroutine 上调。
type CommandStore struct {
	path string

	mu     sync.Mutex
	loaded bool
	byCwd  map[string]*commandEntry
}

// commandEntry 是一个工作目录的命令和更新的先后（越大越新）。
type commandEntry struct {
	Commands json.RawMessage `json:"commands"`
	Seq      int64           `json:"seq"`
}

// NewCommandStore 用文件 path 存命令；第一次用时才读。
func NewCommandStore(path string) *CommandStore { return &CommandStore{path: path} }

func (s *CommandStore) load() {
	if s.loaded {
		return
	}
	s.loaded = true
	s.byCwd = map[string]*commandEntry{}
	data, err := os.ReadFile(s.path)
	if err == nil {
		_ = json.Unmarshal(data, &s.byCwd)
	}
}

const agyCommandPrefix = "agy\x00"

func commandBucketKey(harness, cwd string) string {
	if sessionproto.NormalizeHarness(harness) == sessionproto.HarnessAgy {
		return agyCommandPrefix + cwd
	}
	return cwd
}

func sameCommandHarness(bucketKey, harness string) bool {
	isAgy := sessionproto.NormalizeHarness(harness) == sessionproto.HarnessAgy
	return strings.HasPrefix(bucketKey, agyCommandPrefix) == isAgy
}

// Recall 是工作目录 cwd 上次拿到的 Claude 斜杠命令。
func (s *CommandStore) Recall(cwd string) json.RawMessage {
	return s.RecallFor(sessionproto.HarnessClaude, cwd)
}

// RecallFor 是引擎 harness 在工作目录 cwd 上次拿到的命令；没有时用同一引擎最近拿到的那份。
func (s *CommandStore) RecallFor(harness, cwd string) json.RawMessage {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	if e := s.byCwd[commandBucketKey(harness, cwd)]; e != nil {
		return e.Commands
	}
	var newest *commandEntry
	for k, e := range s.byCwd {
		if sameCommandHarness(k, harness) && (newest == nil || e.Seq > newest.Seq) {
			newest = e
		}
	}
	if newest == nil {
		return nil
	}
	return newest.Commands
}

// Remember 记下工作目录 cwd 刚拿到的 Claude 斜杠命令 list。
func (s *CommandStore) Remember(cwd string, list json.RawMessage) {
	s.RememberFor(sessionproto.HarnessClaude, cwd, list)
}

// RememberFor 记下引擎 harness 在工作目录 cwd 刚拿到的命令 list，写进文件；和记着的一样时不写。
func (s *CommandStore) RememberFor(harness, cwd string, list json.RawMessage) {
	if s == nil || len(list) == 0 {
		return
	}
	key := commandBucketKey(harness, cwd)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	var newest int64
	for _, e := range s.byCwd {
		newest = max(newest, e.Seq)
	}
	if e := s.byCwd[key]; e != nil && bytes.Equal(e.Commands, list) && e.Seq == newest {
		return
	}
	s.byCwd[key] = &commandEntry{Commands: list, Seq: newest + 1}
	for len(s.byCwd) > commandsKept {
		oldest := ""
		for k, e := range s.byCwd {
			if oldest == "" || e.Seq < s.byCwd[oldest].Seq {
				oldest = k
			}
		}
		delete(s.byCwd, oldest)
	}
	if err := s.save(); err != nil {
		log.Print("记下斜杠命令：", err)
	}
}

// save 整份写进文件：先写临时文件再改名，写到一半时别的核心读到的还是完整的旧文件。
func (s *CommandStore) save() error {
	data, err := json.Marshal(s.byCwd)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// visibleCommands 是 initialize 应答里的命令去掉 __ 开头的内部命令和没有名字的项（客户端不列它们）。不是数组时原样返回。
func visibleCommands(list json.RawMessage) json.RawMessage {
	var all []json.RawMessage
	if json.Unmarshal(list, &all) != nil {
		return list
	}
	kept := slices.DeleteFunc(all, func(item json.RawMessage) bool {
		var c struct {
			Name string `json:"name"`
		}
		return json.Unmarshal(item, &c) != nil || c.Name == "" || strings.HasPrefix(c.Name, "__")
	})
	return raw(nonNilSlice(kept))
}
