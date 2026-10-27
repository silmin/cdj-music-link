package model

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"testing"
	"time"
)

var t0 = time.Date(2026, 10, 25, 12, 34, 56, 0, time.Local)

func mustPlayer(t *testing.T, js string) Player {
	t.Helper()
	var p Player
	if err := json.Unmarshal([]byte(js), &p); err != nil {
		t.Fatal(err)
	}
	return p
}

func ev(t *testing.T, name, data string) Event {
	t.Helper()
	e, err := DecodeEvent(name, []byte(data))
	if err != nil {
		t.Fatal(err)
	}
	e.At = t0
	return e
}

func ip(v int) *int { return &v }

func lastLog(s *State) string {
	if len(s.Log) == 0 {
		return ""
	}
	return s.Log[len(s.Log)-1]
}

func TestReplaceSnapshotDropsStaleState(t *testing.T) {
	s := NewState()
	p1 := mustPlayer(t, playerJSON)
	p2 := mustPlayer(t, emptyPlayerJSON)
	s.ReplaceSnapshot(Snapshot{
		Online: true, DeviceNumber: ip(5),
		Devices:    []Device{{Number: 1, Name: "a", Address: "x", Kind: "player"}, {Number: 2, Name: "b", Address: "y", Kind: "player"}},
		Players:    []Player{p1, p2},
		Master:     MasterInfo{Player: ip(1)},
		NowPlaying: &p1,
	})
	s.Apply(ev(t, "beat", beatJSON))
	if len(s.Players) != 2 || len(s.Beats) != 1 || s.NowPlaying == nil {
		t.Fatalf("setup: %+v", s)
	}

	s.ReplaceSnapshot(Snapshot{Online: false, Players: []Player{p2}, Devices: []Device{{Number: 2, Name: "b", Address: "y", Kind: "player"}}})
	if _, ok := s.Players[1]; ok {
		t.Fatal("player 1 must be gone after ReplaceSnapshot")
	}
	if len(s.Players) != 1 || s.Players[2].Number != 2 {
		t.Fatalf("players: %+v", s.Players)
	}
	if len(s.Beats) != 0 {
		t.Fatalf("beats must be cleared: %+v", s.Beats)
	}
	if s.Online || s.DeviceNumber != nil || s.Master.Player != nil || s.NowPlaying != nil || len(s.Devices) != 1 {
		t.Fatalf("not fully replaced: %+v", s)
	}
}

func TestReplaceSnapshotCopiesSlices(t *testing.T) {
	s := NewState()
	devs := []Device{{Number: 1, Name: "a"}}
	s.ReplaceSnapshot(Snapshot{Devices: devs})
	devs[0].Name = "mutated"
	if s.Devices[0].Name != "a" {
		t.Fatal("State must not alias caller's slice")
	}
}

func TestApplySnapshotEvent(t *testing.T) {
	s := NewState()
	p1 := mustPlayer(t, playerJSON)
	s.ReplaceSnapshot(Snapshot{Online: true, DeviceNumber: ip(5), Players: []Player{p1}, NowPlaying: &p1})
	s.Apply(ev(t, "beat", beatJSON))

	snap := `{"players":[` + emptyPlayerJSON + `],"master":{"player":null,"tempo":null},"devices":[{"number":2,"name":"fake-cdj-2","address":"172.21.0.3","kind":"player"}],"online":true,"serverTime":1}`
	s.Apply(ev(t, "snapshot", snap))
	if _, ok := s.Players[1]; ok || len(s.Players) != 1 {
		t.Fatalf("old player survived snapshot event: %+v", s.Players)
	}
	if len(s.Beats) != 0 {
		t.Fatal("beats survived snapshot event")
	}
	if s.NowPlaying != nil {
		t.Fatal("now-playing pointing at a vanished player must be cleared")
	}
	if s.DeviceNumber == nil || *s.DeviceNumber != 5 {
		t.Fatal("deviceNumber (health only) should be kept across SSE snapshot")
	}
	want := []string{
		"12:34:56  snapshot",
		"12:34:56      online: true",
		"12:34:56      devices: 2 fake-cdj-2 172.21.0.3",
		"12:34:56      player 2: empty  track: (none)",
	}
	got := s.Log[len(s.Log)-4:]
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("snapshot log:\n%s", strings.Join(got, "\n"))
	}
}

