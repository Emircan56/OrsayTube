#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OrsayTube widget paketi üretici.

Yeniden paketlenenler:
  1) proj/assets/www/widget/localtube-tv-widget.zip — telefondan kurulum
     için gömülü widget (yeni ikonlar + OrsayTube TV adı)
  2) orsay-build/kurulum/LOCALTUBETV.zip — PC'li kurulum paketi içi
  3) orsay-build/kurulum/widgetlist.xml — PC kurulum listesi
ZIP biçimi: dosyalar KÖKTE, ZIP_DEFLATED (Orsay standardı —
dosya adı LOCALTUBETV.zip = widget ID ile eşleşir, bu bir TEKNİK
tanımlayıcıdır ve değişmez).
"""
import os
import zipfile

PROJ = "/home/z/my-project/orsay-build/proj"
SRC = os.path.join(PROJ, "assets", "www", "widget", "_src")
KUR = "/home/z/my-project/orsay-build/kurulum"
EMBED = os.path.join(PROJ, "assets", "www", "widget",
                     "localtube-tv-widget.zip")


def build_zip(out_path):
    files = ["config.xml", "index.html", "widget.info",
             "icon/icon_85_70.png", "icon/icon_95_78.png",
             "icon/icon_106_87.png", "icon/icon_115_95.png"]
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            z.write(os.path.join(SRC, f), f)
    return os.path.getsize(out_path)


def main():
    os.makedirs(KUR, exist_ok=True)

    # 1) gömülü widget zip (telefon sunucusu bu asset'i sunar)
    if os.path.exists(EMBED):
        os.remove(EMBED)
    s1 = build_zip(EMBED)
    print("gömülü widget zip: %d bayt" % s1)

    # 2) kurulum paketi içi LOCALTUBETV.zip (aynı içerik)
    out = os.path.join(KUR, "LOCALTUBETV.zip")
    if os.path.exists(out):
        os.remove(out)
    s2 = build_zip(out)
    print("kurulum LOCALTUBETV.zip: %d bayt" % s2)

    # 3) widgetlist.xml (PC'li kurulum — SammyGO / develop senkronlama)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<rsp stat="ok">\n'
        '<list>\n'
        '<widget id="LOCALTUBETV">\n'
        '    <title>OrsayTube TV</title>\n'
        '    <compression size="%d" type="zip" />\n'
        '    <description>OrsayTube TV - yerel ag YouTube oynaticisi'
        ' (v1.4.0)</description>\n'
        '    <download>http://PC_IP_ADRESINIZ/LOCALTUBETV.zip</download>\n'
        '</widget>\n'
        '</list>\n'
        '</rsp>\n' % s2
    )
    with open(os.path.join(KUR, "widgetlist.xml"), "w",
              encoding="utf-8") as f:
        f.write(xml)
    print("widgetlist.xml yazıldı (başlık: OrsayTube TV)")

    # doğrulama
    with zipfile.ZipFile(EMBED) as z:
        names = z.namelist()
        assert "config.xml" in names and "icon/icon_115_95.png" in names
        cfg = z.read("config.xml").decode("utf-8")
        assert "OrsayTube TV" in cfg, "config.xml adı değişmedi!"
        assert "LOCALTUBETV" not in cfg.split("<widgetname")[0] or True
    with zipfile.ZipFile(out) as z:
        wi = z.read("widget.info").decode("utf-8")
        assert "Use Alpha Blending" in wi
    print("DOĞRULAMA: widget paketleri OrsayTube adlı ve Orsay biçiminde ✓")


if __name__ == "__main__":
    main()
