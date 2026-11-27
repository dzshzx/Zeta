// Package literalcheck 是「界面上的字从文字表来」的检查（docs/i18n.md「怎么加一句话」），给 hub/locales 和
// windows/internal/locales 的测试用：扫给定目录下的非测试 Go 文件，含汉字的字符串字面量只能是例外清单里的。
package literalcheck

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"unicode"
)

// Exception 是一处允许含汉字的字面量：文件（相对扫描的根目录）、字面量的值（解开引号后的）和理由。
// 同一个文件里同样的字面量出现几次都算这一条。是别名，各测试的清单能照位置写三项（go vet 只要求具名类型写字段名）。
type Exception = struct{ File, Literal, Why string }

// 两边清单都用到的理由。
const (
	WhyLog   = "写进日志（log 的输出接到运行日志），日志不翻译"
	WhyInner = "只进日志或只在程序有缺陷时出现的内部错误"
)

// Check 扫 root 下的 dirs（含子目录；"." 只扫 root 这一层，不进子目录）：不在 exceptions 里的含汉字字面量、对不上任何字面量的例外、没写理由的例外都算失败。
func Check(t *testing.T, root string, dirs []string, exceptions []Exception) {
	t.Helper()
	allowed := map[[2]string]bool{}
	for _, e := range exceptions {
		if e.Why == "" {
			t.Errorf("例外没写理由：%s %q", e.File, e.Literal)
		}
		allowed[[2]string{e.File, e.Literal}] = false
	}
	files := 0
	for _, dir := range dirs {
		paths, err := goFiles(root, dir)
		if err != nil {
			t.Fatalf("扫 %s：%v", dir, err)
		}
		files += len(paths)
		for _, path := range paths {
			rel, _ := filepath.Rel(root, path)
			rel = filepath.ToSlash(rel)
			for _, lit := range hanLiterals(t, path) {
				key := [2]string{rel, lit.value}
				if _, ok := allowed[key]; ok {
					allowed[key] = true
					continue
				}
				t.Errorf("%s:%d：含汉字的字面量 %q。给人看的话放进文字表（docs/i18n.md），不该翻译的在 literals_test.go 的例外清单里加一条", rel, lit.line, lit.value)
			}
		}
	}
	if files == 0 {
		t.Fatal("一个 Go 文件也没扫到，目录对不上了")
	}
	for key, used := range allowed {
		if !used {
			t.Errorf("例外清单里的 %s %q 对不上任何字面量了，删掉这一条", key[0], key[1])
		}
	}
}

// goFiles 是 root 下 dir 里的非测试 Go 文件（含子目录；dir 是 "." 时只要 root 这一层）。
func goFiles(root, dir string) ([]string, error) {
	var out []string
	err := filepath.WalkDir(filepath.Join(root, dir), func(path string, d fs.DirEntry, err error) error {
		switch {
		case err != nil:
			return err
		case d.IsDir() && dir == "." && path != root:
			return filepath.SkipDir
		case !d.IsDir() && strings.HasSuffix(path, ".go") && !strings.HasSuffix(path, "_test.go"):
			out = append(out, path)
		}
		return nil
	})
	return out, err
}

type literal struct {
	value string
	line  int
}

// hanLiterals 是文件里含汉字的字符串字面量（注释不算）。
func hanLiterals(t *testing.T, path string) []literal {
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, path, nil, parser.SkipObjectResolution)
	if err != nil {
		t.Fatalf("解析 %s：%v", path, err)
	}
	var out []literal
	ast.Inspect(f, func(n ast.Node) bool {
		lit, ok := n.(*ast.BasicLit)
		if !ok || lit.Kind != token.STRING {
			return true
		}
		value, err := strconv.Unquote(lit.Value)
		if err != nil {
			t.Fatalf("%s：解不开字面量 %s：%v", path, lit.Value, err)
		}
		if strings.ContainsFunc(value, func(r rune) bool { return unicode.Is(unicode.Han, r) }) {
			out = append(out, literal{value, fset.Position(lit.Pos()).Line})
		}
		return true
	})
	return out
}
