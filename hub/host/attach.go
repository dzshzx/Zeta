package host

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
)

// Upload 是要传到设备上随消息附带的一样东西：本机的文件或文件夹 Local（Folder 时是文件夹），或者内存里的内容 Data
// （名字 Name，比如放不下的截图）。超过 Limit 字节不传。
type Upload struct {
	Local  string
	Folder bool
	Name   string
	Data   []byte
	Limit  int64
}

// Upload 把 u 传到设备 h 上（~/.cache/zeta/files/<随机>/，见 hostlink.Upload），返回那边的路径。progress 报已传的字节数，
// ctx 取消时停下、删掉传了一半的；超过上限是 hostlink.ErrTooLarge。阻塞。
func (d *Devices) Upload(ctx context.Context, h sshlink.Host, u Upload, progress func(int64)) (string, error) {
	shell := hostlink.NewShell(d.Links, h, LinuxName)
	if u.Folder {
		return shell.UploadFolder(ctx, u.Local, u.Limit, progress)
	}
	var src io.Reader = bytes.NewReader(u.Data)
	name := u.Name
	if u.Local != "" {
		f, err := os.Open(u.Local)
		if err != nil {
			return "", err
		}
		defer f.Close()
		src, name = f, filepath.Base(u.Local)
	}
	return shell.Upload(ctx, name, src, u.Limit, progress)
}

// RemoveUploaded 删掉设备 h 上 Upload 传上去的 path 那一次的目录（见 hostlink.Shell.RemoveUploaded）。阻塞。
func (d *Devices) RemoveUploaded(h sshlink.Host, path string) {
	hostlink.NewShell(d.Links, h, LinuxName).RemoveUploaded(path)
}

// CheckPath 查设备 h 上的路径 path（绝对路径）是文件、文件夹、没有还是读不了。阻塞。
func (d *Devices) CheckPath(h sshlink.Host, path string) (hostlink.PathKind, error) {
	return hostlink.NewShell(d.Links, h, LinuxName).CheckPath(path)
}
