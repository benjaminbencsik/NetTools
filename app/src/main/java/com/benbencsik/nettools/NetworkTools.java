package com.benbencsik.nettools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Diagnostics independent of the Android UI. No remote backend or telemetry. */
public final class NetworkTools {
    private NetworkTools() {}

    public static String host(String input) {
        String value = input.trim();
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.isEmpty() || value.length() > 253 || value.startsWith("-") ||
                !value.matches("[a-zA-Z0-9._:%-]+")) {
            throw new IllegalArgumentException("Enter a hostname or IP address, without a URL or port.");
        }
        return value;
    }

    public static InetAddress[] resolve(String input) throws Exception {
        String target = host(input);
        FutureTask<InetAddress[]> task = new FutureTask<>(() -> InetAddress.getAllByName(target));
        Thread resolver = new Thread(task, "nettools-dns");
        resolver.setDaemon(true);
        resolver.start();
        try {
            return task.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("DNS lookup timed out after 8 seconds.");
        } catch (ExecutionException e) {
            throw new IOException("Could not resolve " + target + ". Check the hostname and connection.", e.getCause());
        } finally {
            task.cancel(true);
        }
    }

    public static String dns(String input) throws Exception {
        String target = host(input);
        long start = System.nanoTime();
        InetAddress[] addresses = resolve(target);
        StringBuilder out = new StringBuilder("HOST  " + target + "\n\n");
        for (InetAddress a : addresses) {
            out.append(a instanceof Inet6Address ? "AAAA  " : "A     ").append(a.getHostAddress()).append('\n');
        }
        out.append(String.format(Locale.US, "\nResolved in %.0f ms\nResolver: your device's configured DNS\n\nThis lookup returns IP addresses (A/AAAA), not MX or TXT records.",
                (System.nanoTime() - start) / 1_000_000.0));
        return out.toString();
    }

    public static String ping(String input) throws Exception {
        InetAddress address = resolve(input)[0];
        boolean ipv6 = address instanceof Inet6Address;
        List<String> command = new ArrayList<>();
        command.add("/system/bin/ping");
        if (ipv6) command.add("-6");
        Collections.addAll(command, "-n", "-c", "4", "-W", "2", "-w", "10", address.getHostAddress());
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("This device doesn't provide the Android ping command. Try TCP ports instead.");
        }
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) throw new IOException("Ping timed out.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int length;
            while ((length = process.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, length);
            String output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (output.trim().isEmpty()) output = "Ping returned no output.";
            return output + (process.exitValue() == 0 ? "" : "\nICMP may be blocked by the host, network, or device. A failed ping does not prove a host is offline.");
        } finally {
            process.destroyForcibly();
        }
    }

    public static List<Integer> ports(String input) {
        TreeSet<Integer> result = new TreeSet<>();
        if (input.trim().isEmpty()) throw new IllegalArgumentException("Enter ports, such as 22,80,443 or 8000-8010.");
        for (String token : input.split(",", -1)) {
            String[] range = token.trim().split("-", -1);
            if (range.length < 1 || range.length > 2) throw new IllegalArgumentException("Use commas or a small port range.");
            int first = number(range[0].trim(), 1, 65535, "Port");
            int last = range.length == 2 ? number(range[1].trim(), 1, 65535, "Port") : first;
            if (last < first || last - first >= 128) throw new IllegalArgumentException("Use up to 128 ports per check.");
            for (int port = first; port <= last; port++) result.add(port);
            if (result.size() > 128) throw new IllegalArgumentException("Use up to 128 ports per check.");
        }
        return new ArrayList<>(result);
    }

    public static String connect(InetAddress address, int port, int timeout) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeout);
            return "OPEN";
        } catch (ConnectException e) {
            return "REFUSED";
        } catch (SocketTimeoutException e) {
            return "TIMEOUT";
        } catch (IOException e) {
            return "UNREACHABLE";
        }
    }

    public static String checkPorts(String input, String portInput) throws Exception {
        List<Integer> list = ports(portInput);
        InetAddress[] addresses = resolve(input);
        // One host, bounded concurrency, no subnet discovery or background scanning.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, list.size()));
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int port : list) {
                futures.add(pool.submit(() -> {
                    String state = "UNREACHABLE";
                    // Bound dual-stack retries as well as connection timeouts.
                    for (int i = 0; i < Math.min(2, addresses.length); i++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        String attempt = connect(addresses[i], port, 1500);
                        if (attempt.equals("OPEN")) return port + "  OPEN  " + addresses[i].getHostAddress();
                        if (!state.equals("REFUSED")) state = attempt;
                    }
                    return port + "  " + state;
                }));
            }
            StringBuilder out = new StringBuilder("TCP PORT CHECK  " + host(input) + "\n\n");
            for (Future<String> future : futures) out.append(future.get()).append('\n');
            out.append("\nOPEN = connection accepted\nREFUSED = connection rejected\nTIMEOUT = no response; may be filtered\nUNREACHABLE = routing or socket error\n\nChecks at most two resolved addresses per port; other addresses may differ.");
            return out.toString();
        } finally {
            for (Future<String> f : futures) f.cancel(true);
            pool.shutdownNow();
        }
    }

    public static URL webUrl(String input) throws MalformedURLException {
        String value = input.trim();
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        URL url = new URL(value);
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https")) ||
                url.getHost().isEmpty() || url.getUserInfo() != null || url.getPort() == 0 || url.getPort() > 65535) {
            throw new IllegalArgumentException("Enter an HTTP or HTTPS URL without credentials.");
        }
        host(url.getHost());
        return url;
    }

    public static String http(String input) throws Exception {
        URL url = webUrl(input);
        StringBuilder out = new StringBuilder();
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            resolve(url.getHost());
            long start = System.nanoTime();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("User-Agent", "NetTools/0.1");
            try {
                int code = connection.getResponseCode();
                out.append(url).append("\nHTTP ").append(code).append(' ').append(connection.getResponseMessage());
                out.append(String.format(Locale.US, "  •  %.0f ms\n", (System.nanoTime() - start) / 1_000_000.0));
                for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null) out.append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue())).append('\n');
                }
                out.append('\n');
                String location = connection.getHeaderField("Location");
                if (Arrays.asList(301, 302, 303, 307, 308).contains(code) && location != null) {
                    if (redirect == 5) return out + "Stopped after 5 redirects.";
                    url = webUrl(new URL(url, location).toString());
                } else {
                    if (code == 405 || code == 501) out.append("This server does not support HEAD requests.\n");
                    return out + "Method: HEAD; response body is not downloaded.\nTLS certificate validation stays enabled for HTTPS.";
                }
            } finally {
                connection.disconnect();
            }
        }
        return out.toString();
    }

    public static String tls(String input) throws Exception {
        String target = host(input);
        InetAddress[] addresses = resolve(target);
        Exception last = null;
        for (InetAddress address : Arrays.copyOf(addresses, Math.min(2, addresses.length))) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try (Socket plain = new Socket()) {
                plain.connect(new InetSocketAddress(address, 443), 4000);
                plain.setSoTimeout(5000);
                try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, target, 443, true)) {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                    socket.startHandshake();
                    SSLSession session = socket.getSession();
                    X509Certificate cert = (X509Certificate) session.getPeerCertificates()[0];
                    long days = (cert.getNotAfter().getTime() - System.currentTimeMillis()) / 86_400_000;
                    return "TLS CERTIFICATE  " + target + "\n\nHostname & trust validation: passed\nProtocol: " + session.getProtocol() +
                            "\nCipher: " + session.getCipherSuite() + "\n\nSubject: " + cert.getSubjectX500Principal().getName() +
                            "\nIssuer: " + cert.getIssuerX500Principal().getName() + "\nValid from: " + cert.getNotBefore().toInstant() +
                            "\nExpires: " + cert.getNotAfter().toInstant() + "\nDays remaining: " + days + "\n\nPort: 443";
                }
            } catch (Exception e) { last = e; }
        }
        throw new IOException("TLS check failed: " + (last == null ? "No reachable address" : last.getMessage()), last);
    }

    public static int number(String text, int min, int max, String label) {
        try {
            if (!text.matches("[0-9]+")) throw new NumberFormatException();
            int n = Integer.parseInt(text);
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
        }
    }

    private static long ipv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Enter an IPv4 address, such as 192.168.1.10.");
        long ip = 0;
        for (String octet : octets) ip = (ip << 8) | number(octet, 0, 255, "IPv4 octet");
        return ip;
    }

    private static String ip(long value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
    }

    public static String subnet(String input) {
        String[] parts = input.trim().split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Enter IPv4/CIDR, such as 192.168.1.10/24.");
        long address = ipv4(parts[0]);
        int prefix = number(parts[1], 0, 32, "CIDR prefix");
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = address & mask;
        long broadcast = network | (~mask & 0xffffffffL);
        long count = 1L << (32 - prefix);
        boolean special = prefix >= 31;
        long first = special ? network : network + 1;
        long last = special ? broadcast : broadcast - 1;
        return "IPV4 SUBNET  " + input.trim() + "\n\nNetwork: " + ip(network) + "/" + prefix +
                "\nSubnet mask: " + ip(mask) + "\nWildcard mask: " + ip(~mask & 0xffffffffL) +
                "\nBroadcast: " + (special ? "Not applicable for /31 or /32" : ip(broadcast)) +
                "\nFirst host: " + ip(first) + "\nLast host: " + ip(last) +
                "\nTotal addresses: " + count + "\nUsable hosts: " + (special ? count : count - 2) +
                (prefix == 31 ? "\n\n/31 uses both addresses on a point-to-point link." : prefix == 32 ? "\n\n/32 represents a single host." : "") +
                "\n\nCalculated on device; no network request.";
    }
}
