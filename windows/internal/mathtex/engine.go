// Package mathtex 排 LaTeX 公式：用的是手机版同一个 RaTeX（npm 包 ratex-wasm 里编好的 wasm，版本同 gradle/libs.versions.toml 的 ratex），
// 由 wazero 的解释器跑（纯 Go，不要 cgo），排出的绘制清单（字形、线、矩形、路径，以 em 为单位）用 KaTeX 字体画（draw.go）。
// wasm 和字体由 windows/scripts/assets.sh 放进 assets/gen/ratex。
//
// 第一次排公式时才起 wasm（解释器起来约 0.1 秒），之后一条公式约 1 毫秒；排好的按公式和模式缓存。
// 调用方在哪个 goroutine 都行，同一时刻只排一条。
package mathtex

import (
	"cmp"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/windows/internal/assets"
	"github.com/tetratelabs/wazero"
	"github.com/tetratelabs/wazero/api"
)

// cacheLimit 是缓存最多留几条：满了整个清掉重来（一个对话里的公式远没有这么多）。
const cacheLimit = 2048

// memoryPages 是 wasm 内存的上限（64 KiB 一页，共 256 MiB）：公式出了毛病也不至于吃光内存。
const memoryPages = 4096

type cacheKey struct {
	tex     string
	display bool
}

type cached struct {
	list *List
	err  error
}

var eng struct {
	mu    sync.Mutex
	rt    *runtime
	err   error // 起 wasm 失败（assets 坏了）：之后都不再试
	cache map[cacheKey]cached
}

// Layout 排一条公式：display 为真是块公式（\displaystyle），否则是行内的。排不出的（语法错、不认识的命令）返回错误，调用方照原文显示。
func Layout(tex string, display bool) (*List, error) {
	eng.mu.Lock()
	defer eng.mu.Unlock()
	k := cacheKey{tex, display}
	if c, ok := eng.cache[k]; ok {
		return c.list, c.err
	}
	if eng.err != nil {
		return nil, eng.err
	}
	if eng.rt == nil {
		rt, err := start()
		if err != nil {
			eng.err = fmt.Errorf("mathtex: 起不来 RaTeX：%w", err)
			return nil, eng.err
		}
		eng.rt = rt
	}
	out, err := eng.rt.render(tex, display)
	var crash *crashError
	if errors.As(err, &crash) {
		// wasm 停在了半路（Rust 里 panic）：内存里的状态不可信，下次换一个新实例；这条记为排不出
		eng.rt.close()
		eng.rt = nil
	}
	var l *List
	if err == nil {
		l, err = decode(out)
	}
	if eng.cache == nil || len(eng.cache) >= cacheLimit {
		eng.cache = map[cacheKey]cached{}
	}
	eng.cache[k] = cached{l, err}
	return l, err
}

// runtime 是一个跑着的 RaTeX 实例。
type runtime struct {
	ctx                      context.Context
	rt                       wazero.Runtime
	mod                      api.Module
	sp                       api.Function // __wbindgen_add_to_stack_pointer
	alloc, free, renderLatex api.Function
	// thrown 是 wasm 交给宿主的上一个字符串：renderLatex 出错时 JsValue::from_str 的说明就是它
	thrown string
}

// crashError 是 wasm 自己停了（trap），不是公式排不出。
type crashError struct{ err error }

func (e *crashError) Error() string { return "RaTeX 停了：" + e.err.Error() }
func (e *crashError) Unwrap() error { return e.err }

