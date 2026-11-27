package mobile

import "github.com/dzshzx/Zeta/hub/mathtex"

// 公式：排版在 hub/mathtex（同桌面版、手机版的 RaTeX），Swift 照绘制清单用 KaTeX 字体画（ios/Zeta/Views/Chat/MathView.swift）。

type mathParams struct {
	Tex     string `json:"tex"`
	Display bool   `json:"display"`
}

// MathFont 是方法 math.fonts 的一项：KaTeX 字体的 FontId 和 TTF（JSON 里是 base64）。
type MathFont struct {
	ID   string `json:"id"`
	Data []byte `json:"data"`
}

func init() {
	// 哪些算公式照手机版（mathtex.Source）：配上的单个 $ 换成 \( \)，不算块公式的 \[ 换成 &#91;；界面再自己认 \( \) 和行首的块公式
	reading("math.source", func(_ *Core, p struct {
		Text string `json:"text"`
	}) (any, error) {
		return map[string]string{"text": mathtex.Source(p.Text)}, nil
	})
	// 第一次要起 wasm（约 0.1 秒），之后一条约 1 毫秒，排好的有缓存；排不出时出错，界面照原文显示
	blocking("math", func(_ *Core, p mathParams) (any, error) { return mathtex.Layout(p.Tex, p.Display) })
	blocking("math.fonts", func(*Core, struct{}) (any, error) {
		out := make([]MathFont, 0, len(mathtex.FontIDs))
		for _, id := range mathtex.FontIDs {
			data, err := mathtex.Font(id)
			if err != nil {
				return nil, err
			}
			out = append(out, MathFont{ID: id, Data: data})
		}
		return out, nil
	})
}
