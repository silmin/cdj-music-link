// Package ui is the cdj-tui screen: a pure renderer of model.State and the
// bubbletea program around it.
package ui

import (
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/charmbracelet/bubbles/viewport"
	"github.com/charmbracelet/lipgloss"
	"github.com/silmin/cdj-music-link/tui/internal/model"
)

// MinWidth and MinHeight are the smallest terminal the layout supports.
const (
	MinWidth  = 80
	MinHeight = 24
	deckMinW  = 40 // a deck box is at least this wide
	npHeight  = 5  // NOW PLAYING box incl. border
	logMinH   = 3  // event log box incl. border
)

var (
	accent      = lipgloss.AdaptiveColor{Light: "#005FAF", Dark: "#5FAFFF"}
	red         = lipgloss.AdaptiveColor{Light: "#AF0000", Dark: "#FF5F5F"}
	accentStyle = lipgloss.NewStyle().Foreground(accent).Bold(true)
	redStyle    = lipgloss.NewStyle().Foreground(red).Bold(true)
	dimStyle    = lipgloss.NewStyle().Faint(true)
	boldStyle   = lipgloss.NewStyle().Bold(true)
	cursorStyle = lipgloss.NewStyle().Reverse(true)
	boxStyle    = lipgloss.NewStyle().Border(lipgloss.RoundedBorder()).Padding(0, 1)
	npBoxStyle  = boxStyle.BorderForeground(accent)
)

// Options are the view settings that are not part of model.State.
type Options struct {
	Width, Height int
	Now           time.Time
	Blink         bool                           // blink the beat dot
	ShowWave      bool                           // waveform row in deck boxes
	StreamUp      bool                           // SSE stream connected
	Waves         map[int]*model.WaveformPreview // per player, only for its current track
}

// decksPerRow is how many deck boxes fit side by side in width w.
func decksPerRow(w int) int {
	if n := w / deckMinW; n > 1 {
		return n
	}
	return 1
}

// Render draws the whole screen. Every line is at most Width cells and there
// are at most Height lines.
func Render(st *model.State, o Options) string {
	if o.Width < MinWidth || o.Height < MinHeight {
		return notice(o.Width, o.Height)
	}
	W, H := o.Width, o.Height

	nums := make([]int, 0, len(st.Players))
	for n := range st.Players {
		nums = append(nums, n)
	}
	sort.Ints(nums)

	per := decksPerRow(W)
	boxW := W / per
	rows := (len(nums) + per - 1) / per
	avail := H - 1 - npHeight - 1 - logMinH
	showWave := o.ShowWave
	deckH := 6
	if !showWave || rows*deckH > avail {
		showWave, deckH = false, 5
	}
	hidden := 0
	if rows*deckH > avail {
		rows = avail / deckH
		hidden = len(nums) - rows*per
		nums = nums[:rows*per]
	}

	parts := []string{header(st, o), nowPlaying(st, o)}
	for r := 0; r < rows; r++ {
		end := (r + 1) * per
		if end > len(nums) {
			end = len(nums)
		}
		var boxes []string
		for _, n := range nums[r*per : end] {
			boxes = append(boxes, deck(st, n, o, boxW, showWave))
		}
		parts = append(parts, lipgloss.JoinHorizontal(lipgloss.Top, boxes...))
	}
	logH := H - 1 - npHeight - rows*deckH - 1
	parts = append(parts, eventLog(st.Log, W, logH), footer(o, hidden, W))
	return lipgloss.JoinVertical(lipgloss.Left, parts...)
}

func notice(w, h int) string {
	lines := []string{
		"ターミナルを広げてください",
		fmt.Sprintf("%d×%d 以上が必要です（現在 %d×%d）", MinWidth, MinHeight, w, h),
	}
	for i := range lines {
		lines[i] = truncate(lines[i], w)
	}
	if h < len(lines) {
		lines = lines[:max(h, 0)]
	}
	if w <= 0 || h <= 0 {
		return ""
	}
	return lipgloss.Place(w, h, lipgloss.Center, lipgloss.Center, strings.Join(lines, "\n"))
}

