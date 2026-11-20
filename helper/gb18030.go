package main

import "golang.org/x/text/encoding/simplifiedchinese"

// decodeGB18030 按 GB18030 解码；有不合法或不完整的序列时 ok 为假（调用方再回落到 UTF-8）。
// x/text 遇到不合法的序列写替代字符而不报错，单个 0x80 还按 Windows 936 解成“€”，所以先按 GB18030 的结构核一遍。
func decodeGB18030(data []byte) (string, bool) {
	for i := 0; i < len(data); {
		n := gb18030Len(data[i:])
		if n == 0 {
			return "", false
		}
		i += n
	}
	out, err := simplifiedchinese.GB18030.NewDecoder().Bytes(data)
	if err != nil {
		return "", false
	}
	return string(out), true
}

// gb18030Len 是 b 开头一个合法 GB18030 字符的字节数；不合法、不完整时为 0。
// 单字节 0x00–0x7F；两字节首字节 0x81–0xFE、尾字节 0x40–0x7E 或 0x80–0xFE（这些码都有字）；
// 四字节 0x81–0xFE 0x30–0x39 0x81–0xFE 0x30–0x39，只有落在基本平面段（0x81308130–0x8431A439）
// 和辅助平面段（0x90308130–0xE3329A35）的才有字。
func gb18030Len(b []byte) int {
	c := b[0]
	switch {
	case c < 0x80:
		return 1
	case c == 0x80 || c == 0xFF || len(b) < 2:
		return 0
	}
	t := b[1]
	if t >= 0x40 && t <= 0xFE && t != 0x7F {
		return 2
	}
	if t < 0x30 || t > 0x39 || len(b) < 4 || b[2] < 0x81 || b[2] > 0xFE || b[3] < 0x30 || b[3] > 0x39 {
		return 0
	}
	const bmpCodes, supplementaryStart = 39420, 189000 // 0x8431A439 之后第一个有字的四字节码是 0x90308130
	index := ((int(c-0x81)*10+int(t-0x30))*126+int(b[2]-0x81))*10 + int(b[3]-0x30)
	if index < bmpCodes || (index >= supplementaryStart && index < supplementaryStart+0x100000) {
		return 4
	}
	return 0
}
