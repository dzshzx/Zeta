package claudecfg

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

// Skill 的 ZIP 包在这边查：不许绝对路径、..、反斜杠、符号链接、加密；限制项数和解压后的总大小。挑出 SKILL.md 所在那一层的
// 文件，先完整解一遍核对大小和 CRC（archive/zip 读的时候核对），再解到临时目录换上去：解到一半才发现坏了会留下半个 Skill。

const (
	maxZipEntries = 4000
	maxZipTotal   = 128 << 20
	maxSkillMD    = 1 << 20
)

var skillNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)

// toSkillName 把一段文字变成 Skill 的名字：转小写，别的字符换成 -，去掉首尾的 -，最长 64。
func toSkillName(text string) string {
	s := regexp.MustCompile(`[^a-z0-9-]+`).ReplaceAllString(strings.ToLower(strings.TrimSpace(text)), "-")
	s = strings.Trim(s, "-")
	if len(s) > 64 {
		s = s[:64]
	}
	return strings.Trim(s, "-")
}

func isSkillName(name string) bool { return skillNamePattern.MatchString(name) && name != "synced" }

type skillMember struct {
	path string // 相对 Skill 的根，目录不带末尾的 /
	dir  bool
	exec bool
	file *zip.File
}

type skillPackage struct {
	name, description string
	members           []skillMember
}

// openSkillZip 打开并检查 Skill 包。SKILL.md 可以在包的根上，也可以在第一层文件夹里，取最浅的那个；它所在那一层的东西就是
// Skill 的全部。名字依次取 SKILL.md 里的 name、那层文件夹的名字、ZIP 的文件名，第一个合规的。
func openSkillZip(data []byte, fileName string) (*skillPackage, error) {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil && !errors.Is(err, zip.ErrInsecurePath) {
		return nil, fail("这不是一个有效的 ZIP 文件")
	}
	if len(zr.File) > maxZipEntries {
		return nil, fail("ZIP 里的文件太多（超过 %d 个）", maxZipEntries)
	}
	var usable []*zip.File
	var total uint64
	for _, f := range zr.File {
		if strings.HasPrefix(f.Name, "__MACOSX/") || strings.HasPrefix(path.Base(strings.TrimSuffix(f.Name, "/")), "._") {
			continue
		}
		usable = append(usable, f)
		total += f.UncompressedSize64
	}
	if total > maxZipTotal {
		return nil, fail("ZIP 解压后太大（超过 %d MiB）", maxZipTotal>>20)
	}
	skillMD := shallowestSkillMD(usable)
	if skillMD == nil {
		return nil, fail("包里没有 SKILL.md（应放在根目录或第一层文件夹里）")
	}
	prefix := strings.TrimSuffix(skillMD.Name, "SKILL.md")
	text, err := readZipFile(skillMD, maxSkillMD)
	if err != nil {
		return nil, err
	}
	if !utf8.Valid(text) {
		return nil, fail("SKILL.md 不是 UTF-8 文本")
	}
	frontText, _, ok := splitFront(string(text))
	meta := parseFront(frontText)
	pkg := &skillPackage{description: strings.TrimSpace(meta.get("description"))}
	if !ok || pkg.description == "" {
		return nil, fail("SKILL.md 开头缺少带 description 的前言（--- 包起来的 name、description），Claude 不知道什么时候该用它")
	}
	for _, candidate := range []string{meta.get("name"), strings.Trim(prefix, "/"), strings.TrimSuffix(fileName, path.Ext(fileName))} {
		if n := toSkillName(candidate); isSkillName(n) {
			pkg.name = n
			break
		}
	}
	if pkg.name == "" {
		return nil, fail("认不出 Skill 的名字：请在 SKILL.md 里写上 name（小写字母、数字和连字符）")
	}
	for _, f := range usable {
		m, ok, err := memberOf(f, prefix)
		if err != nil {
			return nil, err
		}
		if ok {
			pkg.members = append(pkg.members, m)
		}
	}
	for _, m := range pkg.members {
		if m.file != nil {
			if err := extract(m.file, io.Discard); err != nil {
				return nil, err
			}
		}
	}
	return pkg, nil
}