func start() (*runtime, error) {
	code, err := assets.Read("ratex/ratex.wasm")
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	cfg := wazero.NewRuntimeConfigInterpreter().WithMemoryLimitPages(memoryPages)
	r := &runtime{ctx: ctx, rt: wazero.NewRuntimeWithConfig(ctx, cfg)}
	compiled, err := r.rt.CompileModule(ctx, code)
	if err != nil {
		r.close()
		return nil, err
	}
	if err := r.hostModule(compiled); err != nil {
		r.close()
		return nil, err
	}
	r.mod, err = r.rt.InstantiateModule(ctx, compiled, wazero.NewModuleConfig().WithName("ratex").WithStartFunctions())
	if err != nil {
		r.close()
		return nil, err
	}
	r.sp = r.mod.ExportedFunction("__wbindgen_add_to_stack_pointer")
	r.alloc = r.mod.ExportedFunction("__wbindgen_export") // __wbindgen_malloc(len, align)
	r.free = r.mod.ExportedFunction("__wbindgen_export3") // __wbindgen_free(ptr, len, align)
	r.renderLatex = r.mod.ExportedFunction("renderLatex") // (retptr, latex, len, color, len, displayMode)
	if r.sp == nil || r.alloc == nil || r.free == nil || r.renderLatex == nil {
		r.close()
		return nil, errors.New("wasm 里缺 renderLatex 或 wasm-bindgen 的导出（ratex-wasm 换了版本？）")
	}
	if _, err := r.mod.ExportedFunction("__wbindgen_start").Call(ctx); err != nil {
		r.close()
		return nil, err
	}
	return r, nil
}

// hostModule 提供 wasm-bindgen 生成的 JS 胶水（ratex_wasm.js 的 __wbg_get_imports）在浏览器里给的那几个函数。
// 只用 renderLatex（字符串进、字符串出），用不到 JS 对象：判断类型的一律答「不是」，取值的答「没有」。
// 名字末尾是 wasm-bindgen 加的 16 位哈希，按去掉哈希的名字认；认不得的（换了 ratex-wasm 的版本）起不来，报出名字。
func (r *runtime) hostModule(compiled wazero.CompiledModule) error {
	var module string
	b := r.rt.NewHostModuleBuilder("")
	for _, f := range compiled.ImportedFunctions() {
		mod, name, _ := f.Import()
		if module == "" {
			module = mod
			b = r.rt.NewHostModuleBuilder(mod)
		} else if mod != module {
			return fmt.Errorf("wasm 从两个模块导入：%s、%s", module, mod)
		}
		fn, ok := r.hostFunc(unhashed(name))
		if !ok {
			return fmt.Errorf("wasm 要的 %s 这里没有", name)
		}
		b.NewFunctionBuilder().WithGoModuleFunction(fn, f.ParamTypes(), f.ResultTypes()).Export(name)
	}
	_, err := b.Instantiate(r.ctx)
	return err
}

func (r *runtime) hostFunc(name string) (api.GoModuleFunc, bool) {
	switch name {
	case "__wbg___wbindgen_string_get", "__wbg___wbindgen_number_get":
		// (retptr, obj)：在 retptr 写「没有」（指针和长度、或有无和数值，都写 0）
		return func(_ context.Context, m api.Module, s []uint64) { m.Memory().Write(uint32(s[0]), make([]byte, 16)) }, true
	case "__wbg___wbindgen_is_object", "__wbg___wbindgen_is_undefined", "__wbg_get":
		return func(_ context.Context, _ api.Module, s []uint64) { s[0] = 0 }, true
	case "__wbg___wbindgen_is_null":
		return func(_ context.Context, _ api.Module, s []uint64) { s[0] = boolWord(s[0] == 0) }, true
	case "__wbg___wbindgen_boolean_get":
		return func(_ context.Context, _ api.Module, s []uint64) { s[0] = 0xFFFFFF }, true // 不是布尔值
	case "__wbg___wbindgen_throw":
		return func(_ context.Context, m api.Module, s []uint64) {
			msg, _ := m.Memory().Read(uint32(s[0]), uint32(s[1]))
			panic(errors.New(string(msg)))
		}, true
	case "__wbindgen_init_externref_table":
		return func(context.Context, api.Module, []uint64) {}, true
	case "__wbindgen_generic":
		// Ref(String) -> Externref：只有出错时用来带说明，记下字符串，给 wasm 一个非空的引用
		return func(_ context.Context, m api.Module, s []uint64) {
			msg, _ := m.Memory().Read(uint32(s[0]), uint32(s[1]))
			r.thrown = string(msg)
			s[0] = 1
		}, true
	}
	return nil, false
}

