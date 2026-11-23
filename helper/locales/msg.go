package locales

import (
	"errors"
	"maps"
	"strings"
)

// 核心的文字表：核心按客户端的语言出的字（应答里的出错、导出），和客户端按核心给的种类码出的字（条目里的提示、状态等），
// 做法见 docs/i18n.md。核心里只用 Msg 和 Error（按某个客户端的语言 In(lang) 出字），不用生成的不带语言的取字函数：
// 那些按「程序当前的语言」出字，核心不是界面程序，没有这个语言。

// Msg 是一句按语言出字的话：文字表里的键和参数。推给客户端时 Key 是种类码（code），Args 是参数（args）。
//
// 参数可以是另一句话（Msg，编成 JSON 是 {"code","args"} 对象，客户端解回来是带 code 的 map）或几句话（[]Msg，按这种语言的
// 顿号连起来，只用在核心自己出字的地方），出字时按同一种语言先说出来。
type Msg struct {
	Key  string         `json:"code"`
	Args map[string]any `json:"args,omitempty"`
}

// M 是键 key 的一句话，args 是成对的参数名和值。
func M(key string, args ...any) Msg {
	m := Msg{Key: key}
	for i := 0; i+1 < len(args); i += 2 {
		if m.Args == nil {
			m.Args = map[string]any{}
		}
		m.Args[args[i].(string)] = args[i+1]
	}
	return m
}

// In 是这句话在语言 lang 里怎么说（"" 是源语言简体中文；没有译文时也是源语言）。
func (m Msg) In(lang string) string { return Catalog.Format(lang, m.Key, m.argsIn(lang)) }

// argsIn 是出字用的参数：嵌着的话按 lang 说出来。没有嵌着的话时就是 Args。
func (m Msg) argsIn(lang string) map[string]any {
	var out map[string]any
	for k, v := range m.Args {
		var said string
		switch v := v.(type) {
		case Msg:
			said = v.In(lang)
		case []Msg:
			said = joinIn(lang, v)
		case map[string]any: // 客户端从 JSON 解回来的一句话
			code, ok := v["code"].(string)
			if !ok {
				continue
			}
			args, _ := v["args"].(map[string]any)
			said = Msg{Key: code, Args: args}.In(lang)
		default:
			continue
		}
		if out == nil {
			out = maps.Clone(m.Args)
		}
		out[k] = said
	}
	if out == nil {
		return m.Args
	}
	return out
}

// joinIn 把几句话按 lang 说出来，用这种语言的顿号（listSeparator）连起来。
func joinIn(lang string, msgs []Msg) string {
	parts := make([]string, len(msgs))
	for i, m := range msgs {
		parts[i] = m.In(lang)
	}
	return strings.Join(parts, M("listSeparator").In(lang))
}

// String 是这句话的源语言，写日志、给不认种类码的旧客户端。
func (m Msg) String() string { return m.In("") }

// Error 是给客户端看的出错：Error() 是源语言，core.Client.Reply 按那条连接的语言出字，并带上种类码和参数。
// 用指针（Err 给的就是）：哨兵错误能用 == 和 errors.Is 比较。
type Error struct{ Msg }

// Err 是键 key 的出错，args 同 M。
func Err(key string, args ...any) *Error { return &Error{M(key, args...)} }

func (e *Error) Error() string { return e.String() }

// Code 是种类码，客户端按它判断是哪种出错，不比对文字。
func (e *Error) Code() string { return e.Key }

// ErrArg 是把 err 放进另一句话的参数时的值：错误链里有 *Error 时是它那句话（按出字的语言说），否则是 err.Error() 原文。
func ErrArg(err error) any {
	if said, ok := errors.AsType[*Error](err); ok {
		return said.Msg
	}
	return err.Error()
}

// Said 是 err 在语言 lang 里怎么说：错误链里有 *Error 时按它出字，否则是 err.Error()（原文，比如 Claude Code 给的）。
func Said(err error, lang string) string {
	if said, ok := errors.AsType[*Error](err); ok {
		return said.In(lang)
	}
	return err.Error()
}
