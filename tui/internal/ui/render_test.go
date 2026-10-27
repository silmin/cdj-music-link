package ui

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/lipgloss"
	"github.com/silmin/cdj-music-link/tui/internal/model"
)

var t0 = time.Date(2026, 10, 25, 12, 0, 0, 0, time.Local)

func ptr[T any](v T) *T { return &v }

func fabricate(n int) *model.State {
	st := model.NewState()
	st.Online = true
	st.DeviceNumber = ptr(5)
	for i := 1; i <= n; i++ {
		st.Devices = append(st.Devices, model.Device{Number: i, Name: "CDJ-2000nexus", Kind: "player"})
		p := model.Player{
			Number: i, Name: "CDJ-2000nexus", PlayState: "playing", IsPlaying: i%2 == 1,
			Pitch: 3.25, Tempo: ptr(178.0), IsOnAir: ptr(i == 1), IsTempoMaster: i == 1,
			Position: &model.Position{Ms: 61000, UpdatedAt: t0.UnixMilli()},
			Track: &model.Track{
				ID: 300 + i, Title: ptr(strings.Repeat("A very long track title ", 3)),
				Artist: ptr("Some Artist With A Long Name"), Key: ptr("Dbm"),
				DurationSec: ptr(296), WaveformAvailable: true,
			},
		}
		if i == 4 {
			p.Track, p.PlayState, p.IsPlaying, p.Position = nil, "empty", false, nil
		}
		st.Players[i] = p
		st.Beats[i] = model.LastBeat{At: t0, PositionMs: ptr(int64(60000)), BeatNumber: ptr(129), BeatWithinBar: ptr(2), Tempo: ptr(178.0)}
	}
	if n > 0 {
		np := st.Players[1]
		st.NowPlaying = &np
		st.Master = model.MasterInfo{Player: ptr(1), Tempo: ptr(178.0)}
	}
	for i := 0; i < 40; i++ {
		st.AddLog(t0, fmt.Sprintf("player 1  metadata  \"夜の曲 %d — with a long long long long long long long long long long tail\"", i))
	}
	return st
}

func waves(n int) map[int]*model.WaveformPreview {
	w := map[int]*model.WaveformPreview{}
	for i := 1; i <= n; i++ {
		h := make([]int, 400)
		for j := range h {
			h[j] = j % 32
		}
		w[i] = &model.WaveformPreview{Segments: 400, MaxHeight: 31, Heights: h}
	}
	return w
}

func checkFits(t *testing.T, out string, w, h int) {
	t.Helper()
	lines := strings.Split(out, "\n")
	if len(lines) > h {
		t.Errorf("%dx%d: %d lines > %d\n%s", w, h, len(lines), h, out)
	}
	for i, l := range lines {
		if lw := lipgloss.Width(l); lw > w {
			t.Errorf("%dx%d: line %d width %d > %d: %q", w, h, i, lw, w, l)
		}
	}
}

func TestRenderFits80x24(t *testing.T) {
	for n := 1; n <= 4; n++ {
		for _, wave := range []bool{true, false} {
			st := fabricate(n)
			out := Render(st, Options{Width: 80, Height: 24, Now: t0.Add(time.Second), Blink: true, ShowWave: wave, Waves: waves(n), StreamUp: true})
			checkFits(t, out, 80, 24)
			for i := 1; i <= n; i++ {
				if !strings.Contains(out, fmt.Sprintf("player %d", i)) {
					t.Errorf("n=%d: deck %d missing\n%s", n, i, out)
				}
			}
		}
	}
}

func TestRenderFits120x30(t *testing.T) {
	st := fabricate(4)
	out := Render(st, Options{Width: 120, Height: 30, Now: t0, ShowWave: true, Waves: waves(4), StreamUp: true})
	checkFits(t, out, 120, 30)
	if !strings.Contains(out, "NOW PLAYING") || !strings.Contains(out, "(no track)") || !strings.Contains(out, "--:-- / --:--") {
		t.Errorf("missing content\n%s", out)
	}
}

