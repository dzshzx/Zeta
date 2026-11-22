package claudecfg

import (
	"bufio"
	"cmp"
	"encoding/json"
	"os"
	"regexp"
	"slices"
	"strings"
)

// Claude Code 的两种长期记忆：用户写给它的指令 ~/.claude/CLAUDE.md，每次对话都载入；它自己记的自动记忆，每个项目一个目录
// （~/.claude/projects/<项目>/memory），每条一个文件，MEMORY.md 是索引。自动记忆的开关在 settings.json（autoMemoryEnabled）。

// ConfigFile 是一个配置文件在不在、多大。
type ConfigFile struct {
	Path   string `json:"path"`
	Exists bool   `json:"exists"`
	Size   int64  `json:"size"`
}

// MemoryProject 是记忆页上可选的一个项目。
type MemoryProject struct {
	Key     string `json:"key"`     // ~/.claude/projects 下的目录名
	Label   string `json:"label"`   // 还原出来的项目路径，还原不了时是目录名
	Count   int    `json:"count"`   // 记忆条数
	Default bool   `json:"default"` // 默认工作目录所在的项目
}

// MemoryEntry 是一条自动记忆。
type MemoryEntry struct {
	Path        string `json:"path"`
	FileName    string `json:"fileName"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Type        string `json:"type"` // user、feedback、project、reference，或文件里写的别的
	Size        int64  `json:"size"`
}

// Memory 是记忆页要显示的全部内容。
type Memory struct {
	AutoMemory   bool            `json:"autoMemory"`
	ForcedOff    bool            `json:"forcedOff"` // settings.json 的 env 里设了 CLAUDE_CODE_DISABLE_AUTO_MEMORY，开关不起作用
	CustomDir    string          `json:"customDir"` // 设了 autoMemoryDirectory 时所有项目共用这个目录
	Instructions ConfigFile      `json:"instructions"`
	Projects     []MemoryProject `json:"projects"`
	Selected     string          `json:"selected"` // 选中的项目
	Dir          string          `json:"dir"`      // 显示的记忆目录；没有项目可选时为空
	Index        *ConfigFile     `json:"index"`
	Files        []MemoryEntry   `json:"files"`
}

// Memory 读记忆页：cwd 是默认工作目录（空是家目录），project 是要看的项目（空或不在列表里时选第一个）。
func (s *Store) Memory(cwd, project string) Memory {
	home := s.Home()
	cwd = cmp.Or(cwd, home)
	ms := memorySettingsOf(s.lenientSettings())
	m := Memory{AutoMemory: ms.autoMemory, ForcedOff: ms.forcedOff, CustomDir: customMemoryDir(ms.customDir, home)}
	m.Instructions = ConfigFile{Path: instructions(home)}
	if _, size, ok := readHead(m.Instructions.Path, 0); ok {
		m.Instructions.Exists, m.Instructions.Size = true, size
	}
	if m.CustomDir != "" {
		// 设了 autoMemoryDirectory 时所有项目共用这一个目录，不用再选项目
		m.Dir = m.CustomDir
	} else {
		m.Projects = s.projects(cwd)
		for _, p := range m.Projects {
			if p.Key == project || m.Selected == "" {
				m.Selected = p.Key
			}
		}
		if m.Selected != "" {
			m.Dir = memoryDir(home, m.Selected)
		}
	}
	if m.Dir != "" {
		m.Index, m.Files = memoryFiles(m.Dir)
	}
	return m
}

func memoryFiles(dir string) (*ConfigFile, []MemoryEntry) {
	index := &ConfigFile{Path: dir + "/MEMORY.md"}
	if _, size, ok := readHead(index.Path, 0); ok {
		index.Exists, index.Size = true, size
	}
	entries, _ := os.ReadDir(dir)
	var files []MemoryEntry
	for _, e := range entries {
		name := e.Name()
		if name == "MEMORY.md" || !isMemoryFileName(name) {
			continue
		}
		path := dir + "/" + name
		if head, size, ok := readHead(path, 4096); ok {
			files = append(files, memoryEntry(path, size, head))
		}
	}
	slices.SortFunc(files, func(a, b MemoryEntry) int { return cmp.Compare(a.FileName, b.FileName) })
	return index, files
}

// memoryEntry 读记忆文件的前言：name、description、type（新版写在 metadata.type 下面）。
func memoryEntry(path string, size int64, head []byte) MemoryEntry {
	text, _, _ := splitFront(string(head))
	meta := parseFront(text)
	typ := meta.get("type")
	if typ == "" {
		typ = meta.values["metadata"].fields["type"]
	}
	return MemoryEntry{
		Path: path, FileName: baseName(path), Name: meta.get("name"),
		Description: meta.get("description"), Type: typ, Size: size,
	}
}

type projectDir struct {
	key    string
	count  int // memory 目录里的条数；没有 memory 目录是 -1
	mtime  int64
	sample string // 会话记录里的一个 cwd
}

// projects 是项目列表：默认工作目录对应的项目排第一（即使还没有记忆），其余有记忆的按修改时间新的在前。
// 名字从会话记录里的 cwd 还原，对不上的显示目录名。
func (s *Store) projects(cwd string) []MemoryProject {
	var base string
	if real, g := probeGit(cwd); real != "" {
		base = cmp.Or(canonicalRoot(g), real)
	}
	dirs := scanProjectDirs(projectsDir(s.Home()))
	var samples []string
	for _, d := range dirs {
		if d.sample != "" {
			samples = append(samples, d.sample)
		}
	}
	var projects []MemoryProject
	defaultKey := ""
	if base != "" {
		defaultKey = projectKey(base)
		p := MemoryProject{Key: defaultKey, Label: base, Default: true}
		for _, d := range dirs {
			if d.key == defaultKey {
				p.Count = max(d.count, 0)
			}
		}
		projects = append(projects, p)
	}
	others := slices.DeleteFunc(slices.Clone(dirs), func(d projectDir) bool { return d.key == defaultKey || d.count <= 0 })
	slices.SortStableFunc(others, func(a, b projectDir) int { return cmp.Compare(b.mtime, a.mtime) })
	for _, d := range others {
		label := labelFor(d.key, append([]string{d.sample}, samples...))
		projects = append(projects, MemoryProject{Key: d.key, Label: cmp.Or(label, d.key), Count: d.count})
	}
	return projects
}

// scanProjectDirs 是 ~/.claude/projects 下每个项目目录：记忆条数、记忆目录的修改时间、最新会话记录里的一个 cwd。
func scanProjectDirs(root string) []projectDir {
	entries, _ := os.ReadDir(root)
	var dirs []projectDir
	for _, e := range entries {
		dir := root + "/" + e.Name()
		if st, err := os.Stat(dir); err != nil || !st.IsDir() {
			continue
		}
		d := projectDir{key: e.Name(), count: -1, sample: newestCwd(dir)}
		// memory 可能是指向别的项目的符号链接（几个项目共用一份），跟着它走
		if st, err := os.Stat(dir + "/memory"); err == nil && st.IsDir() {
			d.mtime = st.ModTime().Unix()
			d.count = 0
			files, _ := os.ReadDir(dir + "/memory")
			for _, f := range files {
				if f.Type().IsRegular() && strings.HasSuffix(f.Name(), ".md") && f.Name() != "MEMORY.md" {
					d.count++
				}
			}
		}
		dirs = append(dirs, d)
	}
	return dirs
}

var cwdField = regexp.MustCompile(`"cwd":"[^"]*"`)

