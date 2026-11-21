package main

import (
	"bufio"
	"bytes"
	"fmt"
	"net"
	"os"
	"strings"
	"testing"
	"time"
)

// echoServer 在抽象套接字 name 上等一条连接，把每行加上前缀送回去，对方关了写的一半后再送一行 bye 才关。
func echoServer(t *testing.T, name string) net.Listener {
	t.Helper()
	l, err := net.Listen("unix", "@"+name)
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		conn, err := l.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		sc := bufio.NewScanner(conn)
		if sc.Scan() {
			fmt.Fprintf(conn, "header:%s\n", sc.Text())
		}
		for sc.Scan() {
			fmt.Fprintf(conn, "re:%s\n", sc.Text())
		}
		fmt.Fprintln(conn, "bye")
	}()
	return l
}

func testBridge(woke *[]string, onWake func()) phoneBridge {
	b := realPhoneBridge
	b.wake = func(pkg string) error {
		*woke = append(*woke, pkg)
		if onWake != nil {
			onWake()
		}
		return nil
	}
	b.wait = 2 * time.Second
	b.step = 20 * time.Millisecond
	return b
}

func TestPhoneMCPRelays(t *testing.T) {
	pkg := fmt.Sprintf("com.example.t%d", os.Getpid())
	l := echoServer(t, pkg+".phone")
	defer l.Close()
	var woke []string
	var out, errOut bytes.Buffer
	code := runPhoneMCP(testBridge(&woke, nil), []string{pkg, "desk-1"}, strings.NewReader("{\"id\":1}\n{\"id\":2}\n"), &out, &errOut)
	if code != 0 {
		t.Fatalf("退出码 %d：%s", code, errOut.String())
	}
	// 输入完了只关写的一半：服务端还能把最后一行送回来
	if got, want := out.String(), "header:ZETA-BRIDGE desk-1\nre:{\"id\":1}\nre:{\"id\":2}\nbye\n"; got != want {
		t.Fatalf("输出 %q，要 %q", got, want)
	}
	if len(woke) != 1 || woke[0] != pkg {
		t.Fatalf("叫起 %v", woke)
	}
}

func TestPhoneMCPWaitsForWake(t *testing.T) {
	pkg := fmt.Sprintf("com.example.w%d", os.Getpid())
	opened := make(chan net.Listener, 1)
	defer func() { (<-opened).Close() }()
	var woke []string
	// 叫起之后过一会儿入口才开：要等它，不是一次连不上就放弃
	b := testBridge(&woke, func() {
		go func() {
			time.Sleep(150 * time.Millisecond)
			opened <- echoServer(t, pkg+".phone")
		}()
	})
	var out, errOut bytes.Buffer
	if code := runPhoneMCP(b, []string{pkg}, strings.NewReader("x\n"), &out, &errOut); code != 0 {
		t.Fatalf("退出码 %d：%s", code, errOut.String())
	}
	if out.String() != "header:ZETA-BRIDGE default\nre:x\nbye\n" {
		t.Fatalf("输出 %q", out.String())
	}
}

func TestPhoneMCPGivesUp(t *testing.T) {
	var woke []string
	b := testBridge(&woke, nil)
	b.wait = 100 * time.Millisecond
	var out, errOut bytes.Buffer
	pkg := fmt.Sprintf("com.example.none%d", os.Getpid())
	if code := runPhoneMCP(b, []string{pkg}, strings.NewReader(""), &out, &errOut); code != 1 {
		t.Fatalf("退出码 %d", code)
	}
	if !strings.Contains(errOut.String(), "连不上") || out.Len() != 0 {
		t.Fatalf("标准错误 %q，标准输出 %q", errOut.String(), out.String())
	}
}

func TestPhoneMCPRejectsBadPackage(t *testing.T) {
	for _, args := range [][]string{nil, {"zeta"}, {"com.x;rm"}, {"a.b", "c d"}, {"a.b", "c", "d"}} {
		var woke []string
		var out, errOut bytes.Buffer
		if code := runPhoneMCP(testBridge(&woke, nil), args, strings.NewReader(""), &out, &errOut); code != 1 || len(woke) != 0 {
			t.Fatalf("%v：退出码 %d，叫起 %v", args, code, woke)
		}
	}
}
