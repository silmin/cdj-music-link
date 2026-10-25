package model

import (
	"encoding/json"
	"testing"
)

func TestDecodePlayerFull(t *testing.T) {
	var p Player
	if err := json.Unmarshal([]byte(playerJSON), &p); err != nil {
		t.Fatal(err)
	}
	if p.Number != 1 || p.Name != "fake-cdj-1" || p.PlayState != "playing" || !p.IsPlaying {
		t.Fatalf("basic fields: %+v", p)
	}
	if p.IsOnAir != nil {
		t.Fatalf("isOnAir null should decode to nil, got %v", *p.IsOnAir)
	}
	if p.Tempo == nil || *p.Tempo != 128 || p.TrackBpm == nil || *p.TrackBpm != 128 {
		t.Fatalf("tempo/trackBpm: %v %v", p.Tempo, p.TrackBpm)
	}
	if p.BeatNumber == nil || *p.BeatNumber != 24 || p.BeatWithinBar == nil || *p.BeatWithinBar != 4 {
		t.Fatalf("beat fields")
	}
	if p.Position == nil || p.Position.Ms != 7471 || p.Position.RemainingMs == nil || *p.Position.RemainingMs != 348509 || !p.Position.Definitive || p.Position.UpdatedAt != 1792905297010 {
		t.Fatalf("position: %+v", p.Position)
	}
	tr := p.Track
	if tr == nil || tr.ID != 308 || tr.SourcePlayer != 4 || tr.Slot != "usb" || tr.Type != "rekordbox" {
		t.Fatalf("track: %+v", tr)
	}
	if tr.Title == nil || *tr.Title != "T" || tr.Artist != nil || tr.Key == nil || *tr.Key != "Dbm" {
		t.Fatalf("track strings: %+v", tr)
	}
	if tr.DurationSec == nil || *tr.DurationSec != 355 || tr.StartingBpm == nil || *tr.StartingBpm != 178 {
		t.Fatalf("track numbers")
	}
	if tr.Rating == nil || *tr.Rating != 0 || tr.Year != nil {
		t.Fatalf("rating 0 must be non-nil, year null must be nil")
	}
	if tr.Color == nil || tr.Color.Hex != "#000000" || tr.Color.Name != "No Color" {
		t.Fatalf("color: %+v", tr.Color)
	}
	if !tr.ArtworkAvailable || !tr.WaveformAvailable || !tr.BeatgridAvailable {
		t.Fatalf("availability flags")
	}
}

func TestDecodePlayerNulls(t *testing.T) {
	var p Player
	if err := json.Unmarshal([]byte(emptyPlayerJSON), &p); err != nil {
		t.Fatal(err)
	}
	if p.Position != nil || p.Track != nil || p.Tempo != nil || p.TrackBpm != nil || p.BeatNumber != nil || p.BeatWithinBar != nil {
		t.Fatalf("nullable fields should be nil: %+v", p)
	}
	if p.IsOnAir == nil || !*p.IsOnAir {
		t.Fatalf("isOnAir true")
	}
}

func TestDecodeBeat(t *testing.T) {
	var b SseBeat
	if err := json.Unmarshal([]byte(beatJSON), &b); err != nil {
		t.Fatal(err)
	}
	if b.Player != 1 || b.Position == nil || b.Position.Ms != 7471 || b.Waveform == nil || b.Waveform.Height != 12 || b.Waveform.Color != "#ff0000" || b.ReceivedAt != 1792905297307 {
		t.Fatalf("beat: %+v", b)
	}
	if b.NextBeatInMs == nil || *b.NextBeatInMs != 468 {
		t.Fatalf("nextBeatInMs")
	}
	var n SseBeat
	if err := json.Unmarshal([]byte(beatNullJSON), &n); err != nil {
		t.Fatal(err)
	}
	if n.Position != nil || n.Waveform != nil || n.Tempo != nil || n.BeatNumber != nil || n.BeatWithinBar != nil || n.NextBeatInMs != nil || n.NextBarInMs != nil {
		t.Fatalf("null beat fields should be nil: %+v", n)
	}
}

