# Hozin City

Mini open-world 3D ala GTA San Andreas — dibuat dengan libGDX (Java, native
murni, tanpa WebView, tanpa internet, tanpa iklan). Satu distrik kota: jalan
kaki, nyetir mobil, pejalan kaki, koin, dan minimap.

> Catatan jujur: GTA SA asli (map raksasa, cerita, misi, polisi, radio) tidak
> mungkin dalam satu build. v1.0 ini mini open-world: satu distrik kota,
> jalan kaki + nyetir + pejalan kaki + koin + minimap.

## Cara main

- **Joystick kiri** — gerak (jalan/lari). Saat nyetir: kiri-kanan = setir.
- **Drag kanan** — putar kamera. **Pinch** — zoom kamera.
- **LOMPAT** — lompat (saat jalan kaki).
- **MASUK MOBIL** — muncul saat dekat mobil (< 3,5 unit). Saat nyetir:
  **GAS** / **REM** (tahan REM saat diam = mundur), **KELUAR** untuk turun.
- **Koin emas (10)** — lewati untuk mengumpulkan. 10/10 = "KOTA SELESAI!".
- **Minimap** — pojok kanan atas: jalan, titik pemain (putih), mobil (biru),
  koin (emas).
- **SUARA ON/OFF** — hum mesin, blip koin, jingle. **RESET** — ulang koin.

## Isi v1.0

- Kota 240×240: jalan grid tiap 40 unit, trotoar, ~24 gedung bertingkat
  (tekstur fasad berjendela), lampu jalan, pohon, satu blok taman.
- Siang terang ala GTA SA: langit biru muda, matahari directional hangat +
  ambient terang, cincin haze di tepi kota (fade jarak jauh).
- Pemain humanoid prosedural dengan animasi jalan (ayunan lengan/kaki),
  lompat + gravitasi.
- 4 mobil warna beda (fisika arcade buatan sendiri: akselerasi, rem, mundur,
  setir melemah saat cepat, tabrakan AABB dengan gedung).
- 6 pejalan kaki NPC yang jalan di trotoar dan belok acak di persimpangan.
- Blob shadow murah di bawah pemain/mobil/NPC (tanpa shadow map).

## Grafik & optimasi

- Geometri statis digabung jadi sedikit mesh besar: tanah+jalan+trotoar (3
  part), cincin haze (1 part), gedung (2 part), pohon (2 part), lampu (2
  part) = **10 draw call statis**.
- Total draw call saat semua objek terlihat: **~73** (target ≤ 80).
- Satu directional light + ambient saja, tanpa lampu per-pixel tambahan.
- Frustum culling manual untuk objek dinamis; tanpa alokasi objek baru
  tiap frame di loop render; fisika fixed timestep 60 Hz; FPS cap 60;
  tekstur 512 dengan mipmap + filter trilinear; MSAA 2x.
- APK ~1,8 MB (target < 10 MB).

## Build

Build otomatis di GitHub Actions (push ke `main` → APK di GitHub Release).
Lokal (diagnostik saja): `./gradlew :android:assembleDebug` (JDK 17).

## Aset

Lihat `ASSET_SOURCES.md` — semua aset dibuat sendiri; tidak ada aset GTA SA
asli maupun aset berhak cipta lain.
