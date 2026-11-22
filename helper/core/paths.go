package core

import (
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// DirEnv 指定核心的目录（套接字、锁、日志放在里面），盖过默认的 ~/.cache/<ZETA_NAME>/core。测试用它把套接字放到短路径下。
const DirEnv = "ZETA_CORE_DIR"

// programPrefix 是装好的程序文件名在版本前面的部分（装到 ~/.cache/<ZETA_NAME>/bin/zeta-helper-<版本>）。
const programPrefix = "zeta-helper-"

// maxSocketPath 是 unix 套接字路径的上限：sun_path 108 字节，含结尾的 0。
const maxSocketPath = 107

var versionPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// ErrRunning 表示这个版本已经有一个核心在听，新起的不用再跑。
var ErrRunning = errors.New("another core is already listening")

// lockWait 是锁被占着、套接字却连不上（那个核心正在起或正在退出）时最多等多久。
var lockWait = 5 * time.Second

// Paths 是一个版本的核心用的几个文件，都在同一个只有本人能进的目录里。
type Paths struct {
	Socket string // <版本>.sock：核心在这里听
	Lock   string // <版本>.lock：持着它的就是正在跑的那个核心，里面写着它的进程号
	Log    string // <版本>.log：serve 拉起核心时，核心的标准错误追加到这里
}

// Locate 算出 exe 这个程序对应的核心文件。版本取程序文件名去掉 zeta-helper- 前缀（没有这个前缀时用整个文件名）；
// 目录是 $ZETA_CORE_DIR，没设时是 home/.cache/name/core。套接字路径超过 unix 套接字的长度上限时报错。
func Locate(home, name, exe string) (Paths, error) {
	version := strings.TrimPrefix(filepath.Base(exe), programPrefix)
	if !versionPattern.MatchString(version) {
		return Paths{}, fmt.Errorf("cannot take a core version from program name %q", filepath.Base(exe))
	}
	dir := os.Getenv(DirEnv)
	if dir == "" {
		dir = strings.TrimRight(home, "/") + "/.cache/" + name + "/core"
	}
	base := filepath.Join(dir, version)
	p := Paths{Socket: base + ".sock", Lock: base + ".lock", Log: base + ".log"}
	if n := len(p.Socket); n > maxSocketPath {
		return Paths{}, fmt.Errorf("core socket path %s is %d bytes, over the %d-byte limit of unix sockets (%s can point to a shorter directory)",
			p.Socket, n, maxSocketPath, DirEnv)
	}
	return p, nil
}

func (p Paths) dir() string { return filepath.Dir(p.Socket) }

// makeDir 建出目录，权限收到只有本人能进。
func (p Paths) makeDir() error {
	if err := os.MkdirAll(p.dir(), 0o700); err != nil {
		return err
	}
	return os.Chmod(p.dir(), 0o700)
}

// acquire 拿到锁就返回锁文件（关掉它就放开锁），并把自己的进程号写进去。锁被占着时：套接字连得上说明那个核心
// 在听，返回 ErrRunning；连不上说明它正在起或正在退出，等到它听上或放开锁，最多等 lockWait。
func (p Paths) acquire() (*os.File, error) {
	f, err := os.OpenFile(p.Lock, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(lockWait)
	for {
		err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
		switch {
		case err == nil:
			_ = f.Truncate(0)
			_, _ = f.WriteAt([]byte(strconv.Itoa(os.Getpid())+"\n"), 0)
			return f, nil
		case !errors.Is(err, syscall.EWOULDBLOCK):
			_ = f.Close()
			return nil, fmt.Errorf("lock %s: %w", p.Lock, err)
		case listening(p.Socket):
			_ = f.Close()
			return nil, ErrRunning
		case time.Now().After(deadline):
			_ = f.Close()
			return nil, fmt.Errorf("lock %s is held but nobody listens on %s", p.Lock, p.Socket)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// listening：有人在这个套接字上听（连得上）。
func listening(socket string) bool {
	c, err := net.Dial("unix", socket)
	if err != nil {
		return false
	}
	_ = c.Close()
	return true
}
