package core

import (
	"errors"
	"strings"
)

// Mux 按方法名的前缀（比如 "conv."、"config."）把请求分给各处理器，自己也是一个 Handler：核心上挂的就是它。
// 断开时每个处理器都收到 Gone；全部处理器都闲着才算闲着。
type Mux struct {
	routes []route
}

type route struct {
	prefix string
	h      Handler
}

// Handle 把以 prefix 开头的方法交给 h。在 Serve 之前注册完；前缀有包含关系时长的优先。
func (m *Mux) Handle(prefix string, h Handler) {
	m.routes = append(m.routes, route{prefix, h})
}

// HandleAsync 同 Handle，只是这些请求各在自己的 goroutine 里跑、做完再答，不挡同一个客户端后面的请求（要等外部程序的慢请求用）。
// 应答的先后因此不一定和请求一样。
func (m *Mux) HandleAsync(prefix string, h Handler) {
	m.Handle(prefix, async{h})
}

type async struct{ Handler }

func (a async) Request(c *Client, r Request) (any, error) {
	go func() {
		result, err := a.Handler.Request(c, r)
		c.Reply(r.ID, result, err)
	}()
	return Later, nil
}

func (m *Mux) Request(c *Client, r Request) (any, error) {
	var best *route
	for i, rt := range m.routes {
		if strings.HasPrefix(r.Method, rt.prefix) && (best == nil || len(rt.prefix) > len(best.prefix)) {
			best = &m.routes[i]
		}
	}
	if best == nil {
		return nil, errors.New("核心不认识的方法 " + r.Method)
	}
	return best.h.Request(c, r)
}

func (m *Mux) Gone(c *Client) {
	for _, rt := range m.routes {
		rt.h.Gone(c)
	}
}

// Release：交班时让各处理器放开它们接着的东西。
func (m *Mux) Release() {
	for _, rt := range m.routes {
		if r, ok := rt.h.(Releaser); ok {
			r.Release()
		}
	}
}

func (m *Mux) Idle() bool {
	for _, rt := range m.routes {
		if !rt.h.Idle() {
			return false
		}
	}
	return true
}

func (a async) Release() {
	if r, ok := a.Handler.(Releaser); ok {
		r.Release()
	}
}