func TestRenderManyDecksStillFits(t *testing.T) {
	st := fabricate(4)
	for i := 5; i <= 6; i++ {
		p := st.Players[1]
		p.Number = i
		st.Players[i] = p
	}
	out := Render(st, Options{Width: 80, Height: 24, Now: t0, ShowWave: true, Waves: waves(6)})
	checkFits(t, out, 80, 24)
}

func TestRenderEmptyState(t *testing.T) {
	st := model.NewState()
	out := Render(st, Options{Width: 80, Height: 24, Now: t0})
	checkFits(t, out, 80, 24)
	if !strings.Contains(out, "offline") {
		t.Errorf("expected offline header\n%s", out)
	}
}

func TestRenderTooSmallShowsNotice(t *testing.T) {
	out := Render(fabricate(2), Options{Width: 60, Height: 20, Now: t0})
	if !strings.Contains(out, "ターミナルを広げてください") || strings.Contains(out, "NOW PLAYING") {
		t.Errorf("notice expected\n%s", out)
	}
	checkFits(t, out, 60, 20)
}

func TestDecksPerRow(t *testing.T) {
	for _, c := range []struct{ w, want int }{{80, 2}, {119, 2}, {120, 3}, {160, 4}, {40, 1}} {
		if got := decksPerRow(c.w); got != c.want {
			t.Errorf("decksPerRow(%d) = %d, want %d", c.w, got, c.want)
		}
	}
}

func TestQuantise(t *testing.T) {
	h := make([]int, 1200)
	for i := range h {
		h[i] = 0
	}
	h[0], h[33], h[34] = 31, 5, 16 // bucket 0 = max(31,...)=31; bucket 1 = 16
	got := quantise(h, 31, 36)
	if len(got) != 36 {
		t.Fatalf("len %d", len(got))
	}
	if got[0] != 7 || got[1] != 3 || got[2] != 0 {
		t.Errorf("levels %v", got[:3])
	}
	if c := cursorCol(148000, 296, 36); c != 18 {
		t.Errorf("cursor at 50%% = %d, want 18", c)
	}
	if c := cursorCol(999999, 296, 36); c != 35 {
		t.Errorf("cursor clamp = %d", c)
	}
	if c := cursorCol(-5, 296, 36); c != 0 {
		t.Errorf("cursor clamp low = %d", c)
	}
	if got := quantise(nil, 31, 10); len(got) != 10 {
		t.Errorf("empty heights: %v", got)
	}
	if got := quantise([]int{31, 0}, 31, 4); got[0] != 7 || got[3] != 0 {
		t.Errorf("upsample: %v", got)
	}
}

func TestWaveRowWidth(t *testing.T) {
	w := waves(1)[1]
	row := waveRow(w, ptr(int64(148000)), ptr(296), 36)
	if lw := lipgloss.Width(row); lw != 36 {
		t.Errorf("width %d", lw)
	}
}

func TestInterpolate(t *testing.T) {
	p := model.Player{IsPlaying: true, Pitch: 10}
	lb := model.LastBeat{At: t0, PositionMs: ptr(int64(10000))}
	got := positionMs(p, &lb, t0.Add(time.Second), 0)
	if got == nil || *got != 11100 {
		t.Fatalf("playing: %v", got)
	}
	p.IsPlaying = false
	if got := positionMs(p, &lb, t0.Add(time.Second), 0); got == nil || *got != 10000 {
		t.Fatalf("paused: %v", got)
	}
	// no beat: player position + elapsed since updatedAt
	p = model.Player{IsPlaying: true, Position: &model.Position{Ms: 5000, UpdatedAt: t0.UnixMilli()}}
	if got := positionMs(p, nil, t0.Add(2*time.Second), 0); got == nil || *got != 7000 {
		t.Fatalf("no beat: %v", got)
	}
	// beat without position falls back to player position
	if got := positionMs(p, &model.LastBeat{At: t0}, t0, 0); got == nil || *got != 5000 {
		t.Fatalf("beat nil pos: %v", got)
	}
	if got := positionMs(model.Player{}, nil, t0, 0); got != nil {
		t.Fatalf("nil position: %v", *got)
	}
}

