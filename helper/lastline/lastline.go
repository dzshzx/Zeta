// Package lastline 从一行一个 JSON 的文件（会话记录、托管输出日志）往回找最后一行合条件的：从后往前一块块读，
// 要找的通常就在末尾附近，不用读整个文件。
package lastline

import (
	"bytes"
	"io"
	"slices"
)

// chunk 是往回一次读多少。
const chunk = 1 << 20

// Find 往回找 f 的 [lo, hi) 里最后一行 match 的，交给 match 的行不带换行。lo 要是一行的开头；hi 不是一行的开头时，
// 末尾那半行照样交给 match（它解不开 JSON，自然不算）。一行都不合时 found 为 false。
func Find(f io.ReaderAt, lo, hi int64, match func(line []byte) bool) (found bool, err error) {
	var carry []byte // 上一块开头那半行：接在这一块后面
	for end := hi; end > lo; {
		start := max(lo, end-chunk)
		buf := make([]byte, end-start, end-start+int64(len(carry)))
		n, err := f.ReadAt(buf, start)
		if err != nil && err != io.EOF {
			return false, err
		}
		data := append(buf[:n], carry...)
		carry = nil
		if start > lo {
			// 第一个换行之前的可能是半行，留给前一块
			i := bytes.IndexByte(data, '\n')
			if i < 0 {
				carry, end = data, start
				continue
			}
			carry, data = slices.Clone(data[:i+1]), data[i+1:]
		}
		lines := bytes.SplitAfter(data, []byte("\n"))
		for i := len(lines) - 1; i >= 0; i-- {
			if line := bytes.TrimSuffix(lines[i], []byte("\n")); len(line) > 0 && match(line) {
				return true, nil
			}
		}
		end = start
	}
	return false, nil
}

// LineStart 是 r 里第 n 行（从 1 起）开头的字节位置；不到 n 行时是 r 的长度。
func LineStart(r io.Reader, n int) (int64, error) {
	var pos int64
	buf := make([]byte, 64<<10)
	for left := n - 1; left > 0; {
		k, err := r.Read(buf)
		for i := 0; i < k && left > 0; {
			j := bytes.IndexByte(buf[i:k], '\n')
			if j < 0 {
				pos += int64(k - i)
				break
			}
			pos += int64(j + 1)
			i += j + 1
			left--
		}
		if err == io.EOF {
			return pos, nil
		}
		if err != nil {
			return 0, err
		}
	}
	return pos, nil
}
