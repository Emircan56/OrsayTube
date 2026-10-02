#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OrsayTube — noktasız temiz logo üretici.

v4.6 ikonunun aynısı, SADECE noktalar kaldırıldı:
  - üçgen köşelerindeki kırmızı daireler (nokta görünümü)  → KALDIRILDI
  - sağ üstteki beyaz nokta                                → KALDIRILDI
Geometri ve renkler birebir korundu (aynı uygulama hissi):
  zemin #0D0D0D squircle, üçgen #FF0033 (merkez hafif sağda).

Ürettiği dosyalar:
  1) mipmap ikonları:  48/72/96/144/192  (APK launcher)
  2) icon-512.png      (web/PWA/favicon)
  3) widget ikonları:  85x70, 95x78, 106x87, 115x95 (Samsung Orsay)
  4) TV banner:        320x180 (Android TV leanback)
"""
from PIL import Image, ImageDraw, ImageFont
import os

DARK = (13, 13, 13, 255)          # #0D0D0D  — eskisiyle aynı
RED = (255, 0, 51, 255)           # #FF0033  — eskisiyle aynı
BANNER_RED = (230, 33, 23)        # banner/rosette kırmızısı — eskisiyle aynı
WHITE = (255, 255, 255, 255)
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_R = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

SS = 4  # süper örnekleme çarpanı


def draw_triangle(d, cx, cy, w, h):
    """Kırmızı oynat üçgeni — KÖŞE NOKTASI YOK, düz temiz poligon.
    Geometri eski ikonla birebir aynı (cx=0.54, w=0.52, h=0.40 oranı)."""
    x0 = cx - w * 0.45
    tri = [(x0, cy - h / 2), (x0, cy + h / 2), (cx + w * 0.55, cy)]
    d.polygon(tri, fill=RED)


def app_icon(size):
    """Kare uygulama ikonu: koyu squircle + kırmızı üçgen. NOKTASIZ."""
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(S * 0.22)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=DARK)
    draw_triangle(d, S * 0.54, S * 0.5, S * 0.52, S * 0.40)
    return img.resize((size, size), Image.LANCZOS)


def widget_icon(w, h):
    """Samsung Orsay widget ikonu: koyu yuvarlatılmış dikdörtgen + üçgen.
    Oranlar eski ikondan ölçüldü (üçgen ~%58 genişlik, ~%44 yükseklik)."""
    W, H = w * SS, h * SS
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(min(W, H) * 0.18)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=r, fill=DARK)
    draw_triangle(d, W * 0.56, H * 0.50, W * 0.58, H * 0.44)
    return img.resize((w, h), Image.LANCZOS)


def tv_banner():
    """Android TV banner 320x180 — 'OrsayTube' + kırmızı rozet.
    Eski banner'daki sağ kenar metin taşması da giderildi."""
    W, H = 320, 180
    img = Image.new("RGB", (W, H), (13, 13, 13))
    d = ImageDraw.Draw(img)

    # kırmızı rozet + beyaz üçgen (eskiyle aynı konut/boyut)
    cx, cy, r = 52, 90, 34
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=BANNER_RED)
    d.polygon([(cx - 11, cy - 16), (cx - 11, cy + 16), (cx + 18, cy)],
              fill="white")

    # başlık — 44px sığmıyorsa otomatik küçült (taşma olmasın)
    title = "OrsayTube"
    size = 44
    while size > 28:
        f1 = ImageFont.truetype(FONT_B, size)
        bb = d.textbbox((0, 0), title, font=f1)
        if 100 + (bb[2] - bb[0]) <= W - 10:
            break
        size -= 2
    d.text((100, 55), title, font=f1, fill=(244, 244, 244))

    # alt satır: kırmızı 'TV' + gri açıklama (sığan uzunlukta)
    f2 = ImageFont.truetype(FONT_R, 22)
    d.text((102, 110), "TV", font=f2, fill=BANNER_RED)
    desc = "· YouTube, telefondan"
    bb = d.textbbox((0, 0), desc, font=f2)
    dx = 150
    if dx + (bb[2] - bb[0]) > W - 8:
        dx = W - 8 - (bb[2] - bb[0])
    d.text((dx, 110), desc, font=f2, fill=(150, 150, 150))
    return img


def main():
    base = "/home/z/my-project/orsay-build"
    # 1) mipmap ikonları
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96,
             "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, sz in sizes.items():
        folder = os.path.join(base, "res", "mipmap-" + dpi)
        os.makedirs(folder, exist_ok=True)
        app_icon(sz).save(os.path.join(folder, "ic_launcher.png"))
        print("yazıldı: mipmap-%s/ic_launcher.png (%d)" % (dpi, sz))

    # 2) icon-512
    os.makedirs(os.path.join(base, "img"), exist_ok=True)
    app_icon(512).save(os.path.join(base, "img", "icon-512.png"))
    print("yazıldı: img/icon-512.png")

    # 3) widget ikonları
    wdir = os.path.join(base, "widget-icon")
    os.makedirs(wdir, exist_ok=True)
    for w, h in [(85, 70), (95, 78), (106, 87), (115, 95)]:
        widget_icon(w, h).save(
            os.path.join(wdir, "icon_%d_%d.png" % (w, h)))
        print("yazıldı: widget icon_%d_%d.png" % (w, h))

    # 4) TV banner
    bdir = os.path.join(base, "tv")
    os.makedirs(bdir, exist_ok=True)
    tv_banner().save(os.path.join(bdir, "banner.png"))
    print("yazıldı: tv/banner.png")


if __name__ == "__main__":
    main()
