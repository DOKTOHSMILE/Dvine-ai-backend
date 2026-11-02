package ai.dvine.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which outside hosts the sandbox and the web fetch may reach. Hosts are
 * matched against an allow-list ("example.org", "*.example.org", or "*" for
 * any public host). Whatever the list says, private, loopback, link-local,
 * multicast and carrier-grade NAT addresses are refused (no reaching the
 * server's own network), and only ports 80 and 443 are allowed.
 */
public final class EgressPolicy {
    private final List<String> hosts = new ArrayList<>();
    private final boolean allowPrivate;
    private final Set<Integer> ports;

    public EgressPolicy(List<String> allow, boolean allowPrivate, Set<Integer> ports) {
        for (String h : allow) {
            String t = h.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                hosts.add(t);
            }
        }
        this.allowPrivate = allowPrivate;
        this.ports = ports;
    }

    public boolean hostAllowed(String host, int port) {
        if (host == null || !ports.contains(port)) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        if (h.isEmpty() || h.length() > 253) {
            return false;
        }
        for (String rule : hosts) {
            if (rule.equals("*") || rule.equals(h) || rule.startsWith("*.") && (h.endsWith(rule.substring(1))
                    || h.equals(rule.substring(2)))) {
                return true;
            }
        }
        return false;
    }

    public boolean addressAllowed(InetAddress a) {
        if (allowPrivate) {
            return true;
        }
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int x = b[0] & 0xff, y = b[1] & 0xff;
            return !(x == 0 || x == 100 && y >= 64 && y < 128 || x == 192 && y == 0 && (b[2] & 0xff) == 0
                    || x == 198 && (y == 18 || y == 19) || x >= 224);
        }
        if (a instanceof Inet6Address) {
            int first = b[0] & 0xff;
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean mapped = isMapped(b);
            if (mapped) {
                try {
                    return addressAllowed(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
            return !uniqueLocal;
        }
        return false;
    }

    private static boolean isMapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    public List<String> hosts() {
        return List.copyOf(hosts);
    }
}
