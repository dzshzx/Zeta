// Command ktlocales 生成手机版的 Kotlin 取字代码（docs/i18n.md）：读 ARB 格式的文字表，每个键一个带类型的函数，
// 参数按 @键 的 placeholders 声明的类型（没声明的照 mygo 推断：复数、number 是 int，date、time 是 DateTime，别的是 String）。
// 两份文字表：核心的 helper/locales 生成 CoreStrings，手机版的 app/src/main/locales 生成 Strings，都写进 shared 的
// com.dzshzx.zeta.i18n（手机版的界面有一半在 shared 里）。生成的文件和文字表一起提交；门禁（scripts/check.sh）重新生成一次，
// 和提交的对不上就失败。
//
// 用法：在 helper/ 下 go run ./cmd/ktlocales [-root 仓根]。
//
// 文字表对不对（消息能解析、英文的占位和复数、每句都有英文）由 mygo i18n check 核对，这里只读源语言（简体中文）那份的键、
// 说明和占位，消息解析不了时失败。
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/egoist/mygo/i18n"
)

// source 是文字表的源语言，生成的取字函数的说明里写它的文字。
const source = "zh-Hans"

// catalog 是一份文字表和它生成的 Kotlin 对象。
type catalog struct {
	dir    string // 文字表目录，相对仓根
	name   string // 打进包里的 Java 资源目录 locales/<name>/（app/build.gradle.kts 的 catalogResources）
	object string // 生成的 Kotlin 对象名
	doc    string // 对象的说明
}

// outDir 是生成的 Kotlin 文件放的目录，相对仓根；文件名是 <对象名>.gen.kt。
const outDir = "shared/src/commonMain/kotlin/com/dzshzx/zeta/i18n"

var catalogs = []catalog{
	{
		dir: "helper/locales", name: "core", object: "CoreStrings",
		doc: "核心的文字表（helper/locales）：核心推来的种类码（提示条目的 code 等）按界面语言出的字。",
	},
	{
		dir: "app/src/main/locales", name: "app", object: "Strings",
		doc: "手机版界面的文字表（app/src/main/locales）。",
	},
}

func main() {
	root := flag.String("root", "..", "仓根")
	flag.Parse()
	for _, c := range catalogs {
		if err := write(*root, c); err != nil {
			fmt.Fprintf(os.Stderr, "ktlocales: %s: %v\n", c.dir, err)
			os.Exit(1)
		}
	}
}

// write 读 c 的源语言文字表，生成的代码和已有的不一样时写出。
func write(root string, c catalog) error {
	data, err := os.ReadFile(filepath.Join(root, c.dir, source+".json"))
	if err != nil {
		return err
	}
	keys, err := parse(data)
	if err != nil {
		return err
	}
	out := filepath.Join(root, outDir, c.object+".gen.kt")
	code := kotlin(c, keys)
	if old, err := os.ReadFile(out); err == nil && bytes.Equal(old, code) {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	return os.WriteFile(out, code, 0o644)
}

// key 是源语言文字表里的一句话。
type key struct {
	name, message, description string
	params                     []param
}

// param 是一句话的一个参数：消息里的名字和 ARB 的类型（String、int、double、num、DateTime、Object）。
type param struct{ name, typ string }

// arbTypes 是 ARB 占位类型对应的 Kotlin 类型。
var arbTypes = map[string]string{
	"String":   "String",
	"int":      "Int",
	"double":   "Double",
	"num":      "Number",
	"DateTime": "java.util.Date",
	"Object":   "Any",
}

// parse 读源语言文字表：键按文件里的先后，带上 @键 里的说明和占位。
func parse(data []byte) ([]key, error) {
	names, values, err := orderedObject(data)
	if err != nil {
		return nil, err
	}
	var keys []key
	for _, name := range names {
		if strings.HasPrefix(name, "@") {
			continue
		}
		k := key{name: name}
		if err := json.Unmarshal(values[name], &k.message); err != nil {
			return nil, fmt.Errorf("%s 不是字符串：%v", name, err)
		}
		if err := k.readMeta(values["@"+name]); err != nil {
			return nil, err
		}
		keys = append(keys, k)
	}
	return keys, nil
}

// readMeta 读 @键：说明和占位；占位先按声明的先后，再补上消息里用到、没声明的。
func (k *key) readMeta(raw json.RawMessage) error {
	m, err := i18n.ParseMessage(k.message)
	if err != nil {
		return fmt.Errorf("%s 解析不了：%v", k.name, err)
	}
	var meta struct {
		Description  string          `json:"description"`
		Placeholders json.RawMessage `json:"placeholders"`
	}
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &meta); err != nil {
			return fmt.Errorf("@%s：%v", k.name, err)
		}
	}
	k.description = meta.Description
	if len(meta.Placeholders) > 0 && string(meta.Placeholders) != "null" {
		names, values, err := orderedObject(meta.Placeholders)
		if err != nil {
			return fmt.Errorf("@%s.placeholders：%v", k.name, err)
		}
		for _, name := range names {
			var p struct {
				Type string `json:"type"`
			}
			if err := json.Unmarshal(values[name], &p); err != nil {
				return fmt.Errorf("@%s.placeholders.%s：%v", k.name, name, err)
			}
			if _, ok := arbTypes[p.Type]; p.Type != "" && !ok {
				return fmt.Errorf("@%s.placeholders.%s：类型 %q 不是 String、int、double、num、DateTime、Object", k.name, name, p.Type)
			}
			k.params = append(k.params, param{name: name, typ: p.Type})
		}
	}
	for _, a := range m.Args() {
		i := slices.IndexFunc(k.params, func(p param) bool { return p.name == a.Name })
		if i < 0 {
			k.params = append(k.params, param{name: a.Name})
			i = len(k.params) - 1
		}
		if k.params[i].typ == "" {
			k.params[i].typ = inferType(a.Kind)
		}
	}
	return nil
}

// inferType 是没声明类型的参数的类型，同 mygo：复数、序数、number 是 int，date、time 是 DateTime，别的是 String。
func inferType(kind string) string {
	switch kind {
	case "plural", "selectordinal", "number":
		return "int"
	case "date", "time":
		return "DateTime"
	}
	return "String"
}

// orderedObject 读一个 JSON 对象：键按出现的先后，值原样。
func orderedObject(data []byte) ([]string, map[string]json.RawMessage, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	if t, err := dec.Token(); err != nil || t != json.Delim('{') {
		return nil, nil, errors.New("不是 JSON 对象")
	}
	var names []string
	values := map[string]json.RawMessage{}
	for dec.More() {
		t, err := dec.Token()
		if err != nil {
			return nil, nil, err
		}
		name, _ := t.(string)
		var v json.RawMessage
		if err := dec.Decode(&v); err != nil {
			return nil, nil, err
		}
		if _, dup := values[name]; dup {
			return nil, nil, fmt.Errorf("键 %s 出现了两次", name)
		}
		names = append(names, name)
		values[name] = v
	}
	if _, err := dec.Token(); err != nil {
		return nil, nil, err
	}
	if _, err := dec.Token(); err != io.EOF {
		return nil, nil, errors.New("对象后面还有内容")
	}
	return names, values, nil
}
