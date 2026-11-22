package claudecfg

import (
	"cmp"
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
)

// ~/.claude/skills 下每个含 SKILL.md 的文件夹是一个 Skill。开关写的是 Claude Code 自己的设置：settings.json 的
// skillOverrides（关掉是 "off"），和它的 /skills 菜单一样，文件不动。账号同步的 Skill 在 ~/.claude/skills/synced，
// 由 Claude Code 从 claude.ai 拉下来，只列出来。

// Skill 是用户装的一个 Skill。
type Skill struct {
	Name        string `json:"name"`  // 文件夹名，也是 Skill 的名字（/名字、skillOverrides 的键）
	Title       string `json:"title"` // SKILL.md 里写的 name
	Description string `json:"description"`
	Enabled     bool   `json:"enabled"`
	Valid       bool   `json:"valid"` // 有 SKILL.md
}

// SyncedSkill 是账号同步的一个 Skill。
type SyncedSkill struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Enabled     bool   `json:"enabled"`
}

// Skills 是 Skills 页的内容。
type Skills struct {
	User   []Skill       `json:"user"`
	Synced []SyncedSkill `json:"synced"`
}

// Skills 读 Skills 页。
func (s *Store) Skills() Skills {
	settings := s.lenientSettings()
	root := s.claudeDir() + "/skills"
	entries, _ := os.ReadDir(root)
	out := Skills{User: []Skill{}, Synced: []SyncedSkill{}}
	for _, e := range entries {
		if st, err := os.Stat(root + "/" + e.Name()); err != nil || !st.IsDir() || e.Name() == "synced" {
			continue
		}
		sk := Skill{Name: e.Name()}
		if head, _, ok := readHead(root+"/"+e.Name()+"/SKILL.md", 8192); ok {
			sk.Valid = true
			text, _, _ := splitFront(string(head))
			meta := parseFront(text)
			sk.Title, sk.Description = meta.get("name"), meta.get("description")
		}
		sk.Enabled = skillOverride(settings, sk.Name, sk.Title) != "off"
		out.User = append(out.User, sk)
	}
	manifests, _ := filepath.Glob(root + "/synced/*/manifest.json")
	slices.Sort(manifests)
	seen := map[string]bool{}
	for _, path := range manifests {
		data, _, ok := readHead(path, 4<<20)
		if !ok {
			continue
		}
		var manifest struct {
			Skills []struct{ Name, Description string }
		}
		if json.Unmarshal(data, &manifest) != nil {
			continue
		}
		for _, m := range manifest.Skills {
			if m.Name == "" || seen[m.Name] {
				continue
			}
			seen[m.Name] = true
			out.Synced = append(out.Synced, SyncedSkill{m.Name, m.Description, skillOverride(settings, m.Name) != "off"})
		}
	}
	slices.SortFunc(out.User, func(a, b Skill) int { return cmp.Compare(a.Name, b.Name) })
	slices.SortFunc(out.Synced, func(a, b SyncedSkill) int { return cmp.Compare(a.Name, b.Name) })
	return out
}

func checkSkillName(name string) error {
	if name == "" || strings.Contains(name, "/") || strings.HasPrefix(name, ".") || name == "synced" || hasControl(name) {
		return fail("名字不对")
	}
	return nil
}

// SetSkillEnabled 停用写 skillOverrides 的 "off"，和 Claude Code 里 /skills 关掉一样；启用把这个名字（和 SKILL.md 里的 name）
// 下的 "off" 去掉，别的取值（name-only 之类）不动。
func (st *Store) SetSkillEnabled(s Skill, enabled bool) error {
	if err := checkSkillName(s.Name); err != nil {
		return err
	}
	_, err := st.updateSettings(func(settings *object) error {
		existing := settings.obj("skillOverrides")
		overrides := existing
		if overrides == nil {
			overrides = newObject()
		}
		if enabled {
			for _, n := range []string{s.Title, s.Name} {
				if v, _ := overrides.str(n); n != "" && v == "off" {
					overrides.del(n)
				}
			}
		} else {
			overrides.set(s.Name, "off")
		}
		if existing == nil && overrides.len() > 0 {
			settings.set("skillOverrides", overrides)
		}
		return nil
	})
	return err
}

