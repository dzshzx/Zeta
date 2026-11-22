package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// ghProxy 是 GitHub 连不上时的中转（地址前面加上它），和 assets/zeta_setup_debian.sh 下载 GitHub 文件的做法一样。
// 它是第三方的：从它取的清单和安装包照样核对大小和 sha256。
const ghProxy = "https://gh-proxy.com/"

// GitHubMirrors 给出一个地址该依次从哪几处取：GitHub 上的先直连，不通再走 gh-proxy；别的地址只有它自己。
func GitHubMirrors(u string) []string {
	if strings.HasPrefix(u, "https://github.com/") {
		return []string{u, ghProxy + u}
	}
	return []string{u}
}

// Client 取更新清单、下载 MSI：每个地址按 Mirrors 给的顺序试，前一个连不上、超时、HTTP 出错或内容不对就换下一个；
// 都不行时返回的错误里每个地址各一句原因，界面照原样显示。零值可用。
type Client struct {
	URL       string                // 清单地址，空时是 LatestURL
	UserAgent string                // 空时是 "Zeta-Desktop"
	HTTP      *http.Client          // 空时是 http.DefaultClient（跟随跳转：GitHub 的下载地址会跳到 objects.githubusercontent.com）
	Mirrors   func(string) []string // 空时是 GitHubMirrors
	Stall     time.Duration         // 这么久既没连上也没收到数据就算超时，空时 20 秒
}

// errStalled 是 Stall 到点时取消请求的原因。
var errStalled = errors.New("stalled")

// Manifest 取清单并解析。
func (c *Client) Manifest(ctx context.Context) (*Manifest, error) {
	src := c.URL
	if src == "" {
		src = LatestURL
	}
	var m *Manifest
	err := c.each(ctx, src, func(ctx context.Context, u string) error {
		return c.get(ctx, u, func(body io.Reader) error {
			data, err := io.ReadAll(io.LimitReader(body, maxManifestBytes+1))
			if err != nil {
				return err
			}
			if len(data) > maxManifestBytes {
				return fmt.Errorf("%s 给的更新清单比 %d KB 还大，不像是清单", host(u), maxManifestBytes>>10)
			}
			if m, err = ParseManifest(data); err != nil {
				return fmt.Errorf("%s 给的%w", host(u), err)
			}
			return nil
		})
	})
	return m, err
}

// Download 把 p 下载到 dest：先写到旁边的 .part，大小和 sha256 都对上清单才改名成 dest；对不上就删掉，换下一个地址重下。
// progress 报已下载的字节数，在下载的 goroutine 上调。
func (c *Client) Download(ctx context.Context, p Package, dest string, progress func(done int64)) error {
	part := dest + ".part"
	defer os.Remove(part)
	return c.each(ctx, p.URL, func(ctx context.Context, u string) error {
		if err := c.get(ctx, u, func(body io.Reader) error { return save(body, part, p, u, progress) }); err != nil {
			return err
		}
		if err := os.Rename(part, dest); err != nil {
			return fmt.Errorf("存不下下载的文件：%w", err)
		}
		return nil
	})
}

// save 把 body 写进 part，边写边算 sha256、报进度；比清单写的大、小或 sha256 不对时返回错误。
func save(body io.Reader, part string, p Package, u string, progress func(int64)) error {
	f, err := os.Create(part)
	if err != nil {
		return fmt.Errorf("存不下下载的文件：%w", err)
	}
	defer f.Close()
	sum := sha256.New()
	w := &counter{w: io.MultiWriter(f, sum), progress: progress}
	if _, err := io.Copy(w, io.LimitReader(body, p.Size+1)); err != nil {
		if pe := (*fs.PathError)(nil); errors.As(err, &pe) {
			return fmt.Errorf("存不下下载的文件：%w", err)
		}
		return err
	}
	switch {
	case w.n > p.Size:
		return fmt.Errorf("从 %s 下的文件比清单写的 %d 字节大，已删掉", host(u), p.Size)
	case w.n < p.Size:
		return fmt.Errorf("从 %s 下的文件只有 %d 字节，清单写的是 %d，已删掉", host(u), w.n, p.Size)
	case hex.EncodeToString(sum.Sum(nil)) != p.SHA256:
		return fmt.Errorf("从 %s 下的文件 sha256 和清单不一致，已删掉", host(u))
	}
	return f.Close()
}

