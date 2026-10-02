
package com.localtube.mobil;
public class TestUpstream {
    public static void main(String[] a) throws Exception {
        Resolver.Up up = new Resolver().upstream("dQw4w9WgXcQ", "18");
        System.out.println("UP_URL=" + (up == null ? "NULL" : up.url));
        System.out.println("UP_KIND=" + (up == null ? "NULL" : up.uaKind));
    }
}