func TestApplySnapshotRefreshesNowPlaying(t *testing.T) {
	s := NewState()
	p1 := mustPlayer(t, playerJSON)
	s.ReplaceSnapshot(Snapshot{Players: []Player{p1}, NowPlaying: &p1})
	p1b := p1
	p1b.PlayState = "paused"
	b, _ := json.Marshal(SseSnapshot{Players: []Player{p1b}, Devices: []Device{}})
	s.Apply(ev(t, "snapshot", string(b)))
	if s.NowPlaying == nil || s.NowPlaying.PlayState != "paused" {
		t.Fatalf("now-playing should be refreshed from snapshot: %+v", s.NowPlaying)
	}
}

func TestSnapshotLogTrackVariants(t *testing.T) {
	s := NewState()
	p := mustPlayer(t, playerJSON)
	q := mustPlayer(t, playerJSON)
	q.Number = 3
	q.Track.Title = nil
	b, _ := json.Marshal(SseSnapshot{Players: []Player{p, q}, Devices: []Device{}, Online: false})
	s.Apply(ev(t, "snapshot", string(b)))
	got := strings.Join(s.Log, "\n")
	for _, w := range []string{"    online: false", "    devices: (none)", "    player 1: playing  track: T", "    player 3: playing  track: (id 308, no metadata)"} {
		if !strings.Contains(got, w) {
			t.Fatalf("missing %q in\n%s", w, got)
		}
	}
}

func TestApplyPlayerLogsOnlyOnChange(t *testing.T) {
	s := NewState()
	s.Apply(ev(t, "player", playerJSON))
	if s.Players[1].PlayState != "playing" {
		t.Fatal("player not stored")
	}
	if lastLog(s) != "12:34:56  player 1  playing  128.0 BPM" {
		t.Fatalf("got %q", lastLog(s))
	}
	n := len(s.Log)
	p := mustPlayer(t, playerJSON)
	p.BeatNumber = ip(25) // not part of the change key
	b, _ := json.Marshal(p)
	s.Apply(ev(t, "player", string(b)))
	if len(s.Log) != n {
		t.Fatalf("unchanged key must not log: %q", lastLog(s))
	}
	if *s.Players[1].BeatNumber != 25 {
		t.Fatal("player must still be updated")
	}
	p.IsTempoMaster = true
	on := true
	p.IsOnAir = &on
	tempo := 127.96
	p.Tempo = &tempo
	b, _ = json.Marshal(p)
	s.Apply(ev(t, "player", string(b)))
	if lastLog(s) != "12:34:56  player 1  playing  128.0 BPM  master  on-air" {
		t.Fatalf("got %q", lastLog(s))
	}
	s.Apply(ev(t, "player", emptyPlayerJSON))
	if lastLog(s) != "12:34:56  player 2  empty  on-air" {
		t.Fatalf("got %q", lastLog(s))
	}
}

func TestApplyPlayerRefreshesNowPlaying(t *testing.T) {
	s := NewState()
	p := mustPlayer(t, playerJSON)
	s.ReplaceSnapshot(Snapshot{Players: []Player{p}, NowPlaying: &p})
	p.PlayState = "paused"
	b, _ := json.Marshal(p)
	s.Apply(ev(t, "player", string(b)))
	if s.NowPlaying.PlayState != "paused" {
		t.Fatal("now-playing copy should follow player events")
	}
}

