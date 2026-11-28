package agybridge

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

// RunMCPSock 实现 `zeta-helper mcp-sock <sock>`：由 agy acp 以 stdio MCP 服务器启动，
// 把标准输入上的 MCP JSON-RPC 请求转发给 agy-bridge 监听的 Unix 套接字，再把应答写回标准输出。
// 对 agy acp 专有的 server/discover 立即回 -32601 Method not found，使它回退到标准的 initialize -> tools/list -> tools/call。
func RunMCPSock(sockPath string, stdin io.Reader, stdout io.Writer) int {
	conn, err := net.Dial("unix", sockPath)
	if err != nil {
		return 1
	}
	defer conn.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = io.Copy(stdout, conn)
	}()

	br := bufio.NewReaderSize(stdin, 64*1024)
	for {
		line, err := br.ReadBytes('\n')
		trimmed := bytes.TrimSpace(line)
		if len(trimmed) > 0 {
			if reply := interceptDiscover(trimmed); reply != nil {
				_, _ = stdout.Write(append(reply, '\n'))
			} else if _, werr := conn.Write(append(trimmed, '\n')); werr != nil {
				break
			}
		}
		if err != nil {
			break
		}
	}
	_ = conn.Close()
	<-done
	return 0
}

func interceptDiscover(line []byte) []byte {
	var probe struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
	}
	if json.Unmarshal(line, &probe) != nil || probe.Method != "server/discover" || len(probe.ID) == 0 {
		return nil
	}
	resp, _ := json.Marshal(rpcMsg{
		JSONRPC: "2.0",
		ID:      probe.ID,
		Error:   &rpcError{Code: -32601, Message: "Method not found"},
	})
	return resp
}

// mcpRelay 在 agy-bridge 进程内监听 Unix 套接字，将 mcp-sock 转来的请求包成 Zeta 的 mcp_message 控制请求，
// 收到 conv.mcp_reply 的应答后再写回对应的连接。
type mcpRelay struct {
	sockPath string
	ln       net.Listener
	emit     func(any)

	mu       sync.Mutex
	seq      int
	waiting  map[string]chan json.RawMessage
	denied   map[string]bool
	listReqs map[string]bool
}

func startMCPRelay(emit func(any)) (*mcpRelay, error) {
	dir := filepath.Join(os.TempDir(), fmt.Sprintf("zeta-mcp-%d", os.Getuid()))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	var rnd [8]byte
	_, _ = rand.Read(rnd[:])
	sock := filepath.Join(dir, hex.EncodeToString(rnd[:])+".sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		return nil, err
	}
	r := &mcpRelay{
		sockPath: sock,
		ln:       ln,
		emit:     emit,
		waiting:  map[string]chan json.RawMessage{},
		denied:   map[string]bool{},
		listReqs: map[string]bool{},
	}
	go r.acceptLoop()
	return r, nil
}

func (r *mcpRelay) setDeniedTools(tools []string) {
	next := make(map[string]bool, len(tools))
	for _, t := range tools {
		if t = strings.TrimSpace(t); t != "" {
			next[t] = true
		}
	}
	r.mu.Lock()
	r.denied = next
	r.mu.Unlock()
}

func (r *mcpRelay) isDenied(name string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.denied[name]
}

func (r *mcpRelay) deniedSnapshot() map[string]bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.denied) == 0 {
		return nil
	}
	cp := make(map[string]bool, len(r.denied))
	for k, v := range r.denied {
		cp[k] = v
	}
	return cp
}

func (r *mcpRelay) acceptLoop() {
	for {
		conn, err := r.ln.Accept()
		if err != nil {
			return
		}
		go r.serveConn(conn)
	}
}

func (r *mcpRelay) serveConn(conn net.Conn) {
	defer conn.Close()
	var writeM sync.Mutex
	br := bufio.NewReaderSize(conn, 64*1024)
	for {
		line, err := br.ReadBytes('\n')
		trimmed := bytes.TrimSpace(line)
		if len(trimmed) > 0 {
			r.forwardMessage(trimmed, func(resp []byte) {
				writeM.Lock()
				_, _ = conn.Write(append(resp, '\n'))
				writeM.Unlock()
			})
		}
		if err != nil {
			return
		}
	}
}

func (r *mcpRelay) forwardMessage(rawMsg []byte, writeBack func([]byte)) {
	var check struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params struct {
			Name string `json:"name"`
		} `json:"params"`
	}
	_ = json.Unmarshal(rawMsg, &check)
	hasID := len(bytes.TrimSpace(check.ID)) > 0 && string(bytes.TrimSpace(check.ID)) != "null"
	if !hasID {
		return
	}
	if check.Method == "tools/call" && check.Params.Name != "" && r.isDenied(check.Params.Name) {
		writeBack(deniedToolCallReply(check.ID, check.Params.Name))
		return
	}

	r.mu.Lock()
	r.seq++
	reqID := "agy-mcp-" + strconv.Itoa(r.seq)
	ch := make(chan json.RawMessage, 1)
	r.waiting[reqID] = ch
	isList := check.Method == "tools/list"
	if isList {
		r.listReqs[reqID] = true
	}
	r.mu.Unlock()

	r.emit(map[string]any{
		"type":       "control_request",
		"request_id": reqID,
		"request": map[string]any{
			"subtype": "mcp_message",
			"message": json.RawMessage(rawMsg),
		},
	})
	go func() {
		resp, ok := <-ch
		if !ok || len(bytes.TrimSpace(resp)) == 0 || string(bytes.TrimSpace(resp)) == "{}" {
			return
		}
		if isList {
			resp = filterToolsListReply(resp, r.deniedSnapshot())
		}
		writeBack(resp)
	}()
}

func deniedToolCallReply(id json.RawMessage, name string) []byte {
	b, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"id":      id,
		"result": map[string]any{
			"isError": true,
			"content": []any{map[string]any{"type": "text", "text": "Tool " + name + " is disabled during unattended runs."}},
		},
	})
	return b
}

func filterToolsListReply(raw []byte, denied map[string]bool) []byte {
	if len(denied) == 0 {
		return raw
	}
	var msg map[string]json.RawMessage
	if json.Unmarshal(raw, &msg) != nil {
		return raw
	}
	var res map[string]json.RawMessage
	if json.Unmarshal(msg["result"], &res) != nil {
		return raw
	}
	var tools []json.RawMessage
	if json.Unmarshal(res["tools"], &tools) != nil {
		return raw
	}
	kept := make([]json.RawMessage, 0, len(tools))
	for _, t := range tools {
		var hdr struct {
			Name string `json:"name"`
		}
		if json.Unmarshal(t, &hdr) == nil && denied[hdr.Name] {
			continue
		}
		kept = append(kept, t)
	}
	if len(kept) == len(tools) {
		return raw
	}
	res["tools"], _ = json.Marshal(kept)
	msg["result"], _ = json.Marshal(res)
	out, err := json.Marshal(msg)
	if err != nil {
		return raw
	}
	return out
}

func (r *mcpRelay) handleReply(reqID string, mcpResp json.RawMessage) {
	r.mu.Lock()
	ch, ok := r.waiting[reqID]
	delete(r.waiting, reqID)
	delete(r.listReqs, reqID)
	r.mu.Unlock()
	if ok && ch != nil {
		ch <- mcpResp
	}
}

func (r *mcpRelay) close() {
	if r == nil {
		return
	}
	_ = r.ln.Close()
	_ = os.Remove(r.sockPath)
	r.mu.Lock()
	for id, ch := range r.waiting {
		close(ch)
		delete(r.waiting, id)
	}
	r.mu.Unlock()
}