// newestCwd 从项目目录里最新的会话记录开头 40 行抠出 "cwd":"…"。
func newestCwd(dir string) string {
	entries, _ := os.ReadDir(dir)
	var newest string
	var newestTime int64
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".jsonl") {
			continue
		}
		if info, err := e.Info(); err == nil && (newest == "" || info.ModTime().UnixNano() > newestTime) {
			newest, newestTime = e.Name(), info.ModTime().UnixNano()
		}
	}
	if newest == "" {
		return ""
	}
	f, err := os.Open(dir + "/" + newest)
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64<<10), 4<<20)
	for i := 0; i < 40 && sc.Scan(); i++ {
		if m := cwdField.Find(sc.Bytes()); m != nil {
			return sampleCwd(string(m))
		}
	}
	return ""
}

// sampleCwd 解出会话记录里抠出来的 "cwd":"…"（JSON 转义过）。
func sampleCwd(raw string) string {
	var v struct{ Cwd string }
	if json.Unmarshal([]byte("{"+raw+"}"), &v) != nil || !strings.HasPrefix(v.Cwd, "/") {
		return ""
	}
	return v.Cwd
}

func (s *Store) checkEditable(path string) error {
	custom := customMemoryDir(memorySettingsOf(s.lenientSettings()).customDir, s.Home())
	if p := editableProblem(path, s.Home(), custom); p != "" {
		return fail("%s", p)
	}
	return nil
}

// ReadDocument 读记忆页上的一个文件（CLAUDE.md、MEMORY.md、一条记忆）。
func (s *Store) ReadDocument(path string) (Document, error) {
	if err := s.checkEditable(path); err != nil {
		return Document{}, err
	}
	return load(path, MaxText)
}

// WriteDocument 写记忆页上的一个文件，返回新内容的 SHA。expectedSHA 是读到时的 SHA（文件原来不存在时为空）；
// 文件在编辑期间被别人改过时返回 ErrConflict。force 为 true 时不比对，直接覆盖。
func (s *Store) WriteDocument(path, content, expectedSHA string, force bool) (string, error) {
	if err := s.checkEditable(path); err != nil {
		return "", err
	}
	if len(content) > MaxText {
		return "", fail("内容超过 1 MiB")
	}
	expected := cmp.Or(expectedSHA, "-")
	if force {
		expected = "*"
	}
	return store(path, []byte(content), expected, "")
}

// DeleteMemory 删一条记忆，连同 MEMORY.md 里指向它的那一行。
func (s *Store) DeleteMemory(path string) error {
	if err := s.checkEditable(path); err != nil {
		return err
	}
	name := baseName(path)
	if name == "MEMORY.md" || path == instructions(s.Home()) {
		return fail("不能删除这个文件")
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fail("删不掉：%s", pathReason(err))
	}
	index := parentDir(path) + "/MEMORY.md"
	for range 3 {
		doc, err := load(index, MaxText)
		if err != nil || !doc.Exists {
			return err
		}
		updated, changed := indexWithout(doc.Content, name)
		if !changed {
			return nil
		}
		if _, err := store(index, []byte(updated), doc.SHA, ""); err != ErrConflict {
			return err
		}
	}
	return nil
}

// indexWithout 去掉 MEMORY.md 里指向 fileName 的那几行。
func indexWithout(index, fileName string) (string, bool) {
	lines := strings.Split(index, "\n")
	kept := slices.DeleteFunc(slices.Clone(lines), func(l string) bool {
		return strings.Contains(l, "]("+fileName+")") || strings.Contains(l, "](./"+fileName+")")
	})
	return strings.Join(kept, "\n"), len(kept) != len(lines)
}

// SetAutoMemory 打开或关掉自动记忆（settings.json 的 autoMemoryEnabled，对所有项目生效）。
func (s *Store) SetAutoMemory(on bool) error {
	_, err := s.updateSettings(func(settings *object) error {
		settings.set("autoMemoryEnabled", on)
		return nil
	})
	return err
}