func boolWord(b bool) uint64 {
	if b {
		return 1
	}
	return 0
}

// render 照 ratex_wasm.js 的 renderLatex 调一次，返回绘制清单的 JSON。默认色传全透明：
// 没用 \color 的部分在清单里是透明的，画的时候换成正文的颜色（同手机版 RaTeXMath 的做法）。
func (r *runtime) render(tex string, display bool) (string, error) {
	res, err := r.sp.Call(r.ctx, api.EncodeI32(-16))
	if err != nil {
		return "", &crashError{err}
	}
	ret := uint32(res[0])
	defer r.sp.Call(r.ctx, 16) //nolint:errcheck // 只是还原栈指针
	texPtr, texLen, err := r.put(tex)
	if err != nil {
		return "", err
	}
	colorPtr, colorLen, err := r.put("transparent")
	if err != nil {
		return "", err
	}
	mode := uint64(0)
	if display {
		mode = 1
	}
	r.thrown = ""
	if _, err := r.renderLatex.Call(r.ctx, uint64(ret), texPtr, texLen, colorPtr, colorLen, mode); err != nil {
		return "", &crashError{err}
	}
	words, ok := r.mod.Memory().Read(ret, 16)
	if !ok {
		return "", &crashError{errors.New("读不到返回值")}
	}
	ptr, n, failed := binary.LittleEndian.Uint32(words), binary.LittleEndian.Uint32(words[4:]), binary.LittleEndian.Uint32(words[12:])
	if failed != 0 {
		return "", errors.New(cmp.Or(r.thrown, "RaTeX 排不出"))
	}
	out, ok := r.mod.Memory().Read(ptr, n)
	if !ok {
		return "", &crashError{errors.New("读不到结果")}
	}
	s := string(out)
	if _, err := r.free.Call(r.ctx, uint64(ptr), uint64(n), 1); err != nil {
		return "", &crashError{err}
	}
	return s, nil
}

// put 把 s 拷进 wasm 的内存（renderLatex 用完自己释放），返回地址和长度。
func (r *runtime) put(s string) (uint64, uint64, error) {
	res, err := r.alloc.Call(r.ctx, uint64(len(s)), 1)
	if err != nil {
		return 0, 0, &crashError{err}
	}
	if !r.mod.Memory().WriteString(uint32(res[0]), s) {
		return 0, 0, &crashError{errors.New("写不进内存")}
	}
	return res[0], uint64(len(s)), nil
}

func (r *runtime) close() {
	_ = r.rt.Close(r.ctx)
}

// unhashed 去掉 wasm-bindgen 加在导入名末尾的「_16 位十六进制」。
func unhashed(name string) string {
	i := strings.LastIndexByte(name, '_')
	if i < 0 || len(name)-i-1 != 16 || strings.Trim(name[i+1:], "0123456789abcdef") != "" {
		return name
	}
	return name[:i]
}

// decode 解 renderLatex 的 JSON（ratex-types 的 DisplayList，带 version）。
func decode(s string) (*List, error) {
	var raw struct {
		Version int       `json:"version"`
		Items   []rawItem `json:"items"`
		Width   float32   `json:"width"`
		Height  float32   `json:"height"`
		Depth   float32   `json:"depth"`
	}
	if err := json.Unmarshal([]byte(s), &raw); err != nil {
		return nil, fmt.Errorf("读不懂 RaTeX 的结果：%w", err)
	}
	if raw.Version != 1 {
		return nil, fmt.Errorf("RaTeX 结果的版本是 %d，这里只认 1", raw.Version)
	}
	l := &List{Width: raw.Width, Height: raw.Height, Depth: raw.Depth}
	for _, it := range raw.Items {
		if item, ok := it.item(); ok {
			l.Items = append(l.Items, item)
		}
	}
	return l, nil
}