func TestApplyTrack(t *testing.T) {
	s := NewState()
	s.Apply(ev(t, "player", emptyPlayerJSON))
	s.Apply(ev(t, "track", `{"player":2,"track":{"id":101,"sourcePlayer":2,"slot":"usb","type":"rekordbox","artworkAvailable":false,"waveformAvailable":false,"beatgridAvailable":false}}`))
	if s.Players[2].Track == nil || s.Players[2].Track.ID != 101 {
		t.Fatal("track not applied to player")
	}
	if lastLog(s) != "12:34:56  player 2  track loaded  id=101 from device 2 (usb, rekordbox)" {
		t.Fatalf("got %q", lastLog(s))
	}
	full := `{"player":2,"track":{"id":101,"sourcePlayer":2,"slot":"usb","type":"rekordbox","title":"Song","artist":null,"key":"8A","startingBpm":127.6,"durationSec":185,"artworkAvailable":true,"waveformAvailable":true,"beatgridAvailable":true}}`
	s.Apply(ev(t, "track", full))
	if lastLog(s) != "12:34:56  player 2  metadata  \"Song\" — (no artist)  key 8A  128 BPM  3:05" {
		t.Fatalf("got %q", lastLog(s))
	}
	n := len(s.Log)
	s.Apply(ev(t, "track", full))
	if len(s.Log) != n {
		t.Fatal("identical track line must not repeat")
	}
	s.Apply(ev(t, "track", `{"player":2,"track":null}`))
	if s.Players[2].Track != nil || lastLog(s) != "12:34:56  player 2  track unloaded" {
		t.Fatalf("unload: %q", lastLog(s))
	}
	// unknown player: logged, map untouched
	s.Apply(ev(t, "track", `{"player":9,"track":null}`))
	if _, ok := s.Players[9]; ok {
		t.Fatal("track event must not invent players")
	}
}

func TestApplyBeat(t *testing.T) {
	s := NewState()
	s.Apply(ev(t, "beat", beatJSON))
	b := s.Beats[1]
	if !b.At.Equal(t0) || b.PositionMs == nil || *b.PositionMs != 7471 || b.BeatWithinBar == nil || *b.BeatWithinBar != 4 || b.Tempo == nil || *b.Tempo != 128 {
		t.Fatalf("beat: %+v", b)
	}
	s.Apply(ev(t, "beat", beatNullJSON))
	b = s.Beats[1]
	if b.PositionMs != nil || b.BeatWithinBar != nil || b.Tempo != nil {
		t.Fatalf("null beat: %+v", b)
	}
	if len(s.Log) != 0 {
		t.Fatal("beats are not logged by default")
	}
	s.LogBeats = true
	s.Apply(ev(t, "beat", `{"player":1,"beatNumber":33,"beatWithinBar":1,"tempo":128,"nextBeatInMs":null,"nextBarInMs":null,"isTempoMaster":false,"position":{"ms":7471},"waveform":null,"receivedAt":1}`))
	if lastLog(s) != "12:34:56  player 1  bar  beat 33  128.0 BPM  pos 7.5s" {
		t.Fatalf("got %q", lastLog(s))
	}
	n := len(s.Log)
	s.Apply(ev(t, "beat", beatJSON)) // beatWithinBar 4: not a bar
	if len(s.Log) != n {
		t.Fatal("only downbeats are logged")
	}
}

func TestApplyBeatZeroAtUsesNow(t *testing.T) {
	s := NewState()
	e, _ := DecodeEvent("beat", []byte(beatJSON))
	before := time.Now()
	s.Apply(e)
	if s.Beats[1].At.Before(before) {
		t.Fatal("zero At should fall back to time.Now()")
	}
}

