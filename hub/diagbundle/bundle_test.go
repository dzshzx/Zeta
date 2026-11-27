package diagbundle

import (
	"testing"
)

func TestUniqueDir(t *testing.T) {
	used := map[string]bool{}
	for _, c := range []struct{ name, want string }{
		{"box", "box"},
		{"box", "box (2)"},
		{`a/b:c*?"<>|`, "a_b_c______"},
		{" . ", "设备"},
		{"", "设备 (2)"},
	} {
		if got := uniqueDir(used, c.name); got != c.want {
			t.Errorf("uniqueDir(%q) = %q，要 %q", c.name, got, c.want)
		}
	}
}

func TestUTF16LE(t *testing.T) {
	// 从头读：去掉 BOM；代理对拼成一个字
	if got := utf16le([]byte{0xFF, 0xFE, 'o', 0, 'k', 0, 0x3D, 0xD8, 0x00, 0xDE}, 0); got != "ok😀" {
		t.Errorf("从头读：%q", got)
	}
	// 从奇数位置截的：先丢一个字节对齐，开头不是 BOM 不丢；落单的代理项换成替代符
	if got := utf16le([]byte{0x00, 'o', 0, 0x3D, 0xD8}, 3); got != "o�" {
		t.Errorf("截断的：%q", got)
	}
}
