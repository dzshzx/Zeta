package claudecfg

import (
	"context"
	"errors"
	"strconv"
)

// 闲置超时（docs/core.md「主机」的 system.idle）：没有客户端接着、又闲着的 Claude Code 进程，过这么久由那台的托管脚本结束。
// 和自动压缩、自动标题不同，它存在那台主机上：几个客户端连同一台看到的是同一个值，改了对已经在跑的进程马上生效。

// IdleChoices 是闲置超时可选的几档（分钟），0 是不自动结束；和手机上的设置页一样。
var IdleChoices = []int{5, 10, 30, 60, 120, 0}

// IdleLabel 是闲置超时的说法：「不自动结束」「30 分钟」「2 小时」。
func IdleLabel(minutes int) string {
	switch {
	case minutes == 0:
		return "不自动结束"
	case minutes%1440 == 0:
		return strconv.Itoa(minutes/1440) + " 天"
	case minutes%60 == 0:
		return strconv.Itoa(minutes/60) + " 小时"
	}
	return strconv.Itoa(minutes) + " 分钟"
}

// IdleMinutes 读这台主机设的闲置超时（分钟）。
func (cl *Client) IdleMinutes(ctx context.Context) (int, error) {
	return cl.idle(ctx, "system.idle", nil)
}

// SetIdleMinutes 改这台主机的闲置超时，给出改后的值。
func (cl *Client) SetIdleMinutes(ctx context.Context, minutes int) (int, error) {
	return cl.idle(ctx, "system.set_idle", obj{"minutes": minutes})
}

func (cl *Client) idle(ctx context.Context, method string, params obj) (int, error) {
	var r struct {
		Minutes *int `json:"minutes"`
	}
	if err := cl.call(ctx, method, params, &r); err != nil {
		return 0, err
	}
	if r.Minutes == nil {
		return 0, errors.New("主机上的 Zeta 服务没有答闲置时长")
	}
	return *r.Minutes, nil
}