func TestApplyDevice(t *testing.T) {
	s := NewState()
	s.Apply(ev(t, "player", emptyPlayerJSON))
	s.Apply(ev(t, "beat", `{"player":2,"beatNumber":null,"beatWithinBar":null,"tempo":null,"nextBeatInMs":null,"nextBarInMs":null,"isTempoMaster":false,"position":null,"waveform":null,"receivedAt":1}`))
	s.Apply(ev(t, "device", `{"action":"connected","device":{"number":2,"name":"fake-cdj-2","address":"172.21.0.3","kind":"player"}}`))
	s.Apply(ev(t, "device", `{"action":"connected","device":{"number":1,"name":"fake-cdj-1","address":"172.21.0.2","kind":"player"}}`))
	if len(s.Devices) != 2 || lastLog(s) != "12:34:56  device 1 connected  fake-cdj-1 172.21.0.2" {
		t.Fatalf("connect: %+v %q", s.Devices, lastLog(s))
	}
	s.Apply(ev(t, "device", `{"action":"connected","device":{"number":2,"name":"renamed","address":"172.21.0.3","kind":"player"}}`))
	if len(s.Devices) != 2 || s.Devices[0].Name != "renamed" {
		t.Fatalf("upsert in place: %+v", s.Devices)
	}
	s.Apply(ev(t, "device", `{"action":"disconnected","device":{"number":2,"name":"renamed","address":"172.21.0.3","kind":"player"}}`))
	if len(s.Devices) != 1 || s.Devices[0].Number != 1 {
		t.Fatalf("disconnect: %+v", s.Devices)
	}
	if _, ok := s.Players[2]; ok {
		t.Fatal("disconnect must drop the player (server does the same)")
	}
	if _, ok := s.Beats[2]; ok {
		t.Fatal("disconnect must drop the beat")
	}
	if lastLog(s) != "12:34:56  device 2 disconnected  renamed 172.21.0.3" {
		t.Fatalf("got %q", lastLog(s))
	}
}

func TestApplyMasterAndOnline(t *testing.T) {
	s := NewState()
	s.Apply(ev(t, "master", `{"player":2,"tempo":127.96}`))
	if s.Master.Player == nil || *s.Master.Player != 2 || lastLog(s) != "12:34:56  tempo master → player 2 (128.0 BPM)" {
		t.Fatalf("master: %q", lastLog(s))
	}
	s.Apply(ev(t, "master", `{"player":null,"tempo":null}`))
	if s.Master.Player != nil || lastLog(s) != "12:34:56  tempo master → none" {
		t.Fatalf("master none: %q", lastLog(s))
	}
	s.Apply(ev(t, "online", `{"online":true}`))
	if !s.Online || lastLog(s) != "12:34:56  link online" {
		t.Fatal("online")
	}
	s.Apply(ev(t, "online", `{"online":false}`))
	if s.Online || lastLog(s) != "12:34:56  link offline" {
		t.Fatal("offline")
	}
}

func TestApplyInvalidEventLogs(t *testing.T) {
	s := NewState()
	s.Apply(Event{Kind: EventInvalid, Name: "player", Err: fmt.Errorf("boom"), At: t0})
	if lastLog(s) != "12:34:56  bad event player: boom" {
		t.Fatalf("got %q", lastLog(s))
	}
}

func TestSetNowPlaying(t *testing.T) {
	s := NewState()
	p := mustPlayer(t, playerJSON)
	s.SetNowPlaying(&p, t0)
	if s.NowPlaying == nil || lastLog(s) != "12:34:56  now playing → player 1  \"T\" — (no artist)" {
		t.Fatalf("got %q", lastLog(s))
	}
	n := len(s.Log)
	s.SetNowPlaying(&p, t0)
	if len(s.Log) != n {
		t.Fatal("same now-playing must not log again")
	}
	q := mustPlayer(t, playerJSON)
	q.Track.Title = nil
	s.SetNowPlaying(&q, t0)
	if lastLog(s) != "12:34:56  now playing → player 1  id=308 (metadata pending)" {
		t.Fatalf("got %q", lastLog(s))
	}
	r := mustPlayer(t, emptyPlayerJSON)
	s.SetNowPlaying(&r, t0)
	if lastLog(s) != "12:34:56  now playing → player 2  id=? (metadata pending)" {
		t.Fatalf("got %q", lastLog(s))
	}
	s.SetNowPlaying(nil, t0)
	if s.NowPlaying != nil || lastLog(s) != "12:34:56  now playing → none" {
		t.Fatalf("got %q", lastLog(s))
	}
}

func TestLogCap(t *testing.T) {
	s := NewState()
	for i := 0; i < 600; i++ {
		s.AddLog(t0, fmt.Sprintf("line %d", i))
	}
	if len(s.Log) != MaxLog || MaxLog != 500 {
		t.Fatalf("len %d", len(s.Log))
	}
	if s.Log[0] != "12:34:56  line 100" || lastLog(s) != "12:34:56  line 599" {
		t.Fatalf("oldest dropped first: %q .. %q", s.Log[0], lastLog(s))
	}
}

