package remote

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/sshtest"
	"golang.org/x/crypto/ssh"
)

// sshConfigFixture 是照这台电脑 ~/.ssh 直连的一套：临时家目录里 ssh-keygen 生成的两把私钥（k 被服务器认，stranger 不认），
// 一份用到 Include、%d、~ 的配置，交给真的 ssh -G 读。要本机有 OpenSSH 客户端（ssh、ssh-keygen），没有就失败。
type sshConfigFixture struct {
	home   string
	auth   *OpenSSH
	server *sshtest.Server
}

func newSSHConfigFixture(t *testing.T) *sshConfigFixture {
	t.Helper()
	for _, tool := range []string{"ssh", "ssh-keygen"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatalf("要本机的 OpenSSH 客户端：找不到 %s", tool)
		}
	}
	home := t.TempDir()
	dir := filepath.Join(home, ".ssh")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	keygen(t, filepath.Join(dir, "k"))
	keygen(t, filepath.Join(dir, "stranger"))
	// 没有 .pub 的不算能选的私钥
	if err := os.WriteFile(filepath.Join(dir, "known_hosts"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	server := sshtest.Start(t, t.TempDir(), publicKey(t, filepath.Join(dir, "k.pub")))
	port := strconv.Itoa(server.Port)
	write(t, filepath.Join(dir, "extra"), "Host box\n  IdentityFile %d/.ssh/k\n  IdentitiesOnly yes\n")
	config := filepath.Join(dir, "config")
	write(t, config, strings.Join([]string{
		"Include " + filepath.Join(dir, "extra"),
		"Host box", "  HostName 127.0.0.1", "  Port " + port, "  User zeta",
		"Host 127.0.0.1", "  IdentityFile ~/.ssh/k",
		"Host other", "  HostName 127.0.0.1", "  Port " + port, "  User zeta", "  IdentityFile ~/.ssh/stranger",
		"Host nokey", "  HostName 127.0.0.1", "  IdentityFile ~/.ssh/missing",
		"Host jump", "  HostName 10.0.0.1", "  ProxyJump box", "  IdentityFile ~/.ssh/k",
		"Host *", "  ServerAliveInterval 30",
	}, "\n")+"\n")
	return &sshConfigFixture{home: home, auth: &OpenSSH{Home: home, SSH: "ssh", Config: config}, server: server}
}

func keygen(t *testing.T, path string) {
	t.Helper()
	out, err := exec.Command("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "test", "-f", path).CombinedOutput()
	if err != nil {
		t.Fatalf("ssh-keygen: %v %s", err, out)
	}
}

func publicKey(t *testing.T, path string) ssh.PublicKey {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	key, _, _, _, err := ssh.ParseAuthorizedKey(data)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func write(t *testing.T, path, text string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestAliasAndAddressLogInWithTheConfiguredKey(t *testing.T) {
	f := newSSHConfigFixture(t)
	route, err := f.auth.Resolve("box", "")
	if err != nil {
		t.Fatal(err)
	}
	if got := route.Target.String(); got != "zeta@127.0.0.1:"+strconv.Itoa(f.server.Port) || route.Keys != "k" {
		t.Errorf("route %s, keys %q", got, route.Keys)
	}
	links := NewLinks(f.auth, time.Minute)
	seen, r, err := links.Probe("box", "", "", "echo ok", 10*time.Second)
	if err != nil || r.Stdout != "ok\n" || seen == "" {
		t.Fatalf("alias: %v %+v", err, r)
	}
	// 写明用户和端口的地址：用户、端口照写的，私钥照配置里那个主机的
	_, r, err = links.Probe("zeta@127.0.0.1:"+strconv.Itoa(f.server.Port), "", seen, "echo ok", 10*time.Second)
	if err != nil || r.Stdout != "ok\n" {
		t.Fatalf("address: %v %+v", err, r)
	}
}

func TestKeyTheHostDoesNotTrustIsExplained(t *testing.T) {
	f := newSSHConfigFixture(t)
	_, _, err := NewLinks(f.auth, time.Minute).Probe("other", "", "", "echo ok", 10*time.Second)
	if err == nil || !strings.Contains(Describe(err), "服务器拒绝了 SSH 密钥 stranger") {
		t.Errorf("err %v", err)
	}
}

func TestChosenKeyReplacesTheConfiguredOne(t *testing.T) {
	f := newSSHConfigFixture(t)
	if got := f.auth.Keys(); !reflect.DeepEqual(got, []string{"~/.ssh/k", "~/.ssh/stranger"}) {
		t.Errorf("keys %v", got)
	}
	if KeyName("~/.ssh/k") != "k" || KeyName(`C:\Users\z\.ssh\k`) != "k" {
		t.Error("key names")
	}
	links := NewLinks(f.auth, time.Minute)
	// 配置里给 other 的是对方不认的 stranger，选了 k 就只用 k
	if _, r, err := links.Probe("other", "~/.ssh/k", "", "echo ok", 10*time.Second); err != nil || r.Stdout != "ok\n" {
		t.Errorf("chosen k: %v %+v", err, r)
	}
	// 选了对方不认的那把：配置里的好钥匙也不再试
	if _, _, err := links.Probe("box", "~/.ssh/stranger", "", "echo ok", 10*time.Second); err == nil || !strings.Contains(err.Error(), "SSH 密钥 stranger") {
		t.Errorf("chosen stranger: %v", err)
	}
	if _, err := f.auth.Resolve("box", "~/.ssh/gone"); err == nil || !strings.Contains(err.Error(), "找不到 SSH 密钥 gone") {
		t.Errorf("gone: %v", err)
	}
}

func TestMissingKeysAndJumpsAreRefusedBeforeConnecting(t *testing.T) {
	f := newSSHConfigFixture(t)
	for target, want := range map[string]string{
		"nokey":    "没有可用于 nokey 的 SSH 密钥",
		"jump":     "跳板",
		"bad name": "SSH 配置中的主机名",
	} {
		_, err := f.auth.Resolve(target, "")
		var problem Problem
		if !errors.As(err, &problem) || !strings.Contains(string(problem), want) {
			t.Errorf("%s: %v", target, err)
		}
	}
	if f.server.Dialed() != 0 {
		t.Errorf("dialed %d times", f.server.Dialed())
	}
}

func TestPassphraseKeysAreNotSupported(t *testing.T) {
	f := newSSHConfigFixture(t)
	path := filepath.Join(f.home, ".ssh", "locked")
	out, err := exec.Command("ssh-keygen", "-q", "-t", "ed25519", "-N", "not-empty", "-C", "test", "-f", path).CombinedOutput()
	if err != nil {
		t.Fatalf("ssh-keygen: %v %s", err, out)
	}
	_, err = f.auth.Resolve("box", "~/.ssh/locked")
	if err == nil || !strings.Contains(err.Error(), "无法读取 SSH 密钥 locked（暂不支持有密码保护的密钥）") {
		t.Errorf("err %v", err)
	}
}

func TestConfigHostsSkipWildcards(t *testing.T) {
	f := newSSHConfigFixture(t)
	hosts, err := f.auth.ConfigHosts()
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"box", "127.0.0.1", "other", "nokey", "jump"}; !reflect.DeepEqual(hosts, want) {
		t.Errorf("hosts %v", hosts)
	}
	// 没有配置文件时是空的，不算出错
	none := &OpenSSH{Home: t.TempDir(), SSH: "ssh"}
	if hosts, err := none.ConfigHosts(); err != nil || len(hosts) != 0 {
		t.Errorf("no config: %v %v", hosts, err)
	}
}

func TestParseOptionsKeepsEveryIdentityFileInOrder(t *testing.T) {
	options := ParseOptions("user Alex Ting\nhostname h\nidentityfile ~/.ssh/a\nIdentityFile ~/.ssh/b\n\n")
	if !reflect.DeepEqual(options["user"], []string{"Alex Ting"}) || !reflect.DeepEqual(options["identityfile"], []string{"~/.ssh/a", "~/.ssh/b"}) {
		t.Errorf("options %v", options)
	}
}

func TestMissingSSHClientIsExplained(t *testing.T) {
	a := &OpenSSH{Home: t.TempDir(), SSH: filepath.Join(t.TempDir(), "no-ssh")}
	if _, err := a.Resolve("box", ""); err == nil || !strings.Contains(err.Error(), "未找到 OpenSSH 客户端") {
		t.Errorf("err %v", err)
	}
}
