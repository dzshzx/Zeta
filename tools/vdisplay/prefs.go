package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"maps"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

func (v *vd) prefsFile() string { return "/data/user/0/" + v.pkg + "/shared_prefs/zeta.xml" }

// prefsOrigFile 是 prefs-set 记下的原值：{键: 原来那一项的 XML，没有这一项时为 null}。
func (v *vd) prefsOrigFile() string { return "/data/local/tmp/" + v.pkg + "-prefs-orig.json" }

// prefSpan 找设置文件里键 key 那一项（含前面的缩进和后面的换行；值可以跨行）：
// 自闭合的 <int name="k" value="1" />，或 <string name="k">…</string>。找不到时 ok 为 false。
func prefSpan(text, key string) (start, end int, ok bool) {
	head := regexp.MustCompile(`[ \t]*<(\w+) name="` + regexp.QuoteMeta(key) + `"`)
	for off := 0; off < len(text); {
		m := head.FindStringSubmatchIndex(text[off:])
		if m == nil {
			return 0, 0, false
		}
		start, tag, after := off+m[0], text[off+m[2]:off+m[3]], off+m[1]
		if end, ok := elementEnd(text, after, tag); ok {
			if end < len(text) && text[end] == '\n' {
				end++
			}
			return start, end, true
		}
		off = after
	}
	return 0, 0, false
}

// elementEnd：元素名和 name 属性之后从 p 起，到元素结束的位置。
func elementEnd(text string, p int, tag string) (int, bool) {
	rest := text[p:]
	gt := strings.IndexByte(rest, '>')
	switch {
	case gt > 0 && rest[gt-1] == '/':
		return p + gt + 1, true
	case gt == 0:
		closing := "</" + tag + ">"
		if i := strings.Index(rest[1:], closing); i >= 0 {
			return p + 1 + i + len(closing), true
		}
	}
	return 0, false
}

// prefPut 去掉键 key 原来的那一项；element 不为 nil 时放到 </map> 前。
func prefPut(text, key string, element *string) string {
	for {
		s, e, ok := prefSpan(text, key)
		if !ok {
			break
		}
		text = text[:s] + text[e:]
	}
	if element == nil {
		return text
	}
	return strings.Replace(text, "</map>", "    "+strings.TrimSpace(*element)+"\n</map>", 1)
}

// prefElement 是 prefs-set 一项赋值写进设置文件的样子。
func prefElement(key, kind, value string) string {
	if kind == "int" {
		n, _ := strconv.Atoi(value)
		return fmt.Sprintf(`<int name="%s" value="%d" />`, key, n)
	}
	escaped := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(value)
	return fmt.Sprintf(`<string name="%s">%s</string>`, key, escaped)
}

// applyPrefs 在设置文件 text 上做这些赋值；orig 里还没有的键先记下原值。返回改后的文件和改了的键。
func applyPrefs(text string, assignments []string, orig map[string]*string) (string, []string) {
	var keys []string
	for _, a := range assignments {
		m := assignment.FindStringSubmatch(a)
		key := m[1]
		if _, seen := orig[key]; !seen {
			orig[key] = nil
			if s, e, ok := prefSpan(text, key); ok {
				old := text[s:e]
				orig[key] = &old
			}
		}
		element := prefElement(key, m[2], m[3])
		text = prefPut(text, key, &element)
		keys = append(keys, key)
	}
	return text, keys
}

// restorePrefs 把 orig 记下的键改回原值。phoneDisplay 指着刚关掉的屏，一律删掉：它在 orig 里补成「原来没有」，调用方打印键名时也带上它。
func restorePrefs(text string, orig map[string]*string) string {
	orig["phoneDisplay"] = nil
	for _, key := range slices.Sorted(maps.Keys(orig)) {
		text = prefPut(text, key, orig[key])
	}
	return text
}

func (v *vd) readOrig() map[string]*string {
	orig := map[string]*string{}
	raw := strings.TrimSpace(v.try("cat " + v.prefsOrigFile() + " 2>/dev/null"))
	if raw != "" {
		if err := json.Unmarshal([]byte(raw), &orig); err != nil {
			die("%s 不是 JSON：%v", v.prefsOrigFile(), err)
		}
	}
	return orig
}

// writePrefs 写回测试版的设置文件。测试版被 force-stop 后会马上被系统拉起来（通知监听重新绑定），拉起来的进程要是先读了旧设置，
// 之后自己保存时会把这次写的冲掉；所以停、写、再停放在同一条命令里，下次启动读到的一定是新文件。
// 原地写（不换文件）：属主和 SELinux 标签不变。
func (v *vd) writePrefs(text string) {
	v.androidIn(fmt.Sprintf("am force-stop %[1]s; cat > %[2]s; am force-stop %[1]s", v.pkg, v.prefsFile()), []byte(text))
}

func (v *vd) prefsSet(assignments []string) {
	text := v.android("cat " + v.prefsFile())
	if !strings.Contains(text, "</map>") {
		die("设置文件不完整：%s", v.prefsFile())
	}
	orig := v.readOrig()
	text, keys := applyPrefs(text, assignments, orig)
	v.writePrefs(text)
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(orig); err != nil {
		die("%v", err)
	}
	v.androidIn("cat > "+v.prefsOrigFile(), buf.Bytes())
	fmt.Println("改了 " + strings.Join(keys, "、"))
}

// prefsRestore 改回 prefs-set 改过的键，在 force-stop 之后调。
func (v *vd) prefsRestore() {
	orig := v.readOrig()
	text := v.try("cat " + v.prefsFile() + " 2>/dev/null")
	if strings.Contains(text, "</map>") {
		if changed := restorePrefs(text, orig); changed != text {
			v.writePrefs(changed)
			fmt.Println("设置改回测试前：" + strings.Join(slices.Sorted(maps.Keys(orig)), "、"))
		}
	}
	v.try("rm -f " + v.prefsOrigFile())
}

func (v *vd) prefs(keys []string) {
	fmt.Print(v.try(fmt.Sprintf(`grep -E 'name="(%s)"' %s`, strings.Join(keys, "|"), v.prefsFile())))
}
