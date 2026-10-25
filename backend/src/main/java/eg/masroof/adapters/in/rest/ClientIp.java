package eg.masroof.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The client address used as a rate-limit key. {@code X-Forwarded-For} is honoured only when the
 * socket peer is inside {@code masroof.trusted-proxies} (env {@code MASROOF_TRUSTED_PROXIES}, a
 * comma separated list of addresses or CIDRs; default Docker's {@code 172.16.0.0/12} pool, where
 * Caddy lives); then the last entry, the one Caddy appended, is used. Anyone else's header is
 * ignored and the socket address is used. Under profile {@code prod} an entry broader than /16
 * (IPv4) or /64 (IPv6) is refused at startup.
 */
@Component
final class ClientIp {

  static final String DEFAULT_PROXIES = "172.16.0.0/12";

  private static final class Cidr {
    private final byte[] network;
    private final int bits;

    Cidr(byte[] network, int bits) {
      this.network = network.clone();
      this.bits = bits;
    }

    byte[] network() {
      return network;
    }

    int bits() {
      return bits;
    }
  }

  private final List<Cidr> trusted = new ArrayList<>();

  static final int PROD_MAX_V4_SPAN = 16;
  static final int PROD_MAX_V6_SPAN = 64;

  @Autowired
  ClientIp(@Value("${masroof.trusted-proxies:}") String proxies, Environment environment) {
    this(proxies, environment.acceptsProfiles(Profiles.of("prod")));
  }

  ClientIp(String proxies) {
    this(proxies, false);
  }

  ClientIp(String proxies, boolean prod) {
    String list = proxies.isBlank() ? DEFAULT_PROXIES : proxies;
    for (String entry : list.split(",", -1)) {
      if (!entry.isBlank()) {
        Cidr cidr = parse(entry.strip());
        int limit = cidr.network().length == 4 ? PROD_MAX_V4_SPAN : PROD_MAX_V6_SPAN;
        if (prod && cidr.bits() < limit) {
          throw new IllegalStateException(
              "masroof.trusted-proxies (MASROOF_TRUSTED_PROXIES) has an entry broader than /"
                  + limit
                  + "; under profile prod list only the proxy's own address");
        }
        trusted.add(cidr);
      }
    }
  }

  String of(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank() && isTrusted(peer)) {
      String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).strip();
      if (!last.isEmpty() && last.length() <= 45) {
        return last;
      }
    }
    return peer;
  }

  /** True for the container's own loopback health probes (socket peer only, no proxy header). */
  static boolean isLoopbackProbe(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    return request.getHeader("X-Forwarded-For") == null
        && ("127.0.0.1".equals(peer) || "::1".equals(peer) || "0:0:0:0:0:0:0:1".equals(peer));
  }

  private boolean isTrusted(String address) {
    byte[] ip = literal(address);
    if (ip == null) {
      return false;
    }
    for (Cidr c : trusted) {
      if (c.network().length == ip.length && matches(c, ip)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(Cidr c, byte[] ip) {
    int full = c.bits() / 8;
    for (int i = 0; i < full; i++) {
      if (c.network()[i] != ip[i]) {
        return false;
      }
    }
    int rest = c.bits() % 8;
    if (rest == 0) {
      return true;
    }
    int mask = 0xFF << (8 - rest) & 0xFF;
    return (c.network()[full] & mask) == (ip[full] & mask);
  }

  private static Cidr parse(String entry) {
    int slash = entry.indexOf('/');
    String addr = slash < 0 ? entry : entry.substring(0, slash);
    byte[] network = literal(addr);
    if (network == null) {
      throw new IllegalStateException("masroof.trusted-proxies has an invalid address");
    }
    int bits = network.length * 8;
    if (slash >= 0) {
      try {
        bits = Integer.parseInt(entry.substring(slash + 1));
      } catch (NumberFormatException e) {
        throw new IllegalStateException("masroof.trusted-proxies has an invalid prefix", e);
      }
      if (bits < 0 || bits > network.length * 8) {
        throw new IllegalStateException("masroof.trusted-proxies has an invalid prefix");
      }
    }
    return new Cidr(network, bits);
  }

  /** Bytes of an IP literal; never resolves names. */
  private static byte @Nullable [] literal(String address) {
    if (!address.matches("[0-9a-fA-F:.]+")) {
      return null;
    }
    try {
      return InetAddress.getByName(address).getAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
