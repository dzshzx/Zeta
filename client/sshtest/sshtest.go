// Package sshtest 是测试里起的 SSH 服务器（golang.org/x/crypto/ssh）：只认给定的公钥，exec 请求交给本机的 bash，
// 在临时的家目录里真跑，像一台装好的远程设备。只给测试用。
package sshtest

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"os/exec"
	"sync"
	"testing"

	"golang.org/x/crypto/ssh"
)

// Server 是起好的服务器。Home 是命令里的 HOME 和起始目录。
type Server struct {
	Addr    string
	Port    int
	HostKey ssh.Signer
	Home    string

	listener   net.Listener
	authorized [][]byte

	mu      sync.Mutex
	conns   map[net.Conn]bool
	dialed  int
	stopped bool
}

// Start 在 127.0.0.1 的随机端口上起一个服务器，只认 authorized 这几把公钥；测试结束时关掉。
func Start(t testing.TB, home string, authorized ...ssh.PublicKey) *Server {
	t.Helper()
	_, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hostKey, err := ssh.NewSignerFromKey(private)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{
		Addr: listener.Addr().String(), Port: listener.Addr().(*net.TCPAddr).Port,
		HostKey: hostKey, Home: home, listener: listener, conns: map[net.Conn]bool{},
	}
	for _, key := range authorized {
		s.authorized = append(s.authorized, key.Marshal())
	}
	go s.accept()
	t.Cleanup(s.Close)
	return s
}

// NewKey 生成一把客户端私钥。
func NewKey(t testing.TB) ssh.Signer {
	t.Helper()
	_, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(private)
	if err != nil {
		t.Fatal(err)
	}
	return signer
}

// Close 关掉服务器和所有连接。
func (s *Server) Close() {
	s.mu.Lock()
	s.stopped = true
	s.mu.Unlock()
	_ = s.listener.Close()
	s.DropAll()
}

// DropAll 断开现有的连接（像网络断了），服务器照常接新的。
func (s *Server) DropAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for c := range s.conns {
		_ = c.Close()
	}
}

// Dialed 是到现在一共接了几条连接。
func (s *Server) Dialed() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.dialed
}

// Open 是现在开着的连接数。
func (s *Server) Open() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}

func (s *Server) accept() {
	config := &ssh.ServerConfig{
		PublicKeyCallback: func(_ ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			for _, k := range s.authorized {
				if bytes.Equal(k, key.Marshal()) {
					return nil, nil
				}
			}
			return nil, io.EOF
		},
	}
	config.AddHostKey(s.HostKey)
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.conns[conn] = true
		s.dialed++
		s.mu.Unlock()
		go s.serve(conn, config)
	}
}

func (s *Server) serve(conn net.Conn, config *ssh.ServerConfig) {
	defer func() {
		_ = conn.Close()
		s.mu.Lock()
		delete(s.conns, conn)
		s.mu.Unlock()
	}()
	sc, chans, reqs, err := ssh.NewServerConn(conn, config)
	if err != nil {
		return
	}
	defer sc.Close()
	go ssh.DiscardRequests(reqs) // 保活包：要回音的答 false，客户端当它还活着
	for ch := range chans {
		if ch.ChannelType() != "session" {
			_ = ch.Reject(ssh.UnknownChannelType, "only sessions")
			continue
		}
		channel, requests, err := ch.Accept()
		if err != nil {
			continue
		}
		go s.session(channel, requests)
	}
}

// session 等 exec 请求，用 bash -c 跑那条命令；别的请求（pty、env）都拒绝。
func (s *Server) session(channel ssh.Channel, requests <-chan *ssh.Request) {
	defer channel.Close()
	for req := range requests {
		if req.Type != "exec" || len(req.Payload) < 4 {
			_ = req.Reply(false, nil)
			continue
		}
		n := binary.BigEndian.Uint32(req.Payload)
		command := string(req.Payload[4 : 4+n])
		_ = req.Reply(true, nil)
		code := s.run(channel, command)
		status := make([]byte, 4)
		binary.BigEndian.PutUint32(status, uint32(code))
		_, _ = channel.SendRequest("exit-status", false, status)
		return
	}
}

// run 在 Home 里跑 command，标准输入输出接到通道上；通道那头关了输入，命令就读到输入结束（同 sshd）。
func (s *Server) run(channel ssh.Channel, command string) int {
	cmd := exec.Command("bash", "-c", command)
	cmd.Dir = s.Home
	// PATH 只有系统的目录，像一台新装的设备：这台电脑上装在用户目录里的东西（比如 claude）不算
	cmd.Env = []string{"HOME=" + s.Home, "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "USER=zeta", "LANG=C.UTF-8"}
	cmd.Stdout = channel
	cmd.Stderr = channel.Stderr()
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return 1
	}
	if err := cmd.Start(); err != nil {
		return 127
	}
	go func() {
		_, _ = io.Copy(stdin, channel)
		_ = stdin.Close()
	}()
	err = cmd.Wait()
	if exit, ok := err.(*exec.ExitError); ok {
		return exit.ExitCode()
	}
	if err != nil {
		return 1
	}
	return 0
}
