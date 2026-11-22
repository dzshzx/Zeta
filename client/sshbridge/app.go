package sshbridge

import (
	"context"
	"errors"
	"io"
	"net"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"golang.org/x/crypto/ssh"
)

// 请应用做的事（call 的 method）：Android 原生的几样，Go 这边做不了或不该做。
const (
	callKey     = "key"     // Zeta 自己那把密钥的公钥（SSH 线上格式），私钥在 Keystore 里，取不出来
	callSign    = "sign"    // 用那把私钥给 data 签名，答原始的签名（Ed25519 是 64 字节）
	callResolve = "resolve" // 经系统解析域名 host，答一串 IP 地址
)

// callMsg 是请应用做一件事；应用答 {"method":"answer","id":…}。
type callMsg struct {
	Type   string `json:"type"`
	ID     int64  `json:"id"`
	Method string `json:"method"`
	Data   []byte `json:"data,omitempty"`
	Host   string `json:"host,omitempty"`
}

// answer 是应用的答复：成了是 data 或 addrs，不成是 error（给人看的原因）。
type answer struct {
	data  []byte
	addrs []string
	err   string
}

// call 请应用做 method，等它答（最多 callWait，ctx 取消时不等了）。
func (s *server) call(ctx context.Context, msg callMsg) (answer, error) {
	reply := make(chan answer, 1)
	s.mu.Lock()
	s.nextID++
	msg.ID, msg.Type = s.nextID, "call"
	if s.calls == nil {
		s.mu.Unlock()
		return answer{}, errors.New("应用已经断开")
	}
	s.calls[msg.ID] = reply
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.calls != nil {
			delete(s.calls, msg.ID)
		}
		s.mu.Unlock()
	}()
	if err := s.send(msg); err != nil {
		return answer{}, err
	}
	timer := time.NewTimer(callWait)
	defer timer.Stop()
	select {
	case a, ok := <-reply:
		if !ok {
			return answer{}, errors.New("应用已经断开")
		}
		if a.err != "" {
			return a, errors.New(a.err)
		}
		return a, nil
	case <-timer.C:
		return answer{}, errors.New("应用没有回应")
	case <-ctx.Done():
		return answer{}, ctx.Err()
	}
}

// answer 把应用的答复交给等着的那个 call；没人等（超时了）的扔掉。
func (s *server) answer(r request) {
	s.mu.Lock()
	reply := s.calls[r.ID]
	s.mu.Unlock()
	if reply == nil {
		return
	}
	select {
	case reply <- answer{data: r.Data, addrs: r.Addrs, err: r.Error}:
	default:
	}
}

// failCalls：应用断开了，等着的 call 都不等了，之后的直接失败。
func (s *server) failCalls() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, reply := range s.calls {
		close(reply)
	}
	s.calls = nil
}

// appSigner 是 Zeta 自己那把密钥：公钥取来放在这里，签名每次请应用在 Keystore 里做。
type appSigner struct {
	s   *server
	pub ssh.PublicKey
}

func (k *appSigner) PublicKey() ssh.PublicKey { return k.pub }

// Sign 见 ssh.Signer：请应用签，签名格式照公钥的类型（Ed25519 是 ssh-ed25519）。
func (k *appSigner) Sign(_ io.Reader, data []byte) (*ssh.Signature, error) {
	a, err := k.s.call(context.Background(), callMsg{Method: callSign, Data: data})
	if err != nil {
		return nil, err
	}
	return &ssh.Signature{Format: k.pub.Type(), Blob: a.data}, nil
}

// signer 是 Zeta 的密钥，第一次要时问应用取公钥；取不到的不记，下次再问。
func (s *server) signer() (*appSigner, error) {
	s.mu.Lock()
	key := s.key
	s.mu.Unlock()
	if key != nil {
		return key, nil
	}
	a, err := s.call(context.Background(), callMsg{Method: callKey})
	if err != nil {
		return nil, err
	}
	pub, err := ssh.ParsePublicKey(a.data)
	if err != nil {
		return nil, err
	}
	key = &appSigner{s: s, pub: pub}
	s.mu.Lock()
	s.key = key
	s.mu.Unlock()
	return key, nil
}

// appAuth 是手机上直连用的认证：地址只认写明用户的，用 Zeta 自己的密钥登录。
type appAuth struct{ s *server }

// directHint 是直连地址的写法（同 shared 的 RemoteHosts.DIRECT_TARGET_HINT）。
const directHint = "地址写成 用户@主机，端口不是 22 的写成 用户@主机:端口"

// Resolve 见 sshlink.Resolver。
func (a appAuth) Resolve(target, _ string) (sshlink.Route, error) {
	t, ok := sshlink.ParseTarget(target)
	if !ok {
		return sshlink.Route{}, sshlink.Problem(directHint)
	}
	signer, err := a.s.signer()
	if err != nil {
		return sshlink.Route{}, sshlink.Problem("取不到 Zeta 的密钥：" + err.Error())
	}
	return sshlink.Route{Target: t, Signers: []ssh.Signer{signer}, Keys: "Zeta 的密钥"}, nil
}

// Rejected 见 sshlink.Resolver。
func (a appAuth) Rejected(sshlink.Route) string {
	return "对方不认 Zeta 的公钥：把它加进那台设备的 ~/.ssh/authorized_keys"
}

// dial 连 addr：地址是 IP 的直接连；是域名的请应用经系统解析（Android 上 Go 自己解析不了：没有 /etc/resolv.conf，
// 也不用 cgo 调系统的解析），再按应用给的先后一个个试。
func (s *server) dial(ctx context.Context, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	var d net.Dialer
	if net.ParseIP(host) != nil {
		return d.DialContext(ctx, "tcp", addr)
	}
	a, err := s.call(ctx, callMsg{Method: callResolve, Host: host})
	if err == nil && len(a.addrs) == 0 {
		err = errors.New("没有地址")
	}
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, &net.DNSError{Err: err.Error(), Name: host, IsNotFound: true}
	}
	var last error
	for _, ip := range a.addrs {
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
		if err == nil {
			return conn, nil
		}
		last = err
	}
	return nil, last
}