func TestClock(t *testing.T) {
	if s := clock(nil); s != "--:--" {
		t.Error(s)
	}
	if s := clock(ptr(int64(61999))); s != "01:01" {
		t.Error(s)
	}
}

func TestTruncate(t *testing.T) {
	if s := truncate("夜の曲abc", 5); s != "夜の…" || lipgloss.Width(s) != 5 {
		t.Errorf("%q", s)
	}
	if s := truncate("abc", 5); s != "abc" {
		t.Errorf("%q", s)
	}
}

func TestInterpolatePausedPrefersPlayerPosition(t *testing.T) {
	// beats came while playing; then paused and cued back to 0:05
	lb := model.LastBeat{At: t0, PositionMs: ptr(int64(90000)), BeatNumber: ptr(160)}
	p := model.Player{IsPlaying: false, Position: &model.Position{Ms: 5000, UpdatedAt: t0.Add(time.Second).UnixMilli()}}
	if got := positionMs(p, &lb, t0.Add(10*time.Second), 0); got == nil || *got != 5000 {
		t.Fatalf("paused deck shows %v, want player position 5000", got)
	}
	// playing still uses the beat
	p.IsPlaying = true
	if got := positionMs(p, &lb, t0, 0); got == nil || *got != 90000 {
		t.Fatalf("playing: %v", got)
	}
}

func TestNewTrackIgnoresOldBeat(t *testing.T) {
	st := fabricate(1)
	// last beat of the previous track at 4:00
	st.Beats[1] = model.LastBeat{At: t0, PositionMs: ptr(int64(240000)), BeatNumber: ptr(400), BeatWithinBar: ptr(1)}
	np := st.Players[1]
	np.Track = &model.Track{ID: 999, SourcePlayer: 4, Slot: "usb", DurationSec: ptr(200)}
	np.PlayState, np.IsPlaying = "cued", false
	np.Position = &model.Position{Ms: 0, UpdatedAt: t0.UnixMilli()}
	st.Apply(model.Event{Kind: model.EventPlayer, At: t0, Player: &np})
	out := Render(st, Options{Width: 80, Height: 24, Now: t0.Add(time.Second)})
	if !strings.Contains(out, "00:00 / 03:20") {
		t.Errorf("deck should show the new track at 00:00\n%s", out)
	}
	if strings.Contains(out, "bar 100") || !strings.Contains(out, "bar -") {
		t.Errorf("NOW PLAYING bar should not come from the old track\n%s", out)
	}
}

func TestProgressLineLongClocks(t *testing.T) {
	st := fabricate(1)
	p := st.Players[1]
	p.Track.DurationSec = ptr(7200) // 120:00
	st.Players[1] = p
	st.NowPlaying = &p
	st.Beats[1] = model.LastBeat{At: t0, PositionMs: ptr(int64(6100000))} // 101:40
	checkFits(t, Render(st, Options{Width: 80, Height: 24, Now: t0}), 80, 24)
	if out := Render(st, Options{Width: 80, Height: 24, Now: t0}); !strings.Contains(out, "101:40 ▕") || !strings.Contains(out, "▏ 120:00") {
		t.Errorf("long clocks\n%s", out)
	}
	// no duration, position keeps growing
	p.Track.DurationSec = nil
	st.Players[1] = p
	st.NowPlaying = &p
	checkFits(t, Render(st, Options{Width: 80, Height: 24, Now: t0.Add(time.Hour)}), 80, 24)
}

func TestInterpolateAppliesClockOffset(t *testing.T) {
	// Pi clock 10 s behind the laptop: updatedAt is "old" by 10 s in laptop time.
	p := model.Player{IsPlaying: true, Position: &model.Position{Ms: 5000, UpdatedAt: t0.Add(-10 * time.Second).UnixMilli()}}
	if got := positionMs(p, nil, t0.Add(2*time.Second), 10*time.Second); got == nil || *got != 7000 {
		t.Fatalf("with offset: %v, want 7000", got)
	}
	if got := positionMs(p, nil, t0.Add(2*time.Second), 0); got == nil || *got != 17000 {
		t.Fatalf("without offset: %v, want 17000", got)
	}
}