func header(st *model.State, o Options) string {
	right := o.Now.Format("15:04:05")
	name := boldStyle.Render("cdj-music-link")
	status := accentStyle.Render("●online")
	if !st.Online {
		status = redStyle.Render("○offline")
	}
	var rest strings.Builder
	if st.DeviceNumber != nil {
		fmt.Fprintf(&rest, "  player %d", *st.DeviceNumber)
	}
	rest.WriteString("   devices:")
	for _, d := range st.Devices {
		fmt.Fprintf(&rest, " %d %s", d.Number, d.Name)
	}
	if len(st.Devices) == 0 {
		rest.WriteString(" none")
	}
	left := name + "  " + status
	room := o.Width - lipgloss.Width(right) - 1 - lipgloss.Width(left)
	left += truncate(rest.String(), room)
	return pad(left, o.Width-lipgloss.Width(right)) + right
}

func trackTitle(t *model.Track) string {
	if t.Title == nil {
		return "(metadata pending)"
	}
	return `"` + *t.Title + `"`
}

func bpmText(p model.Player) string {
	switch {
	case p.Tempo != nil:
		return fmt.Sprintf("%.1f BPM", *p.Tempo)
	case p.Track != nil && p.Track.StartingBpm != nil:
		return fmt.Sprintf("%.1f BPM", *p.Track.StartingBpm)
	}
	return "? BPM"
}

func lastBeat(st *model.State, n int) *model.LastBeat {
	if lb, ok := st.Beats[n]; ok {
		return &lb
	}
	return nil
}

func nowPlaying(st *model.State, o Options) string {
	inner := o.Width - 4
	label := accentStyle.Render("NOW PLAYING") + "  "
	room := inner - lipgloss.Width(label)
	lines := make([]string, 3)
	if st.NowPlaying == nil {
		lines[0] = label + dimStyle.Render("(nothing playing)")
		return npBoxStyle.Width(o.Width - 2).Render(strings.Join(lines, "\n"))
	}
	p := *st.NowPlaying
	if cur, ok := st.Players[p.Number]; ok {
		p = cur
	}
	t := p.Track
	if t == nil {
		lines[0] = label + truncate(fmt.Sprintf("player %d  (no track)", p.Number), room)
	} else {
		var suffix strings.Builder
		if t.Key != nil {
			suffix.WriteString("  " + *t.Key)
		}
		suffix.WriteString("  " + bpmText(p))
		artist := ""
		if t.Artist != nil {
			artist = " — " + *t.Artist
		}
		lines[0] = label + truncate(trackTitle(t)+artist, room-lipgloss.Width(suffix.String())) + suffix.String()
	}

	lb := lastBeat(st, p.Number)
	pos := positionMs(p, lb, o.Now)
	var dur *int64
	if t != nil && t.DurationSec != nil {
		d := int64(*t.DurationSec) * 1000
		dur = &d
	}
	barW := inner - 14
	filled := 0
	if pos != nil && dur != nil && *dur > 0 {
		filled = int(float64(*pos) / float64(*dur) * float64(barW))
		filled = min(max(filled, 0), barW)
	}
	lines[1] = clock(pos) + " ▕" + strings.Repeat("█", filled) + dimStyle.Render(strings.Repeat("░", barW-filled)) + "▏ " + clock(dur)

	bar, dots := "-", beatDots(p, lb, o)
	if lb != nil && lb.BeatNumber != nil && *lb.BeatNumber > 0 {
		bar = fmt.Sprint((*lb.BeatNumber-1)/4 + 1)
	}
	lines[2] = fmt.Sprintf("player %d  bar %s  beat ", p.Number, bar) + dots
	return npBoxStyle.Width(o.Width - 2).Render(strings.Join(lines, "\n"))
}

