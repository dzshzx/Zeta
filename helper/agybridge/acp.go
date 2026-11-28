// Package agybridge 把 Antigravity CLI 的 ACP 协议（agy acp --process-per-session，JSON-RPC 2.0）
// 桥接成 Zeta 托管层（zeta_host.sh 与 helper/conv）使用的 stream-json 与 control_request/control_response 协议，
// 并提供无端口 Unix 套接字的手机工具 MCP 转发（mcp-sock）。
package agybridge

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
)

// rpcMsg 是双向通用的 JSON-RPC 2.0 消息。
type rpcMsg struct {
	JSONRPC string          `json:"jsonrpc,omitempty"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

// acpClient 负责起一个 agy acp --process-per-session 子进程并做 JSON-RPC 2.0 收发。
type acpClient struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	writeM sync.Mutex

	seq     atomic.Int64
	pendM   sync.Mutex
	pending map[string]chan rpcMsg

	onNotify  func(method string, params json.RawMessage)
	onRequest func(id json.RawMessage, method string, params json.RawMessage)
	done      chan struct{}
}

func buildACPArgs(extraDirs []string, agent string, sandbox bool) []string {
	args := []string{"acp", "--process-per-session"}
	for _, d := range extraDirs {
		if d != "" {
			args = append(args, "--add-dir", d)
		}
	}
	if agent != "" {
		args = append(args, "--agent", agent)
	}
	if sandbox {
		args = append(args, "--sandbox")
	}
	return args
}

func startACP(agyBin, cwd string, extraDirs []string, agent string, sandbox bool, stderr io.Writer, onNotify func(string, json.RawMessage), onReq func(json.RawMessage, string, json.RawMessage)) (*acpClient, error) {
	if agyBin == "" {
		agyBin = "agy"
	}
	cmd := exec.Command(agyBin, buildACPArgs(extraDirs, agent, sandbox)...)
	if cwd != "" {
		cmd.Dir = cwd
	}
	cmd.Env = append(os.Environ(), "BROWSER=true")
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGTERM}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	c := &acpClient{
		cmd:       cmd,
		stdin:     stdin,
		pending:   map[string]chan rpcMsg{},
		onNotify:  onNotify,
		onRequest: onReq,
		done:      make(chan struct{}),
	}
	go c.readLoop(stdout)
	return c, nil
}

func (c *acpClient) readLoop(r io.Reader) {
	defer func() {
		_ = c.cmd.Wait()
		c.failAllPending()
		close(c.done)
	}()
	br := bufio.NewReaderSize(r, 128*1024)
	for {
		line, err := br.ReadBytes('\n')
		trimmed := bytes.TrimSpace(line)
		if len(trimmed) > 0 && trimmed[0] == '{' {
			c.dispatchLine(trimmed)
		}
		if err != nil {
			return
		}
	}
}

func (c *acpClient) dispatchLine(line []byte) {
	var m rpcMsg
	if json.Unmarshal(line, &m) != nil {
		return
	}
	switch {
	case m.Method != "" && len(m.ID) > 0 && string(m.ID) != "null":
		if c.onRequest != nil {
			go c.onRequest(m.ID, m.Method, m.Params)
		}
	case m.Method != "":
		if c.onNotify != nil {
			c.onNotify(m.Method, m.Params)
		}
	case len(m.ID) > 0:
		key := idKey(m.ID)
		c.pendM.Lock()
		ch := c.pending[key]
		delete(c.pending, key)
		c.pendM.Unlock()
		if ch != nil {
			ch <- m
		}
	}
}

func (c *acpClient) failAllPending() {
	c.pendM.Lock()
	all := c.pending
	c.pending = map[string]chan rpcMsg{}
	c.pendM.Unlock()
	for _, ch := range all {
		ch <- rpcMsg{Error: &rpcError{Code: -32000, Message: "agy acp exited"}}
	}
}

func (c *acpClient) call(method string, params any) (json.RawMessage, error) {
	id := c.seq.Add(1)
	rawID := json.RawMessage(strconv.FormatInt(id, 10))
	key := strconv.FormatInt(id, 10)
	ch := make(chan rpcMsg, 1)
	c.pendM.Lock()
	select {
	case <-c.done:
		c.pendM.Unlock()
		return nil, errors.New("agy acp exited")
	default:
	}
	c.pending[key] = ch
	c.pendM.Unlock()

	pBytes, err := json.Marshal(params)
	if err != nil {
		return nil, err
	}
	if err := c.sendRaw(rpcMsg{JSONRPC: "2.0", ID: rawID, Method: method, Params: pBytes}); err != nil {
		c.pendM.Lock()
		delete(c.pending, key)
		c.pendM.Unlock()
		return nil, err
	}
	resp := <-ch
	if resp.Error != nil {
		return nil, errors.New(resp.Error.Message)
	}
	return resp.Result, nil
}

func (c *acpClient) reply(id json.RawMessage, result any) error {
	b, err := json.Marshal(result)
	if err != nil {
		return err
	}
	return c.sendRaw(rpcMsg{JSONRPC: "2.0", ID: id, Result: b})
}

func (c *acpClient) replyError(id json.RawMessage, code int, message string) error {
	return c.sendRaw(rpcMsg{JSONRPC: "2.0", ID: id, Error: &rpcError{Code: code, Message: message}})
}

func (c *acpClient) sendRaw(msg rpcMsg) error {
	b, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	c.writeM.Lock()
	defer c.writeM.Unlock()
	_, err = c.stdin.Write(b)
	return err
}

func (c *acpClient) close() {
	_ = c.stdin.Close()
	if c.cmd.Process != nil {
		_ = c.cmd.Process.Signal(syscall.SIGTERM)
	}
}

func idKey(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return "s:" + s
	}
	return string(bytes.TrimSpace(raw))
}
