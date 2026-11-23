package locales

// 核心的文字表：核心按客户端的语言出的字（应答里的出错、导出），和客户端按核心给的种类码出的字（条目里的提示、状态等），
// 做法见 docs/i18n.md。核心里只用 Msg 和 Error（按某个客户端的语言 In(lang) 出字），不用生成的不带语言的取字函数：
// 那些按「程序当前的语言」出字，核心不是界面程序，没有这个语言。

// Msg 是一句按语言出字的话：文字表里的键和参数。推给客户端时 Key 是种类码（code），Args 是参数（args）。
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
func (m Msg) In(lang string) string { return Catalog.Format(lang, m.Key, m.Args) }

// String 是这句话的源语言，写日志、给不认种类码的旧客户端。
func (m Msg) String() string { return m.In("") }

// Error 是给客户端看的出错：Error() 是源语言，core.Client.Reply 按那条连接的语言出字，并带上种类码和参数。
type Error struct{ Msg }

// Err 是键 key 的出错，args 同 M。
func Err(key string, args ...any) Error { return Error{M(key, args...)} }

func (e Error) Error() string { return e.String() }

// Code 是种类码，客户端按它判断是哪种出错，不比对文字。
func (e Error) Code() string { return e.Key }