func TestDecodeMisc(t *testing.T) {
	var m MasterInfo
	if err := json.Unmarshal([]byte(`{"player":null,"tempo":null}`), &m); err != nil || m.Player != nil || m.Tempo != nil {
		t.Fatalf("master none: %+v %v", m, err)
	}
	var tr SseTrack
	if err := json.Unmarshal([]byte(`{"player":3,"track":null}`), &tr); err != nil || tr.Player != 3 || tr.Track != nil {
		t.Fatalf("track null: %+v %v", tr, err)
	}
	var d SseDevice
	if err := json.Unmarshal([]byte(`{"action":"connected","device":{"number":3,"name":"DJM-900","address":"10.0.0.3","kind":"mixer"}}`), &d); err != nil || d.Action != "connected" || d.Device.Kind != "mixer" || d.Device.Number != 3 {
		t.Fatalf("device: %+v %v", d, err)
	}
	var h Health
	if err := json.Unmarshal([]byte(`{"online":true,"deviceNumber":null,"deviceCount":2,"serverTime":1792905297307,"version":"1.0.0"}`), &h); err != nil || !h.Online || h.DeviceNumber != nil || h.DeviceCount != 2 || h.Version != "1.0.0" {
		t.Fatalf("health: %+v %v", h, err)
	}
	var s SseSnapshot
	if err := json.Unmarshal([]byte(`{"players":[`+playerJSON+`,`+emptyPlayerJSON+`],"master":{"player":1,"tempo":128},"devices":[],"online":true,"serverTime":5}`), &s); err != nil || len(s.Players) != 2 || s.Master.Player == nil || *s.Master.Player != 1 || !s.Online {
		t.Fatalf("snapshot: %+v %v", s, err)
	}
	var w WaveformPreview
	if err := json.Unmarshal([]byte(`{"style":"blue","segments":3,"maxHeight":31,"heights":[1,2,3],"colors":["#000000","#111111","#222222"]}`), &w); err != nil || w.MaxHeight != 31 || len(w.Heights) != 3 || w.Heights[2] != 3 || w.Segments != 3 {
		t.Fatalf("waveform: %+v %v", w, err)
	}
}

func TestDecodeEventPlayerIsFlat(t *testing.T) {
	ev, err := DecodeEvent("player", []byte(playerJSON))
	if err != nil {
		t.Fatal(err)
	}
	if ev.Kind != EventPlayer || ev.Player == nil || ev.Player.Number != 1 || ev.Player.Track == nil {
		t.Fatalf("player event: %+v", ev)
	}
}

func TestDecodeEventKinds(t *testing.T) {
	cases := []struct {
		name, data string
		kind       EventKind
		ok         func(Event) bool
	}{
		{"beat", beatNullJSON, EventBeat, func(e Event) bool { return e.Beat != nil && e.Beat.Position == nil }},
		{"track", `{"player":2,"track":null}`, EventTrack, func(e Event) bool { return e.Track != nil && e.Track.Player == 2 && e.Track.Track == nil }},
		{"device", `{"action":"disconnected","device":{"number":2,"name":"x","address":"a","kind":"player"}}`, EventDevice, func(e Event) bool { return e.Device != nil && e.Device.Action == "disconnected" }},
		{"master", `{"player":null,"tempo":null}`, EventMaster, func(e Event) bool { return e.Master != nil && e.Master.Player == nil }},
		{"online", `{"online":false}`, EventOnline, func(e Event) bool { return e.Online != nil && !e.Online.Online }},
		{"snapshot", `{"players":[],"master":{"player":null,"tempo":null},"devices":[],"online":true,"serverTime":1}`, EventSnapshot, func(e Event) bool { return e.Snapshot != nil && e.Snapshot.Online }},
	}
	for _, c := range cases {
		ev, err := DecodeEvent(c.name, []byte(c.data))
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		if ev.Kind != c.kind || !c.ok(ev) {
			t.Fatalf("%s: %+v", c.name, ev)
		}
	}
	if _, err := DecodeEvent("player", []byte(`{"number":`)); err == nil {
		t.Fatal("malformed JSON should error")
	}
	if _, err := DecodeEvent("bogus", []byte(`{}`)); err == nil {
		t.Fatal("unknown event should error")
	}
}
