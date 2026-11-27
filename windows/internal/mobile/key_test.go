package mobile

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"strings"
	"testing"

	"golang.org/x/crypto/ssh"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

func startCore(t *testing.T) *Core {
	t.Helper()
	c, err := Start(Config{Dir: t.TempDir(), Seed: make([]byte, ed25519.SeedSize)})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// callErr 调一个该出错的方法，答出错的原因。
func callErr(t *testing.T, c *Core, method string, params any) string {
	t.Helper()
	raw, _ := json.Marshal(params)
	var failed struct{ Error string }
	if err := json.Unmarshal(c.Call(method, raw), &failed); err != nil || failed.Error == "" {
		t.Fatalf("%s 没出错", method)
	}
	return failed.Error
}

func pemOf(t *testing.T, key any, passphrase string) string {
	t.Helper()
	var block *pem.Block
	var err error
	if passphrase == "" {
		block, err = ssh.MarshalPrivateKey(key, "")
	} else {
		block, err = ssh.MarshalPrivateKeyWithPassphrase(key, "", []byte(passphrase))
	}
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(block))
}

func authorized(t *testing.T, key any) string {
	t.Helper()
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))
}

func TestImportKey(t *testing.T) {
	c := startCore(t)
	own := call[Key](t, c, "key", nil)
	if own.Imported || !strings.HasSuffix(own.Public, " zeta-ios") {
		t.Fatalf("起来时该用自己的密钥：%+v", own)
	}
	_, private, _ := ed25519.GenerateKey(rand.Reader)
	locked := pemOf(t, private, "secret")

	if got := callErr(t, c, "importkey", map[string]string{"key": locked}); got != locales.MobileKeyNeedsPassphrase() {
		t.Fatalf("没填密码：%s", got)
	}
	if got := callErr(t, c, "importkey", map[string]string{"key": locked, "passphrase": "wrong"}); got != locales.MobileKeyUnreadable() {
		t.Fatalf("密码错：%s", got)
	}
	if got := callErr(t, c, "importkey", map[string]string{"key": own.Public}); got != locales.MobileKeyUnreadable() {
		t.Fatalf("给的是公钥：%s", got)
	}
	if call[Key](t, c, "key", nil).Imported {
		t.Fatal("导入没成时不该换钥匙")
	}

	got := call[Key](t, c, "importkey", map[string]string{"key": locked, "passphrase": "secret"})
	if !got.Imported || got.Public != authorized(t, private) {
		t.Fatalf("导入后：%+v", got)
	}
	route, err := c.auth.Resolve("me@example.com", "")
	if err != nil || len(route.Signers) != 1 || string(route.Signers[0].PublicKey().Marshal()) != string(mustSigner(t, private).PublicKey().Marshal()) {
		t.Fatalf("登录该用导入的钥匙：%v", err)
	}
	if c.auth.Rejected(route) != locales.MobileImportedKeyRejected() {
		t.Fatal("被拒时该说导入的私钥")
	}

	// 存下来的 PEM 没有密码，下次起核心时原样交回来就行。
	again := startCore(t)
	if k := call[Key](t, again, "importkey", map[string]string{"key": got.PEM}); k.Public != got.Public {
		t.Fatalf("存下的 PEM 再导入：%+v", k)
	}

	back := call[Key](t, c, "resetkey", nil)
	if back.Imported || back.Public != own.Public || c.auth.Rejected(route) != locales.MobileKeyRejected() {
		t.Fatalf("换回自己的：%+v", back)
	}
}

// RSA、ECDSA 这类常见的私钥也认，包括 PKCS#1、SEC 1 的旧 PEM 格式。
func TestImportOtherKeyTypes(t *testing.T) {
	c := startCore(t)
	rsaKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	ecKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ecDER, err := x509.MarshalECPrivateKey(ecKey)
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]struct {
		text string
		key  any
	}{
		"rsa openssh": {pemOf(t, rsaKey, ""), rsaKey},
		"rsa pkcs1":   {string(pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(rsaKey)})), rsaKey},
		"ecdsa sec1":  {string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: ecDER})), ecKey},
	}
	for name, tc := range cases {
		got := call[Key](t, c, "importkey", map[string]string{"key": tc.text})
		if !got.Imported || got.Public != authorized(t, tc.key) {
			t.Fatalf("%s：%+v", name, got)
		}
	}
}

func mustSigner(t *testing.T, key any) ssh.Signer {
	t.Helper()
	s, err := ssh.NewSignerFromKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}
