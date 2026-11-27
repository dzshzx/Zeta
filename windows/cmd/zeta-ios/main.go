//go:build cgo

// zeta-ios 是 iOS 版的核心，编成静态库（-buildmode=c-archive）链进 App：导出三个 C 函数，Swift 经它们调 internal/mobile。
// 编法见 ios/scripts/core.sh，协议见 docs/ios.md「核心桥」。
package main

/*
#include <stdlib.h>
typedef void (*zeta_changed_fn)(void);
static inline void zeta_changed(zeta_changed_fn f) { if (f) f(); }
*/
import "C"

import (
	"log"
	"path/filepath"
	"sync"
	"unsafe"

	"github.com/dzshzx/Zeta/windows/internal/mobile"
	"github.com/dzshzx/Zeta/windows/internal/runlog"
)

var (
	mu   sync.Mutex
	core *mobile.Core
)

// ZetaStart 起核心：dir 是设置目录，seed 是 32 字节的密钥种子，lang 是界面语言，device 是这台手机的名字，changed 在状态有变化时调（在 Go 的线程上）。
// 成功时返回 NULL，否则是原因（调用方用 ZetaFree 释放）。只起一次，再调返回 NULL。
//
//export ZetaStart
func ZetaStart(dir *C.char, seed unsafe.Pointer, seedLen C.int, lang, device *C.char, changed C.zeta_changed_fn) *C.char {
	mu.Lock()
	defer mu.Unlock()
	if core != nil {
		return nil
	}
	d := C.GoString(dir)
	log.SetFlags(0)
	log.SetOutput(runlog.Open(filepath.Join(d, "diag"), nil))
	c, err := mobile.Start(mobile.Config{Dir: d, Seed: C.GoBytes(seed, seedLen), Lang: C.GoString(lang), Device: C.GoString(device),
		Changed: func() { C.zeta_changed(changed) }})
	if err != nil {
		return C.CString(err.Error())
	}
	core = c
	return nil
}

// ZetaCall 调一个方法，返回结果的 JSON（调用方用 ZetaFree 释放）。核心没起时答 {"error":…}。
//
//export ZetaCall
func ZetaCall(method *C.char, params unsafe.Pointer, n C.int) *C.char {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return C.CString(`{"error":"core not started"}`)
	}
	return C.CString(string(c.Call(C.GoString(method), C.GoBytes(params, n))))
}

// ZetaFree 释放 ZetaStart、ZetaCall 返回的字符串。
//
//export ZetaFree
func ZetaFree(p *C.char) { C.free(unsafe.Pointer(p)) }

func main() {}
