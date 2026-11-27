package hub

import (
	"cmp"
	"context"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/settings"
)

// 侧栏的排序（docs/desktop.md「界面」）：最新回答（Replied）、发起时间（Created）、手动排序（Rank，核心的 sessions.order 记的位置，
// 没拖过的同 Created，见 docs/core.md「会话」）。选哪种存在这台电脑的设置里；三种都是新的、大的在上，相同时按 Key，先后不跳。
// 手动排序时用鼠标拖动一行改位置（Move）：只在同一组里（置顶组内、其余组内）挪，新位置取落点上下两行的中间。

// rankGap 是拖到一组的最上面、最下面时比挨着的那行大、小多少。
const rankGap = 1000

// Sort 是侧栏会话列表的排序。
func (h *Hub) Sort() settings.SessionSort { return h.settings.SessionSort() }

// SetSort 换侧栏会话列表的排序，存进这台电脑的设置。
func (h *Hub) SetSort(mode settings.SessionSort) {
	if mode == h.Sort() {
		return
	}
	h.settings.SetSessionSort(mode)
	h.changed()
}

// compareIn 是排序 mode 下 a、b 的先后：所选的那个值大的在前，相同时按主机、会话号（手机版 SessionSort.ordered 同样）。
func compareIn(mode settings.SessionSort, a, b hostlink.Session) int {
	var by int
	if mode == settings.SortManual {
		by = cmp.Compare(b.Rank, a.Rank)
	} else {
		by = cmp.Compare(sortTime(mode, b), sortTime(mode, a))
	}
	return cmp.Or(by, strings.Compare(a.Backend, b.Backend), strings.Compare(a.ID, b.ID))
}

// sortTime 是排序 mode 下分「今天 / 更早」、排先后用的时间（毫秒）：发起时间或最新回答。
func sortTime(mode settings.SessionSort, s hostlink.Session) int64 {
	if mode == settings.SortCreated {
		return s.Created
	}
	return s.Replied
}

// rankChange 是挪动时要改位置的一行：会话和它的新位置。
type rankChange struct {
	s    hostlink.Session
	rank float64
}

// CanMove：手动排序时，把会话 s 拖到 target 那一行的上半（below 为假）或下半落下能挪动它：两行在同一组里，挪完先后变了。
// 不在手动排序、不在同一组（置顶和其余之间）、落回原处时为假。用的是上一次 Groups 算的分组。
func (h *Hub) CanMove(s, target hostlink.Session, below bool) bool {
	_, _, ok := h.placement(s, target, below)
	return ok
}

// Move 同 CanMove 那样把会话 s 挪到 target 的上面或下面，返回挪没挪：新位置取落点上下两行 Rank 的中间，拖到组的最上面、
// 最下面时比挨着的那行大、小 rankGap；中间没有余地了（浮点数分不开）时，落点往下挨着的几行一起均匀重排。
// 界面先改，各行的新位置经各自那台的 sessions.order 记下（核心改完经 sessions.watch 推来）；没改成的改回去，原因交给 failed（主线程上）。
func (h *Hub) Move(s, target hostlink.Session, below bool, failed func(error)) bool {
	list, at, ok := h.placement(s, target, below)
	if !ok {
		return false
	}
	changes := placeRanks(list, at)
	if len(changes) == 0 {
		return false
	}
	ranks := map[string]float64{}
	old := map[string]float64{}
	var moved []hostlink.Session
	for _, c := range changes {
		key := Key(c.s)
		ranks[key], old[key] = c.rank, c.s.Rank
		moved = append(moved, c.s)
		h.patch(c.s, func(x *hostlink.Session) { x.Rank = c.rank })
	}
	h.each(moved, func(ctx context.Context, core *coreapi.Client, s hostlink.Session) error {
		return core.OrderSession(ctx, s.ID, ranks[Key(s)])
	}, func(s hostlink.Session, err error) {
		if err != nil {
			h.patch(s, func(x *hostlink.Session) {
				if x.Rank == ranks[Key(s)] { // 这期间又改过的不动
					x.Rank = old[Key(s)]
				}
			})
		}
	}, failed)
	return true
}

// placement 是把 s 拖到 target 上半或下半落下后 s 那一组的先后（list，已经把 s 放到新位置）和 s 在里面的下标；挪不了时 ok 为假。
func (h *Hub) placement(s, target hostlink.Session, below bool) (list []hostlink.Session, at int, ok bool) {
	v := &h.view
	if !v.fresh {
		v = h.sorted(v.now, v.current)
	}
	if h.Sort() != settings.SortManual || sameSession(s, target) {
		return nil, 0, false
	}
	for _, group := range [][]hostlink.Session{v.groups.Pinned, v.groups.Chats} {
		from := slices.IndexFunc(group, func(x hostlink.Session) bool { return sameSession(x, s) })
		to := slices.IndexFunc(group, func(x hostlink.Session) bool { return sameSession(x, target) })
		if from < 0 || to < 0 {
			continue
		}
		moving := group[from]
		list = slices.Delete(slices.Clone(group), from, from+1)
		if to > from {
			to--
		}
		if below {
			to++
		}
		if to == from {
			return nil, 0, false // 落回原处
		}
		return slices.Insert(list, to, moving), to, true
	}
	return nil, 0, false // 不在同一组
}

// placeRanks 给 list（挪完的先后）里第 at 行定新位置，返回要改的行：取上下两行的中间，在最上面、最下面时比挨着的大、小 rankGap。
// 算出来分不开（等于邻居）时，从 at 往下挨着的几行在上面那行和再往下第一个分得开的那行之间均匀重排；到底了就在上面那行之下
// 每隔 rankGap 排。一组只有它自己时什么也不改。
func placeRanks(list []hostlink.Session, at int) []rankChange {
	if len(list) < 2 {
		return nil
	}
	var r float64
	switch {
	case at == 0:
		r = list[1].Rank + rankGap
	case at == len(list)-1:
		r = list[at-1].Rank - rankGap
	default:
		r = (list[at-1].Rank + list[at+1].Rank) / 2
	}
	if between(list, at, r) {
		return []rankChange{{list[at], r}}
	}
	return spread(list, at)
}

// between：r 放在 list 的第 at 行，比上面那行小、比下面那行大（没有的那边不算）。
func between(list []hostlink.Session, at int, r float64) bool {
	return (at == 0 || r < list[at-1].Rank) && (at == len(list)-1 || r > list[at+1].Rank)
}

// spread 是 placeRanks 没有余地时的重排：上界 hi 是 at 上面那行（在最上面时是下面那行加 rankGap），从 at 往下一行行加进来，
// 直到在 hi 和下一行（到底了是 hi 往下每行 rankGap）之间均匀排得开。排不开（数太大，加减 rankGap 也分不开）时什么也不改。
func spread(list []hostlink.Session, at int) []rankChange {
	hi := list[at+1].Rank + rankGap
	if at > 0 {
		hi = list[at-1].Rank
	}
	for end := at + 1; end <= len(list); end++ {
		n := end - at
		lo := hi - rankGap*float64(n+1)
		if end < len(list) {
			lo = list[end].Rank
		}
		step := (hi - lo) / float64(n+1)
		changes := make([]rankChange, n)
		prev, ok := hi, true
		for i := range n {
			r := hi - step*float64(i+1)
			if !(r < prev && r > lo) {
				ok = false
				break
			}
			changes[i], prev = rankChange{list[at+i], r}, r
		}
		if ok {
			return slices.DeleteFunc(changes, func(c rankChange) bool { return c.rank == c.s.Rank })
		}
	}
	return nil
}