// DeleteSkill 删掉一个 Skill 的文件夹（是链接就只删链接），顺带清掉它的 skillOverrides 项。
func (s *Store) DeleteSkill(name string) error {
	if err := checkSkillName(name); err != nil {
		return err
	}
	path := s.claudeDir() + "/skills/" + name
	st, err := os.Lstat(path)
	switch {
	case err != nil:
		return fail("找不到这个 Skill，可能已经被删了")
	case st.Mode()&os.ModeSymlink != 0:
		err = os.Remove(path)
	case st.IsDir():
		err = os.RemoveAll(path)
	default:
		return fail("找不到这个 Skill，可能已经被删了")
	}
	if err != nil {
		return fail("删不掉：%s", pathReason(err))
	}
	_, err = s.updateSettings(func(settings *object) error {
		if o := settings.obj("skillOverrides"); o != nil {
			o.del(name)
		}
		return nil
	})
	return err
}

// SkillImport 是装 Skill 的结果。Exists 为 true 时什么都没装：已经有同名的，要用户确认替换（ExistingDescription 是原来那个的说明）。
type SkillImport struct {
	Name                string `json:"name"`
	Exists              bool   `json:"exists"`
	ExistingDescription string `json:"existingDescription"`
	Replaced            bool   `json:"replaced"`
	Enabled             bool   `json:"enabled"` // 装好了，但 skillOverrides 里停着它时为 false
}

// ImportSkill 从 ZIP 装一个 Skill：查过包、挑出 SKILL.md 那一层，先解到 ~/.claude 下的临时目录，再整个换上去。
// fileName 是 ZIP 的文件名（认不出名字时用它）；replace 为 true 时替换同名的。
func (s *Store) ImportSkill(fileName string, zipData []byte, replace bool) (SkillImport, error) {
	pkg, err := openSkillZip(zipData, fileName)
	if err != nil {
		return SkillImport{}, err
	}
	root := s.claudeDir() + "/skills"
	dest := root + "/" + pkg.name
	_, statErr := os.Lstat(dest)
	if statErr == nil && !replace {
		out := SkillImport{Name: pkg.name, Exists: true}
		if head, _, ok := readHead(dest+"/SKILL.md", 8192); ok {
			text, _, _ := splitFront(string(head))
			out.ExistingDescription = parseFront(text).get("description")
		}
		return out, nil
	}
	if err := os.MkdirAll(root, 0o777); err != nil {
		return SkillImport{}, fail("建不了目录：%s", pathReason(err))
	}
	stage, err := os.MkdirTemp(s.claudeDir(), ".zeta-skill-")
	if err != nil {
		return SkillImport{}, fail("建不了目录：%s", pathReason(err))
	}
	defer os.RemoveAll(stage)
	if err := os.Chmod(stage, 0o755); err != nil {
		return SkillImport{}, fail("建不了目录：%s", pathReason(err))
	}
	if err := pkg.unpack(stage); err != nil {
		return SkillImport{}, err
	}
	replaced := statErr == nil
	old := ""
	if replaced {
		old = stage + ".old"
		if err := os.Rename(dest, old); err != nil {
			return SkillImport{}, fail("换不下原来的 Skill")
		}
		defer os.RemoveAll(old)
	}
	if err := os.Rename(stage, dest); err != nil {
		if old != "" {
			_ = os.Rename(old, dest)
		}
		return SkillImport{}, fail("换不上新的 Skill")
	}
	return SkillImport{Name: pkg.name, Replaced: replaced, Enabled: skillOverride(s.lenientSettings(), pkg.name) != "off"}, nil
}