func shallowestSkillMD(files []*zip.File) *zip.File {
	var best *zip.File
	for _, f := range files {
		depth := strings.Count(f.Name, "/")
		if path.Base(f.Name) != "SKILL.md" || strings.HasSuffix(f.Name, "/") || depth > 1 {
			continue
		}
		if best == nil || depth < strings.Count(best.Name, "/") {
			best = f
		}
	}
	return best
}

// memberOf：f 在 Skill 那一层（prefix）下面时是要装的一项；路径不安全、是符号链接时报错。
func memberOf(f *zip.File, prefix string) (skillMember, bool, error) {
	rel, ok := strings.CutPrefix(f.Name, prefix)
	if !ok || rel == "" {
		return skillMember{}, false, nil
	}
	var parts []string
	for _, p := range strings.Split(rel, "/") {
		if p != "" {
			parts = append(parts, p)
		}
	}
	if unsafePath(rel, parts) {
		return skillMember{}, false, fail("ZIP 里有不安全的路径：%s", f.Name)
	}
	mode := f.Mode()
	if mode&fs.ModeSymlink != 0 {
		return skillMember{}, false, fail("ZIP 里有符号链接，不支持：%s", f.Name)
	}
	if f.Flags&1 != 0 {
		return skillMember{}, false, fail("不支持加密的 ZIP：%s", f.Name)
	}
	m := skillMember{path: strings.Join(parts, "/"), dir: strings.HasSuffix(f.Name, "/")}
	if !m.dir {
		m.file = f
		// 只有 Unix 上做的包才有权限位
		m.exec = f.CreatorVersion>>8 == 3 && mode&0o111 != 0
	}
	return m, true, nil
}

// unsafePath：绝对路径、反斜杠、. 或 ..、控制字符，或者什么都不剩。
func unsafePath(rel string, parts []string) bool {
	if strings.HasPrefix(rel, "/") || strings.Contains(rel, `\`) || len(parts) == 0 {
		return true
	}
	for _, r := range rel {
		if r < ' ' || r == 0x7f {
			return true
		}
	}
	for _, p := range parts {
		if p == "." || p == ".." {
			return true
		}
	}
	return false
}

// extract 解压一项写到 w；实际字节数、CRC 与中央目录不符、压缩方式不认识时报错。
func extract(f *zip.File, w io.Writer) error {
	rc, err := f.Open()
	if err != nil {
		if errors.Is(err, zip.ErrAlgorithm) {
			return fail("不支持这种压缩方式（%d）：%s", f.Method, f.Name)
		}
		return fail("ZIP 文件已损坏：%s", f.Name)
	}
	defer rc.Close()
	n, err := io.Copy(w, rc)
	if err != nil || uint64(n) != f.UncompressedSize64 {
		var cfg *Error
		if errors.As(err, &cfg) {
			return err
		}
		return fail("ZIP 文件已损坏：%s", f.Name)
	}
	return nil
}

func readZipFile(f *zip.File, limit int) ([]byte, error) {
	if f.UncompressedSize64 > uint64(limit) {
		return nil, fail("%s 太大", f.Name)
	}
	var b bytes.Buffer
	if err := extract(f, &b); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

// unpack 把 Skill 的文件解到 dir 下，只写目录和普通文件。
func (p *skillPackage) unpack(dir string) error {
	for _, m := range p.members {
		target := filepath.Join(dir, filepath.FromSlash(m.path))
		if m.dir {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return fail("解包失败：%s", pathReason(err))
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return fail("解包失败：%s", pathReason(err))
		}
		mode := os.FileMode(0o644)
		if m.exec {
			mode = 0o755
		}
		f, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
		if err != nil {
			return fail("解包失败：%s", pathReason(err))
		}
		err = extract(m.file, f)
		if cerr := f.Close(); err == nil && cerr != nil {
			err = fail("写入失败，可能是存储空间不足")
		}
		if err != nil {
			return err
		}
	}
	return nil
}
