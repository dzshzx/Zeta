package locales

import (
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func catalogKeys(t *testing.T, file string) map[string]bool {
	t.Helper()
	b, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	keys := map[string]bool{}
	for k := range m {
		if !strings.HasPrefix(k, "@") {
			keys[k] = true
		}
	}
	return keys
}

// keyUse 是代码里一处 locales.M、locales.Err：键直接写成字符串时 key 是它，否则 literal 为假。
type keyUse struct {
	file, fn, key string
	literal       bool
}

// keyUses 列出文件 p 里的 locales.M、locales.Err 调用。
func keyUses(p string) ([]keyUse, error) {
	f, err := parser.ParseFile(token.NewFileSet(), p, nil, 0)
	if err != nil {
		return nil, err
	}
	var uses []keyUse
	ast.Inspect(f, func(n ast.Node) bool {
		if call, ok := n.(*ast.CallExpr); ok {
			if fn := localesCall(call); fn != "" {
				uses = append(uses, keyUseOf(p, fn, call.Args[0]))
			}
		}
		return true
	})
	return uses, nil
}

// localesCall：call 是 locales.M 或 locales.Err 时返回函数名，否则为空。
func localesCall(call *ast.CallExpr) string {
	sel, ok := call.Fun.(*ast.SelectorExpr)
	if !ok || (sel.Sel.Name != "M" && sel.Sel.Name != "Err") {
		return ""
	}
	if x, ok := sel.X.(*ast.Ident); !ok || x.Name != "locales" {
		return ""
	}
	return sel.Sel.Name
}

func keyUseOf(file, fn string, arg ast.Expr) keyUse {
	lit, ok := arg.(*ast.BasicLit)
	if !ok || lit.Kind != token.STRING {
		return keyUse{file: file, fn: fn}
	}
	key, _ := strconv.Unquote(lit.Value)
	return keyUse{file: file, fn: fn, key: key, literal: true}
}

// coreGoFiles 是核心里除了文字表包和 testdata 的 Go 文件。
func coreGoFiles() ([]string, error) {
	var files []string
	err := filepath.WalkDir("..", func(p string, d fs.DirEntry, err error) error {
		switch {
		case err != nil:
			return err
		case d.IsDir() && (d.Name() == "testdata" || d.Name() == "locales" && p != ".."):
			return filepath.SkipDir
		case !d.IsDir() && strings.HasSuffix(p, ".go"):
			files = append(files, p)
		}
		return nil
	})
	return files, err
}

// 核心代码里 locales.M、locales.Err 的键直接写成字符串、在文字表里：拼出来的键查不出写错。
func TestKeysUsedExist(t *testing.T) {
	keys := catalogKeys(t, "zh-Hans.json")
	files, err := coreGoFiles()
	if err != nil {
		t.Fatal(err)
	}
	used := 0
	for _, p := range files {
		uses, err := keyUses(p)
		if err != nil {
			t.Fatal(err)
		}
		for _, u := range uses {
			used++
			switch {
			case !u.literal:
				t.Errorf("%s：locales.%s 的键要直接写成字符串", u.file, u.fn)
			case !keys[u.key]:
				t.Errorf("%s：文字表里没有 %q", u.file, u.key)
			}
		}
	}
	if used == 0 {
		t.Fatal("一处 locales.M、locales.Err 也没找到：扫描的目录不对")
	}
}

// 按语言出字：英文、源语言，没有的语言退回源语言；参数代进去。
func TestMsgIn(t *testing.T) {
	m := M("noticeStopped")
	if m.In("en") != "Stopped" || m.String() != "已停止" || m.In("de") != "已停止" {
		t.Errorf("%q %q %q", m.In("en"), m.String(), m.In("de"))
	}
	if e := Err("noticeSteered"); e.Error() != "已打断，先处理插话" || e.Code() != "noticeSteered" {
		t.Errorf("%q %q", e.Error(), e.Code())
	}
}
