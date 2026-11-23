package coreapi

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"testing"
)

// methodName 是核心请求方法的样子：前缀是核心挂处理器的那几个（helper/corehandlers.go 的 newCoreHandler）。
var methodName = regexp.MustCompile(`^(conv|sessions|accounts|quota|system|storage|files|images|config|diag)\.[A-Za-z_.]+$`)

// docMethods 是 docs/core.md 各张方法表（表头第一格是「方法」）第一格里列的方法。
func docMethods(t *testing.T) []string {
	b, err := os.ReadFile("../../docs/core.md")
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	inTable := false
	cell := regexp.MustCompile("`([^`]+)`")
	for line := range strings.SplitSeq(string(b), "\n") {
		if !strings.HasPrefix(line, "|") {
			inTable = false
			continue
		}
		first := strings.TrimSpace(strings.SplitN(line[1:], "|", 2)[0])
		if first == "方法" {
			inTable = true
			continue
		}
		if !inTable {
			continue
		}
		for _, m := range cell.FindAllStringSubmatch(first, -1) {
			if methodName.MatchString(m[1]) {
				out = append(out, m[1])
			}
		}
	}
	if len(out) == 0 {
		t.Fatal("docs/core.md 里一个方法也没找到：表的写法变了？")
	}
	return out
}

// apiMethods 是本包源码里（测试除外）作为调用参数出现的方法名。
func apiMethods(t *testing.T) []string {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	var out []string
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(fset, f, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		ast.Inspect(file, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			for _, a := range call.Args {
				lit, ok := a.(*ast.BasicLit)
				if !ok || lit.Kind != token.STRING {
					continue
				}
				if s, err := strconv.Unquote(lit.Value); err == nil && methodName.MatchString(s) {
					out = append(out, s)
				}
			}
			return true
		})
	}
	return out
}

// TestMethodsMatchTheDocs：docs/core.md 列的每个请求方法这里都有调用函数，这里调的每个方法那里都列着。
// 核心加了方法、改了名，文档和这里要一起改，不然这条失败。
func TestMethodsMatchTheDocs(t *testing.T) {
	docs, api := docMethods(t), apiMethods(t)
	for _, m := range docs {
		if !slices.Contains(api, m) {
			t.Errorf("docs/core.md 列了 %s，coreapi 里没有调它的函数", m)
		}
	}
	for _, m := range api {
		if !slices.Contains(docs, m) {
			t.Errorf("coreapi 调了 %s，docs/core.md 的方法表里没有它", m)
		}
	}
}
