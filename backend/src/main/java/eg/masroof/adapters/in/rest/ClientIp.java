package eg.masroof.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The client address used as a rate-limit key. {@code X-Forwarded-For} is honoured only when the
 * socket peer is on a private or loopback network (the Caddy container); then the last entry, the
 * one Caddy appended, is used. Anyone else's header is ignored.
 */
final class ClientIp {

  private ClientIp() {}

  static String of(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(peer)) {
      String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).strip();
      if (!last.isEmpty() && last.length() <= 45) {
        return last;
      }
    }
    return peer;
  }

  private static boolean isTrustedProxy(String address) {
    try {
      if (!address.matches("[0-9a-fA-F:.]+")) {
        return false; // never resolve names
      }
      InetAddress a = InetAddress.getByName(address);
      return a.isLoopbackAddress()
          || a.isSiteLocalAddress()
          || a.isLinkLocalAddress()
          || address.toLowerCase(java.util.Locale.ROOT).startsWith("fd");
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