func TestTrackTitleIsNotEscaped(t *testing.T) {
	s := NewState()
	s.Apply(ev(t, "track", `{"player":1,"track":{"id":1,"sourcePlayer":1,"slot":"usb","type":"rekordbox","title":"夜 \"Live\" \\ mix","artist":"アーティスト","artworkAvailable":false,"waveformAvailable":false,"beatgridAvailable":false}}`))
	if lastLog(s) != `12:34:56  player 1  metadata  "夜 "Live" \ mix" — アーティスト` {
		t.Fatalf("got %q", lastLog(s))
	}
}

func TestTrackChangeDropsStaleBeat(t *testing.T) {
	at := time.Unix(1000, 0)
	beat := func(s *State) {
		ms := int64(200000)
		s.Apply(Event{Kind: EventBeat, At: at, Beat: &SseBeat{Player: 1, BeatNumber: ip(400), Position: &BeatPosition{Ms: ms}}})
	}
	tr := func(id int) *Track { return &Track{ID: id, SourcePlayer: 4, Slot: "usb"} }

	s := NewState()
	s.Apply(Event{Kind: EventPlayer, At: at, Player: &Player{Number: 1, PlayState: "playing", Track: tr(10)}})
	beat(s)
	// same track, state change only: beat kept
	s.Apply(Event{Kind: EventPlayer, At: at, Player: &Player{Number: 1, PlayState: "paused", Track: tr(10)}})
	if _, ok := s.Beats[1]; !ok {
		t.Fatal("beat dropped although the track did not change")
	}
	// metadata arriving for the same id: kept
	title := "x"
	s.Apply(Event{Kind: EventTrack, At: at, Track: &SseTrack{Player: 1, Track: &Track{ID: 10, SourcePlayer: 4, Slot: "usb", Title: &title}}})
	if _, ok := s.Beats[1]; !ok {
		t.Fatal("beat dropped on metadata for the same track")
	}
	// new track via track event
	s.Apply(Event{Kind: EventTrack, At: at, Track: &SseTrack{Player: 1, Track: tr(11)}})
	if _, ok := s.Beats[1]; ok {
		t.Fatal("stale beat survived a track event")
	}
	// new track via player event
	beat(s)
	s.Apply(Event{Kind: EventPlayer, At: at, Player: &Player{Number: 1, PlayState: "cued", Track: tr(12)}})
	if _, ok := s.Beats[1]; ok {
		t.Fatal("stale beat survived a player event with a new track")
	}
	// unload
	beat(s)
	s.Apply(Event{Kind: EventTrack, At: at, Track: &SseTrack{Player: 1, Track: nil}})
	if _, ok := s.Beats[1]; ok {
		t.Fatal("stale beat survived unload")
	}
}

func TestClockOffsetFromSnapshots(t *testing.T) {
	s := NewState()
	// GET snapshot: server clock 3 s behind the laptop
	s.ReplaceSnapshot(Snapshot{Online: true, ServerTime: t0.Add(-3 * time.Second).UnixMilli(), At: t0})
	if s.ClockOffset != 3*time.Second {
		t.Fatalf("offset after ReplaceSnapshot: %v", s.ClockOffset)
	}
	// SSE snapshot event: server clock 5 s ahead
	snap := `{"players":[],"master":{"player":null,"tempo":null},"devices":[],"online":true,"serverTime":` +
		fmtInt(t0.Add(5*time.Second).UnixMilli()) + `}`
	s.Apply(ev(t, "snapshot", snap))
	if s.ClockOffset != -5*time.Second {
		t.Fatalf("offset after snapshot event: %v", s.ClockOffset)
	}
	// a snapshot without serverTime keeps the last offset
	s.ReplaceSnapshot(Snapshot{Online: true})
	if s.ClockOffset != -5*time.Second {
		t.Fatalf("offset after bare ReplaceSnapshot: %v", s.ClockOffset)
	}
}

func fmtInt(v int64) string { return strconv.FormatInt(v, 10) }
