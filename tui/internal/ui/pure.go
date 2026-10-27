package ui

import (
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/lipgloss"
	"github.com/silmin/cdj-music-link/tui/internal/model"
)

// positionMs is the playback position at now. While playing it advances the
// last beat's position (or, without one, the player's position) by the
// elapsed wall time scaled by the pitch. When not playing the player's own
// position wins (a cue or seek after the last beat). nil when nothing is known.
// offset is State.ClockOffset: Position.UpdatedAt is server time.
func positionMs(p model.Player, lb *model.LastBeat, now time.Time, offset time.Duration) *int64 {
	var base int64
	var since time.Time
	switch {
	case !p.IsPlaying && p.Position != nil:
		// paused / cued: the player status is newer than the last beat
		base = p.Position.Ms
	case lb != nil && lb.PositionMs != nil:
		base, since = *lb.PositionMs, lb.At
	case p.Position != nil:
		base, since = p.Position.Ms, time.UnixMilli(p.Position.UpdatedAt).Add(offset)
	default:
		return nil
	}
	if p.IsPlaying {
		if el := now.Sub(since); el > 0 {
			base += int64(float64(el.Milliseconds()) * (1 + p.Pitch/100))
		}
	}
	if p.Track != nil && p.Track.DurationSec != nil {
		if max := int64(*p.Track.DurationSec) * 1000; max > 0 && base > max {
			base = max
		}
	}
	return &base
}

// clock formats ms as mm:ss; nil is --:--.
func clock(ms *int64) string {
	if ms == nil {
		return "--:--"
	}
	s := *ms / 1000
	if s < 0 {
		s = 0
	}
	return fmt.Sprintf("%02d:%02d", s/60, s%60)
}

var blocks = []rune("▁▂▃▄▅▆▇█")

// quantise reduces heights to width buckets (max per bucket) and maps each
// to a level 0..7 of the block glyphs.
func quantise(heights []int, maxHeight, width int) []int {
	out := make([]int, width)
	n := len(heights)
	if n == 0 || width <= 0 {
		return out
	}
	if maxHeight <= 0 {
		maxHeight = 1
	}
	for i := 0; i < width; i++ {
		start, end := i*n/width, (i+1)*n/width
		if end <= start {
			end = start + 1
		}
		if end > n {
			end = n
		}
		m := 0
		for _, h := range heights[start:end] {
			if h > m {
				m = h
			}
		}
		lv := m * 7 / maxHeight
		if lv > 7 {
			lv = 7
		}
		if lv < 0 {
			lv = 0
		}
		out[i] = lv
	}
	return out
}

// cursorCol is the bucket of pos within a track of durSec, clamped to the row.
func cursorCol(posMs int64, durSec, width int) int {
	if width <= 0 {
		return 0
	}
	c := 0
	if durSec > 0 {
		c = int(float64(posMs) / float64(durSec*1000) * float64(width))
	}
	if c < 0 {
		c = 0
	}
	if c >= width {
		c = width - 1
	}
	return c
}

// waveRow renders the preview in width cells, the cursor bucket reversed.
func waveRow(w *model.WaveformPreview, pos *int64, durSec *int, width int) string {
	levels := quantise(w.Heights, w.MaxHeight, width)
	cur := -1
	if pos != nil && durSec != nil {
		cur = cursorCol(*pos, *durSec, width)
	}
	var b strings.Builder
	for i, lv := range levels {
		g := string(blocks[lv])
		if i == cur {
			g = cursorStyle.Render(g)
		}
		b.WriteString(g)
	}
	return b.String()
}

// truncate cuts plain text s to at most w display cells, ending with … when cut.
func truncate(s string, w int) string {
	if lipgloss.Width(s) <= w {
		return s
	}
	if w <= 0 {
		return ""
	}
	var b strings.Builder
	used := 0
	for _, r := range s {
		rw := lipgloss.Width(string(r))
		if used+rw > w-1 {
			break
		}
		b.WriteRune(r)
		used += rw
	}
	return b.String() + "…"
}

// pad right-pads plain or styled s with spaces to w cells (no truncation).
func pad(s string, w int) string {
	if d := w - lipgloss.Width(s); d > 0 {
		return s + strings.Repeat(" ", d)
	}
	return s
}