// beatDots is ●○○○ with the filled dot at beatWithinBar. With Blink the dot
// goes dark for the second half of each beat while playing.
func beatDots(p model.Player, lb *model.LastBeat, o Options) string {
	bwb := 0
	if lb != nil && lb.BeatWithinBar != nil {
		bwb = *lb.BeatWithinBar
	} else if p.BeatWithinBar != nil {
		bwb = *p.BeatWithinBar
	}
	if o.Blink && p.IsPlaying && lb != nil {
		tempo := 0.0
		if lb.Tempo != nil {
			tempo = *lb.Tempo
		} else if p.Tempo != nil {
			tempo = *p.Tempo
		}
		if tempo > 0 && o.Now.Sub(lb.At) > time.Duration(30000/tempo*float64(time.Millisecond)) {
			bwb = 0
		}
	}
	var b strings.Builder
	for i := 1; i <= 4; i++ {
		if i == bwb {
			b.WriteString(accentStyle.Render("●"))
		} else {
			b.WriteString("○")
		}
	}
	return b.String()
}

func deck(st *model.State, n int, o Options, boxW int, showWave bool) string {
	inner := boxW - 4
	p := st.Players[n]
	t := p.Track
	lines := make([]string, 3, 4)

	state := p.PlayState
	if t == nil {
		state = "empty"
	}
	head := boldStyle.Render(fmt.Sprintf("player %d", n)) + "  "
	if state == "playing" {
		head += boldStyle.Render(state)
	} else {
		head += dimStyle.Render(state)
	}
	if p.IsTempoMaster {
		head += "  " + accentStyle.Render("MASTER")
	}
	if p.IsOnAir != nil && *p.IsOnAir {
		head += "  " + redStyle.Render("ON AIR")
	}
	if lipgloss.Width(head) > inner {
		head = truncate(fmt.Sprintf("player %d  %s", n, state), inner)
	}
	lines[0] = head

	if t == nil {
		lines[1] = dimStyle.Render("(no track)")
	} else {
		pre := fmt.Sprintf("id %d  ", t.ID)
		var suf strings.Builder
		if t.Key != nil {
			suf.WriteString("  " + *t.Key)
		}
		suf.WriteString("  " + bpmText(p))
		room := inner - lipgloss.Width(pre) - lipgloss.Width(suf.String())
		lines[1] = truncate(pre+truncate(trackTitle(t), room)+suf.String(), inner)
	}

	var pos, dur *int64
	if t != nil {
		pos = positionMs(p, lastBeat(st, n), o.Now)
	}
	if t != nil && t.DurationSec != nil {
		d := int64(*t.DurationSec) * 1000
		dur = &d
	}
	lines[2] = truncate(fmt.Sprintf("%s / %s   pitch %+.2f%%", clock(pos), clock(dur), p.Pitch), inner)

	if showWave {
		row := ""
		if w := o.Waves[n]; w != nil && t != nil {
			row = waveRow(w, pos, t.DurationSec, inner)
		}
		lines = append(lines, row)
	}
	return boxStyle.Width(boxW - 2).Render(strings.Join(lines, "\n"))
}

func eventLog(log []string, w, h int) string {
	innerW, innerH := w-4, max(h-2, 1)
	start := max(len(log)-innerH, 0)
	lines := make([]string, 0, len(log)-start)
	for _, l := range log[start:] {
		lines = append(lines, truncate(l, innerW))
	}
	vp := viewport.New(innerW, innerH)
	vp.SetContent(strings.Join(lines, "\n"))
	vp.GotoBottom()
	return boxStyle.Width(w - 2).Render(vp.View())
}

func footer(o Options, hidden, w int) string {
	onoff := func(b bool) string {
		if b {
			return "on"
		}
		return "off"
	}
	s := fmt.Sprintf("q quit   b blink:%s   w wave:%s   stream:", onoff(o.Blink), onoff(o.ShowWave))
	if o.StreamUp {
		s += "live"
	} else {
		s += "reconnecting"
	}
	if hidden > 0 {
		s += fmt.Sprintf("   +%d decks hidden (enlarge terminal)", hidden)
	}
	return dimStyle.Render(truncate(s, w))
}