// counter 数写了多少字节，每写一次报一次进度。
type counter struct {
	w        io.Writer
	n        int64
	progress func(int64)
}

func (c *counter) Write(b []byte) (int, error) {
	n, err := c.w.Write(b)
	c.n += int64(n)
	if c.progress != nil && n > 0 {
		c.progress(c.n)
	}
	return n, err
}

// each 对 src 的每个镜像地址做 try，第一个成功就返回；都失败时把每个地址的原因用「；」连起来。ctx 取消时立即返回 ctx 的错误。
func (c *Client) each(ctx context.Context, src string, try func(context.Context, string) error) error {
	mirrors := c.Mirrors
	if mirrors == nil {
		mirrors = GitHubMirrors
	}
	var reasons []string
	for _, u := range mirrors(src) {
		err := try(ctx, u)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		reasons = append(reasons, reason(u, err))
	}
	return errors.New(strings.Join(reasons, "；"))
}

// get 请求 u，HTTP 200 时把响应体交给 read。Stall 内既没连上也没收到数据就取消这次请求。
func (c *Client) get(ctx context.Context, u string, read func(io.Reader) error) error {
	stall := c.Stall
	if stall <= 0 {
		stall = 20 * time.Second
	}
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	timer := time.AfterFunc(stall, func() { cancel(errStalled) })
	defer timer.Stop()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	ua := c.UserAgent
	if ua == "" {
		ua = "Zeta-Desktop"
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Cache-Control", "no-cache")
	client := c.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return stalled(ctx, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s 回了 HTTP %d", host(u), resp.StatusCode)
	}
	return stalled(ctx, read(&resetReader{r: resp.Body, timer: timer, after: stall}))
}

// stalled 把因 Stall 到点而取消的请求报成超时。
func stalled(ctx context.Context, err error) error {
	if err != nil && errors.Is(context.Cause(ctx), errStalled) {
		return errStalled
	}
	return err
}

// resetReader 每收到数据就把超时的计时器拨回去：慢但一直在动的下载不算超时。
type resetReader struct {
	r     io.Reader
	timer *time.Timer
	after time.Duration
}

func (r *resetReader) Read(b []byte) (int, error) {
	n, err := r.r.Read(b)
	if n > 0 {
		r.timer.Reset(r.after)
	}
	return n, err
}

// reason 是一个地址失败的原因，给人看的一句话。内容不对的（已经是这样的一句话）原样用，网络错误换成说法。
func reason(u string, err error) string {
	var dns *net.DNSError
	var ne net.Error
	switch {
	case errors.Is(err, errStalled), errors.As(err, &ne) && ne.Timeout():
		return "连 " + host(u) + " 超时"
	case errors.As(err, &dns):
		return "找不到 " + host(u) + "（没有网络？）"
	case isNetwork(err):
		return "连不上 " + host(u)
	}
	return err.Error()
}

// isNetwork 说 err 是不是请求本身出的错（连不上、断开），而不是内容不对。
func isNetwork(err error) bool {
	var ue *url.Error
	var oe *net.OpError
	return errors.As(err, &ue) || errors.As(err, &oe) || errors.Is(err, io.ErrUnexpectedEOF)
}

// host 是地址里的主机（带端口），原因里说是哪一处。
func host(u string) string {
	if p, err := url.Parse(u); err == nil && p.Host != "" {
		return p.Host
	}
	return u
}

// fileSHA256 是文件的 SHA-256（小写十六进制）。
func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	sum := sha256.New()
	if _, err := io.Copy(sum, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(sum.Sum(nil)), nil
}
