# ASSET_SOURCES.md — Hozin City v1.0

Semua aset game ini dibuat sendiri atau disintesis saat runtime. **Tidak ada aset
yang diunduh dari game lain, dan tidak ada satu pun aset asli GTA San Andreas
(Rockstar) yang dipakai** — GTA SA hanya dipakai sebagai referensi desain.

## Referensi desain (bukan aset)

Layout kota Los Santos di GTA SA dipakai sebagai inspirasi visual/layout saja
(gedung tinggi di pusat kota, lebih rendah di pinggir, satu blok taman, jalan
grid + jalan lingkar). Sumber bacaan:

- Grand Theft Wiki — Los Santos (GTA SA)
- GTA Fandom — Los Santos
- diffsense / grandtheftfans — peta & distrik Los Santos

Tidak ada file (tekstur/model/suara/audio) yang diunduh dari sumber-sumber di
atas maupun dari file game Rockstar.

## Aset yang dipakai (dibuat sendiri)

| Aset | Sumber | Lisensi |
|---|---|---|
| `textures/tex-asphalt.jpg` (aspal + marka) | Generate sendiri via media pipeline | Buatan sendiri |
| `textures/tex-facade.jpg` (fasad gedung berjendela) | Generate sendiri via media pipeline | Buatan sendiri |
| `textures/tex-sidewalk.jpg` (trotoar) | Generate sendiri via media pipeline | Buatan sendiri |
| `textures/tex-grass.jpg` (rumput) | Generate sendiri via media pipeline | Buatan sendiri |
| Ikon launcher (skyline kota + mobil merah) | Generate sendiri via media pipeline | Buatan sendiri |
| Suara (hum mesin, blip koin, lompat, jingle) | Disintesis runtime dari kode (`SoundSynth.java`, pola Gear Tower) — WAV digenerate saat pertama jalan | Buatan sendiri |

## Aset internet yang diunduh

**Tidak ada.** Tidak ada model, tekstur, atau suara yang diunduh dari Kenney,
Poly Haven, OpenGameArt, atau sumber lain — semuanya prosedural/generasi
sendiri supaya APK tetap ringan dan lisensinya bersih.
