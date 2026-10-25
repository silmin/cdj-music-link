package ui

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/silmin/cdj-music-link/tui/internal/model"
)

type fakeClient struct {
	snap      model.Snapshot
	snapErr   error
	np        *model.Player
	waveCalls map[int]int
	waveErr   error
}

func (f *fakeClient) Snapshot() (model.Snapshot, error)  { return f.snap, f.snapErr }
func (f *fakeClient) NowPlaying() (*model.Player, error) { return f.np, nil }
func (f *fakeClient) WaveformPreview(n int) (*model.WaveformPreview, error) {
	f.waveCalls[n]++
	if f.waveErr != nil {
		return nil, f.waveErr
	}
	return &model.WaveformPreview{Segments: 2, MaxHeight: 1, Heights: []int{1, 0}}, nil
}
func (f *fakeClient) Stream(ctx context.Context) (<-chan model.Event, error) {
	return make(chan model.Event), nil
}

func player(n, id int, playing, wave bool) model.Player {
	return model.Player{Number: n, PlayState: "playing", IsPlaying: playing,
		Track: &model.Track{ID: id, SourcePlayer: 4, Slot: "usb", WaveformAvailable: wave}}
}

func newTestModel(f *fakeClient, players ...model.Player) *Model {
	m := New(f, model.Snapshot{Online: true, Players: players})
	m.nowFn = func() time.Time { return t0 }
	m.now = t0
	return m
}

func open(m *Model) {
	m.Update(streamOpenedMsg{m.gen, make(chan model.Event), func() {}})
}

func logHas(m *Model, s string) bool {
	return strings.Contains(strings.Join(m.st.Log, "\n"), s)
}

func TestReconnectReplacesState(t *testing.T) {
	f := &fakeClient{waveCalls: map[int]int{}}
	m := newTestModel(f, player(1, 10, true, false), player(2, 20, true, false))
	open(m)
	m.Update(streamClosedMsg{m.gen})
	if m.streamUp || !logHas(m, "link: stream lost, reconnecting…") {
		t.Fatalf("closed not handled: %v", m.st.Log)
	}
	f.snap = model.Snapshot{Online: true, Players: []model.Player{player(3, 30, false, false)}}
	_, cmd := m.Update(reconnectMsg{m.gen})
	msg := cmd()
	m.Update(msg)
	if _, ok := m.st.Players[1]; ok || len(m.st.Players) != 1 {
		t.Fatalf("stale players survived: %v", m.st.Players)
	}
	open(m)
	if !m.streamUp || !logHas(m, "link: reconnected") {
		t.Fatalf("reconnect not logged: %v", m.st.Log)
	}
}

func TestSnapshotFailureRetriesAndLogsOnce(t *testing.T) {
	f := &fakeClient{waveCalls: map[int]int{}, snapErr: errors.New("connection refused")}
	m := newTestModel(f)
	open(m)
	m.Update(streamClosedMsg{m.gen})
	for i := 0; i < 3; i++ {
		_, cmd := m.Update(reconnectMsg{m.gen})
		m.Update(cmd())
	}
	n := strings.Count(strings.Join(m.st.Log, "\n"), "connection refused")
	if n != 1 {
		t.Fatalf("logged %d times: %v", n, m.st.Log)
	}
}

func TestStaleGenerationIgnored(t *testing.T) {
	f := &fakeClient{waveCalls: map[int]int{}}
	m := newTestModel(f, player(1, 10, true, false))
	open(m)
	old := m.gen
	m.Update(streamClosedMsg{old})
	m.Update(eventMsg{old, model.Event{Kind: model.EventOnline, Online: &model.SseOnline{Online: false}}})
	if !m.st.Online {
		t.Fatal("event of a dropped stream was applied")
	}
	cancelled := false
	m.Update(streamOpenedMsg{old, nil, func() { cancelled = true }})
	if !cancelled || m.streamUp {
		t.Fatal("stale stream not cancelled")
	}
}

func TestQuietStreamReconnectsOnlyWhilePlaying(t *testing.T) {
	f := &fakeClient{waveCalls: map[int]int{}}
	m := newTestModel(f, player(1, 10, false, false))
	open(m)
	m.Update(tickMsg(t0.Add(5 * time.Minute)))
	if !m.streamUp {
		t.Fatal("reconnected while nothing plays")
	}
	p := player(1, 10, true, false)
	m.Update(eventMsg{m.gen, model.Event{Kind: model.EventPlayer, Player: &p, At: t0}})
	m.Update(tickMsg(t0.Add(89 * time.Second)))
	if !m.streamUp {
		t.Fatal("reconnected too early")
	}
	m.Update(tickMsg(t0.Add(90 * time.Second)))
	if m.streamUp || !logHas(m, "no events for 90s") {
		t.Fatalf("quiet stream not dropped: %v", m.st.Log)
	}
}

func TestWaveformFetchedOncePerTrack(t *testing.T) {
	f := &fakeClient{waveCalls: map[int]int{}}
	m := newTestModel(f, player(1, 10, true, false))
	open(m)
	ev := func(p model.Player) {
		_, cmd := m.Update(eventMsg{m.gen, model.Event{Kind: model.EventPlayer, Player: &p, At: t0}})
		for _, w := range waveMsgs(cmd) {
			m.Update(w)
		}
	}
	ev(player(1, 10, true, false)) // no waveform yet
	if f.waveCalls[1] != 0 {
		t.Fatal("fetched without waveformAvailable")
	}
	ev(player(1, 10, true, true))
	ev(player(1, 10, true, true))
	if f.waveCalls[1] != 1 || m.waves[1].preview == nil {
		t.Fatalf("calls %d preview %v", f.waveCalls[1], m.waves[1].preview)
	}
	f.waveErr = errors.New("404")
	ev(player(1, 11, true, true))
	ev(player(1, 11, true, true))
	if f.waveCalls[1] != 2 || m.waves[1].preview != nil {
		t.Fatalf("after error: calls %d", f.waveCalls[1])
	}
}

// waveMsgs runs cmd (and a batch's children) with a short deadline and keeps
// the waveform results; blocking cmds (waitEvent, tea.Tick) are abandoned.
func waveMsgs(cmd tea.Cmd) []waveformMsg {
	if cmd == nil {
		return nil
	}
	done := make(chan tea.Msg, 1)
	go func() { done <- cmd() }()
	select {
	case msg := <-done:
		switch msg := msg.(type) {
		case tea.BatchMsg:
			var out []waveformMsg
			for _, c := range msg {
				out = append(out, waveMsgs(c)...)
			}
			return out
		case waveformMsg:
			return []waveformMsg{msg}
		}
	case <-time.After(50 * time.Millisecond):
	}
	return nil
}
