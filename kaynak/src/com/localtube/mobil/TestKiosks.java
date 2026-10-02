package com.localtube.mobil;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;

/** v4.4: tüm NewPipe kiosk'larını dene — hangisi canlı? */
public class TestKiosks {
    public static void main(String[] args) throws Exception {
        NewPipe.init(new Npe.HttpDownloader(), new Localization("tr", "TR"));
        StreamingService s = NewPipe.getService(0);
        KioskList kl = s.getKioskList();
        kl.forceContentCountry(new ContentCountry("TR"));
        System.out.println("kiosk listesi: " + kl.getAvailableKiosks()
                + " | varsayilan: " + kl.getDefaultKioskId());
        for (String id : kl.getAvailableKiosks()) {
            long t0 = System.currentTimeMillis();
            try {
                ListExtractor<? extends InfoItem> ex = kl.getExtractorById(id, null);
                ex.fetchPage();
                List<? extends InfoItem> items = ex.getInitialPage().getItems();
                int n = items.size();
                String first = n > 0 && items.get(0) instanceof StreamInfoItem
                        ? ((StreamInfoItem) items.get(0)).getName() : "-";
                System.out.println("KIOSK " + id + ": OK " + n + " oge, "
                        + (System.currentTimeMillis() - t0) + "ms | ilk: "
                        + first.substring(0, Math.min(50, first.length())));
            } catch (Throwable t) {
                System.out.println("KIOSK " + id + ": HATA "
                        + t.getClass().getSimpleName() + " "
                        + String.valueOf(t.getMessage()).substring(0,
                                Math.min(110, String.valueOf(t.getMessage()).length()))
                        + " (" + (System.currentTimeMillis() - t0) + "ms)");
            }
        }
    }
}
