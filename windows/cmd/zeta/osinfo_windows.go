package main

import (
	"syscall"
	"unsafe"

	"github.com/dzshzx/Zeta/windows/internal/ui/diag"
)

// osLines 是诊断包里的 Windows 版本：注册表 CurrentVersion 里的产品名、版本号（如 24H2）和内部版本号。读不到的项不写。
func osLines() []diag.Line {
	var key syscall.Handle
	path, _ := syscall.UTF16PtrFromString(`SOFTWARE\Microsoft\Windows NT\CurrentVersion`)
	if syscall.RegOpenKeyEx(syscall.HKEY_LOCAL_MACHINE, path, 0, syscall.KEY_READ, &key) != nil {
		return nil
	}
	defer syscall.RegCloseKey(key)
	value := ""
	for _, name := range []string{"ProductName", "DisplayVersion", "CurrentBuild"} {
		if s := regString(key, name); s != "" {
			if value != "" {
				value += " · "
			}
			value += s
		}
	}
	if value == "" {
		return nil
	}
	return []diag.Line{{Name: "Windows", Value: value}}
}

// regString 读一个字符串值，读不到是空。
func regString(key syscall.Handle, name string) string {
	p, _ := syscall.UTF16PtrFromString(name)
	buf := make([]uint16, 256)
	n := uint32(len(buf) * 2)
	var typ uint32
	if syscall.RegQueryValueEx(key, p, nil, &typ, (*byte)(unsafe.Pointer(&buf[0])), &n) != nil || typ != syscall.REG_SZ {
		return ""
	}
	return syscall.UTF16ToString(buf[:n/2])
}
