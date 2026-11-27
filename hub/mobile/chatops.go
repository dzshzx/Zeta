package mobile

import (
	"context"
	"encoding/base64"
	"errors"
	"slices"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/hub/locales"
)

// 聊天页的对话操作（同桌面版 msgactions.go、handoff.go、prompts.go 的 MCP 表单）和取图（hub/images.go）。
// 剪会话、重发都由核心做，结果经推送回来；这里只把请求交过去。

type (
	roundParams struct {
		Target string   `json:"target"`
		Text   string   `json:"text"`
		Files  []string `json:"files"`
		// Code：重新生成时先还原代码；Drop：还原代码后再删这一轮。
		Code bool `json:"code"`
		Drop bool `json:"drop"`
	}
	forkParams struct {
		Last string `json:"last"`
	}
	elicitParams struct {
		RequestID string              `json:"request_id"`
		Action    string              `json:"action"`
		Values    map[string][]string `json:"values"`
	}
	// imageParams 是 items.Image 的取法：kind 是 inline（key）、session（file、ref）、linked（url）。
	imageParams struct {
		Kind string `json:"kind"`
		Key  string `json:"key"`
		File string `json:"file"`
		Ref  string `json:"ref"`
		URL  string `json:"url"`
	}
)

// Elicited 是 elicit 的结果：表单有几项不对时 Problems 是键 → 原因，表单留着。
type Elicited struct {
	Problems map[string]string `json:"problems"`
}

// Picture 是 image 的结果：图片的原始字节（base64）。
type Picture struct {
	Data string `json:"data"`
}

// chatOpTimeout 是问核心一次（code_changes、elicit）最多等多久。
const chatOpTimeout = 60 * time.Second

func init() {
	inChat("rewrite", func(p roundParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error {
			return conv.Rewrite(ctx, p.Target, coreapi.Outgoing{Text: p.Text, Files: p.Files})
		}, nil
	})
	inChat("regenerate", func(p roundParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error {
			return conv.Regenerate(ctx, p.Target, p.Code)
		}, nil
	})
	inChat("drop", func(p roundParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error {
			if p.Code {
				return conv.RestoreCode(ctx, p.Target, true)
			}
			return conv.Drop(ctx, p.Target)
		}, nil
	})
	inChat("restorecode", func(p roundParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error {
			return conv.RestoreCode(ctx, p.Target, p.Drop)
		}, nil
	})
	blocking("codechanges", func(c *Core, p roundParams) (any, error) {
		conv, err := c.chatConv()
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), chatOpTimeout)
		defer cancel()
		changes, err := conv.CodeChanges(ctx, p.Target)
		if err != nil {
			return nil, err
		}
		if changes == nil {
			return nil, nil
		}
		return changes, nil
	})
	onLoop("fork", func(c *Core, p forkParams) (any, error) {
		c.problem = ""
		c.hub.Fork(p.Last, func(err error) { c.problem = locales.ErrorText(err) })
		return ok, nil
	})
	onLoop("handover", func(c *Core, _ none) (any, error) {
		c.hub.HandOver()
		return ok, nil
	})
	blocking("elicit", func(c *Core, p elicitParams) (any, error) {
		conv, err := c.chatConv()
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), chatOpTimeout)
		defer cancel()
		err = conv.Elicit(ctx, p.RequestID, prompt.ElicitAction(p.Action), p.Values)
		var form chat.FormError
		if errors.As(err, &form) {
			return Elicited{Problems: form}, nil
		}
		return Elicited{}, err
	})
	blocking("image", func(c *Core, p imageParams) (any, error) {
		var data []byte
		var err error
		switch items.ImageKind(p.Kind) {
		case items.Inline:
			conv, cerr := c.chatConv()
			if cerr != nil {
				return nil, cerr
			}
			var load func(string) ([]byte, error)
			c.Do(func() { load = c.hub.InlineImageLoader(c.hub.Chat().Backend, conv) })
			data, err = load(p.Key)
		case items.Session:
			load, cerr := c.chatLoader(func(backend string) any { return c.hub.ImageLoader(backend) })
			if cerr != nil {
				return nil, cerr
			}
			data, err = load.(func(string, string) ([]byte, error))(p.File, p.Ref)
		default:
			load, cerr := c.chatLoader(func(backend string) any { return c.hub.LinkedImageLoader(backend) })
			if cerr != nil {
				return nil, cerr
			}
			data, err = load.(func(string) ([]byte, error))(p.URL)
		}
		if err != nil {
			return nil, err
		}
		return Picture{Data: base64.StdEncoding.EncodeToString(data)}, nil
	})
}

// chatConv 是聊天页里打开成的对话（在调用方线程上取）。
func (c *Core) chatConv() (*chat.Conversation, error) {
	var conv *chat.Conversation
	c.Do(func() {
		if ch := c.hub.Chat(); ch != nil {
			conv = ch.Conv
		}
	})
	if conv == nil {
		return nil, locales.Err(locales.MobileChatNotOpen)
	}
	return conv, nil
}

// chatLoader 在核心 goroutine 上按聊天页所在的设备建取图的办法。
func (c *Core) chatLoader(build func(backend string) any) (any, error) {
	var load any
	c.Do(func() {
		if ch := c.hub.Chat(); ch != nil {
			load = build(ch.Backend)
		}
	})
	if load == nil {
		return nil, locales.Err(locales.MobileChatNotOpen)
	}
	return load, nil
}

// promptsSaid 把请求里核心按种类码写的字（「以后不再问」的说明、子代理的兜底叫法、通知的一句）换成界面语言说的。改的是副本。
func promptsSaid(list []prompt.Prompt, locale string) []prompt.Prompt {
	out := slices.Clone(list)
	for i := range out {
		p := &out[i]
		p.SuggestionLines = p.SuggestionLinesIn(locale)
		p.AgentLabel = p.AgentLabelIn(locale)
		p.Notice = p.NoticeIn(locale)
	}
	return out
}
