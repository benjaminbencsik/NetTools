package com.benbencsik.nettools;

import java.net.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;

/** No third-party test framework; deterministic calculations and loopback sockets. */
public final class NetworkToolsTest {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void contains(String result, String text) { expect(result.contains(text), "Expected: " + text + "\n" + result); }
    private static void rejected(Runnable task) {
        boolean failed=false;
        try { task.run(); } catch (IllegalArgumentException e) { failed=true; }
        expect(failed,"Invalid input must be rejected");
    }
    public static void main(String[] args) throws Exception {
        String normal=NetworkTools.subnet("192.168.1.10/24");
        contains(normal,"Network: 192.168.1.0/24"); contains(normal,"Broadcast: 192.168.1.255");
        contains(normal,"First host: 192.168.1.1"); contains(normal,"Last host: 192.168.1.254"); contains(normal,"Usable hosts: 254");
        contains(NetworkTools.subnet("10.0.0.1/31"),"Usable hosts: 2");
        contains(NetworkTools.subnet("10.0.0.1/31"),"First host: 10.0.0.0");
        contains(NetworkTools.subnet("10.0.0.1/32"),"First host: 10.0.0.1");
        contains(NetworkTools.subnet("10.0.0.1/32"),"Usable hosts: 1");
        contains(NetworkTools.subnet("1.2.3.4/0"),"Total addresses: 4294967296");
        contains(NetworkTools.subnet("1.2.3.4/0"),"Usable hosts: 4294967294");
        rejected(()->NetworkTools.subnet("999.1.1.1/24")); rejected(()->NetworkTools.subnet("1.2.3.4/33"));
        rejected(()->NetworkTools.subnet("1.2.3.4/-1")); rejected(()->NetworkTools.subnet("1.2.3/24"));
        expect(NetworkTools.ports("22,80,443,80,8000-8002").equals(Arrays.asList(22,80,443,8000,8001,8002)),"Deduplicate and expand ports");
        expect(NetworkTools.ports("1-128").size()==128,"128 port limit inclusive");
        rejected(()->NetworkTools.ports("1-129")); rejected(()->NetworkTools.ports("65536"));
        rejected(()->NetworkTools.ports("443-80")); rejected(()->NetworkTools.ports("80,")); rejected(()->NetworkTools.ports("0"));
        expect(NetworkTools.host("[::1]").equals("::1"),"IPv6 bracket normalization");
        rejected(()->NetworkTools.host("-c 100")); rejected(()->NetworkTools.host("example.com;id")); rejected(()->NetworkTools.host("https://example.com"));
        expect(NetworkTools.webUrl("example.com").toString().equals("https://example.com"),"HTTPS default");
        boolean credentialsRejected=false;
        try { NetworkTools.webUrl("https://user:password@example.com"); } catch (IllegalArgumentException e) { credentialsRejected=true; }
        expect(credentialsRejected,"URL credentials rejected");
        boolean protocolRejected=false;
        try { NetworkTools.webUrl("ftp://example.com/file"); } catch (IllegalArgumentException e) { protocolRejected=true; }
        expect(protocolRejected,"Non-HTTP schemes rejected");
        InetAddress loopback=InetAddress.getByName("127.0.0.1");
        contains(NetworkTools.dns("127.0.0.1"),"127.0.0.1");
        try (ServerSocket listener=new ServerSocket(0,10,loopback)) {
            expect(NetworkTools.connect(loopback,listener.getLocalPort(),500).equals("OPEN"),"Live loopback socket is open");
            contains(NetworkTools.checkPorts("127.0.0.1",String.valueOf(listener.getLocalPort())),"OPEN");
        }
        int closedPort;
        try (ServerSocket temporary=new ServerSocket(0,10,loopback)) { closedPort=temporary.getLocalPort(); }
        expect(NetworkTools.connect(loopback,closedPort,500).equals("REFUSED"),"Closed loopback socket is refused");
        HttpServer server=HttpServer.create(new InetSocketAddress(loopback,0),0);
        server.createContext("/ready",exchange -> {
            exchange.getResponseHeaders().add("X-NetTools-Test","verified");
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("HEAD") ? 204 : 405,-1);
            exchange.close();
        });
        server.createContext("/redirect",exchange -> {
            exchange.getResponseHeaders().add("Location","/ready");
            exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        server.start();
        try {
            String http=NetworkTools.http("http://127.0.0.1:"+server.getAddress().getPort()+"/redirect");
            contains(http,"HTTP 302"); contains(http,"HTTP 204"); contains(http.toLowerCase(Locale.US),"x-nettools-test: verified");
            contains(http,"Method: HEAD");
        } finally { server.stop(0); }
        System.out.println("PASS: " + checks + " network and input checks");
    }
}
