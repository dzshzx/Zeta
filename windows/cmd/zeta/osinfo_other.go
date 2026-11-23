//go:build !windows

package main

import "github.com/dzshzx/Zeta/windows/internal/ui/diag"

// osLines 在 Windows 以外（Linux 上编、跑门禁时）没有要写的。
func osLines() []diag.Line { return nil }
