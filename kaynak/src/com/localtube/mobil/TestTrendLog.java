package com.localtube.mobil;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

/** v4.4 teşhis: trending browse isteğinin TAM içeriğini yakala */
public class TestTrendLog {
    static final class LogDownloader extends Downloader {
        final Downloader inner = new Npe.HttpDownloader();
        @Override
        public Response execute(Request request) throws java.io.IOException,
                org.schabi.newpipe.extractor.exceptions.ReCaptchaException {
            if (request.url().contains("browse")) {
                System.out.println("=== İSTEK: " + request.httpMethod() + " " + request.url());
                if (request.headers() != null) {
                    for (java.util.Map.Entry<String, java.util.List<String>> e
                            : request.headers().entrySet()) {
                        System.out.println("H: " + e.getKey() + ": " + e.getValue());
                    }
                }
                if (request.dataToSend() != null) {
                    System.out.println("BODY: " + new String(request.dataToSend(), "UTF-8"));
                }
            }
            return inner.execute(request);
        }
    }

    public static void main(String[] args) throws Exception {
        NewPipe.init(new LogDownloader(), new Localization("tr", "TR"));
        StreamingService s = NewPipe.getService(0);
        KioskList kl = s.getKioskList();
        kl.forceContentCountry(new ContentCountry("TR"));
        KioskExtractor<?> kiosk = kl.getDefaultKioskExtractor();
        kiosk.fetchPage();
        System.out.println("kiosk fetchPage OK, öğe: "
                + kiosk.getInitialPage().getItems().size());
    }
}
