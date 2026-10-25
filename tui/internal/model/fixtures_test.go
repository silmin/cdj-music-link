package model

const playerJSON = `{"number":1,"name":"fake-cdj-1","address":"172.21.0.2","updatedAt":1792905297307,"playState":"playing","isPlaying":true,"isPaused":false,"isCued":false,"isLooping":false,"isOnAir":null,"isSynced":false,"isTempoMaster":false,"trackBpm":128,"pitch":0,"tempo":128,"beatNumber":24,"beatWithinBar":4,"position":{"ms":7471,"remainingMs":348509,"updatedAt":1792905297010,"definitive":true},"track":{"id":308,"sourcePlayer":4,"slot":"usb","type":"rekordbox","title":"T","artist":null,"album":null,"key":"Dbm","genre":null,"label":null,"remixer":null,"originalArtist":null,"comment":null,"durationSec":355,"startingBpm":178,"rating":0,"year":null,"dateAdded":"2023-10-16","color":{"name":"No Color","hex":"#000000"},"artworkAvailable":true,"waveformAvailable":true,"beatgridAvailable":true}}`

const emptyPlayerJSON = `{"number":2,"name":"fake-cdj-2","address":"172.21.0.3","updatedAt":1792905297307,"playState":"empty","isPlaying":false,"isPaused":false,"isCued":false,"isLooping":false,"isOnAir":true,"isSynced":false,"isTempoMaster":false,"trackBpm":null,"pitch":0,"tempo":null,"beatNumber":null,"beatWithinBar":null,"position":null,"track":null}`

const beatJSON = `{"player":1,"beatNumber":24,"beatWithinBar":4,"tempo":128,"nextBeatInMs":468,"nextBarInMs":468,"isTempoMaster":false,"position":{"ms":7471},"waveform":{"height":12,"color":"#ff0000"},"receivedAt":1792905297307}`

const beatNullJSON = `{"player":1,"beatNumber":null,"beatWithinBar":null,"tempo":null,"nextBeatInMs":null,"nextBarInMs":null,"isTempoMaster":false,"position":null,"waveform":null,"receivedAt":1792905297307}`
