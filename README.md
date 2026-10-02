# OrsayTube

**Telefonunuzdaki yerel sunucu üzerinden YouTube oynatan mobil + TV uygulaması paketi.**
Samsung Orsay (2011–2015, D/E/F/H serisi) Smart TV'ler, Android TV kutuları ve telefon için.

> OrsayTube v4.6, önceki sürümün **aynı kodu** ile derlenmiştir: logo yenilenmiş,
> uygulamanın adı OrsayTube olmuştur.
> İşlevsel hiçbir değişiklik yoktur; aynı imzayla derlendiği için eski sürümün
> **üzerine kurulur** (versionCode 48), verileriniz korunur.

```
   ┌────────────────────┐      yerel ağ (MP4)     ┌───────────────────────────┐
   │  TV                │ ◄──────────────────────  │  Telefon (OrsayTube Mobil)│
   │  · Samsung widget  │                          │  · gömülü HTTP sunucusu  │
   │  · Android TV APK  │                          │  · NewPipe motoru        │
   │  · TV tarayıcısı   │                          │  · 720p MP4 zenginleştirme│
   └────────────────────┘                          └───────────────────────────┘
```

## İndirme (Releases)

| Dosya | Ne işe yarar |
|---|---|
| `OrsayTube-Mobil-v4.6.apk` | Telefona kurulacak ana uygulama (sunucu) |
| `OrsayTube-TV-1.3.apk` | Android TV kutuları için istemci |
| `OrsayTube-TV-Kurulum-Paketi-v1.4.zip` | Samsung Orsay widget kurulum paketi (PC'li yol) |
| `OrsayTube-Mobil-v4.6-Tam-Paket.zip` | Her şey (kaynak kod + dokümanlar + APK'lar) |

→ [Releases sayfasından indirin](../../releases)

## Hızlı kurulum

1. **Telefon:** `OrsayTube-Mobil-v4.6.apk`'yı kurun (eski sürüm kuruluysa üstüne kurulur).
2. **Samsung Orsay TV (2012–2015):** iki yol —
   - **Telefondan:** TV'de Smart Hub → `develop` hesabı → Sunucu IP = telefon IP'niz →
     "Kullanıcı uygulamalarını senkronla". (Telefon sunucusu widgetlist.xml + widget zip'ini
     kendisi sunar.)
   - **PC'den:** `OrsayTube-TV-Kurulum-Paketi-v1.4.zip`'i açın, içindeki
     `widgetlist-olustur.bat`'ı çalıştırıp SammyGO benzeri kurucuyla senkronlayın.
3. **Android TV:** `OrsayTube-TV-1.3.apk`'yı kurun; uygulama telefonda açık sunucuyu bulur.

Ayrıntılar: `KURULUM-OKU.txt` (paketin içinde).

## Özellikler (v4.6)

- **720p MP4 TV'de:** YouTube'un imzalı WEB_EMBEDDED akışları sunucuda çözülür (Rhino JS
  motoru gömülü) → TV tarayıcısında 720p'ye kadar MP4.
- **Kumanda dostu 10-foot TV arayüzü:** eski WebKit güvenli kaydırma/odaklama.
- **Widget (Orsay) + Android TV + TV tarayıcısı + telefon PWA** — dört istemci, tek sunucu.
- **Tüm trafik yerel ağda:** YouTube'a istekler telefonun kendi IP'sinden gider.

## Depo yapısı

```
OrsayTube/
├── kaynak/          Mobil APK kaynağı (Java + web arayüzü + gömülü TV APK/widget)
│   ├── src/com/localtube/mobil/   LocalServer, Resolver, Npe (motor köprüsü), ...
│   ├── assets/www/                TV arayüzü (tv.js/tv.css) + widget paketi
│   ├── res/ mipmap-*/             launcher ikonları (noktasız yeni logo)
│   ├── libs/                      gömülü motor (NewPipe Extractor + Rhino + ...)
│   └── keystore/                  imzalama anahtarı (APK güncellemeleri için)
├── samsung-widget/  Orsay widget kaynağı (config.xml + ikonlar + arayüz)
├── motor-yamasi/    YoutubeStreamExtractor dayanıklılık yaması
├── derle/           derleme betikleri (aapt2 + javac + d8 + apksigner)
├── KURULUM-OKU.txt  kurulum ve sorun giderme rehberi
└── LICENSE-NOTICE.txt
```

## Derleme

Gerekenler: JDK 21, Android build-tools 34+, platform-33.
`derle/build_apk_orsaytube.sh` mobil APK'yı, `derle/build_apk_tv_orsaytube.sh` TV APK'yı,
`derle/build_widget.py` widget zip'ini üretir. İmza: `kaynak/keystore/localtube.keystore`.

> **Not — teknik tanımlayıcılar:** Paket adı (`com.localtube.mobil`), Samsung widget
> kimliği (`LOCALTUBETV`) ve TV indirme uç adları gibi bazı iç tanımlayıcılar, eski
> kurulumların **üzerine güncelleme** yapılabilmesi ve TV'deki mevcut kurulumlarla
> uyum için bilinçli olarak korunmuştur. Bunlar kullanıcı arayüzünde görünmez.

## Krediler

- [diekaiju/localtube](https://github.com/diekaiju/localtube) (GPLv3) — mimari ilham
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPLv3) — gömülü motor
- Bağımlılıklar: jsoup, Rhino, nanojson, protobuf-javalite

---
OrsayTube © 2026 Emircan56 — GPLv3 ruhuyla, yerel ağda.
