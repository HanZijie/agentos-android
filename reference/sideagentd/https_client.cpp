#include "https_client.h"

#if defined(__ANDROID__)
#include <android-base/logging.h>
#else
#include <iostream>
#endif
#include <openssl/err.h>
#include <openssl/ssl.h>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <charconv>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>

namespace agentos {
namespace {

constexpr size_t kMaxResponseBytes = 8 * 1024 * 1024;

void LogHttpsError(const std::string& message) {
#if defined(__ANDROID__)
  LOG(ERROR) << message;
#else
  std::cerr << message << '\n';
#endif
}

struct Url {
  std::string host;
  std::string path;
  int port = 443;
};

bool ParseUrl(const std::string& input, Url* out) {
  constexpr char kScheme[] = "https://";
  if (input.compare(0, sizeof(kScheme) - 1, kScheme) != 0) return false;
  const size_t authority_start = sizeof(kScheme) - 1;
  const size_t path_start = input.find('/', authority_start);
  const std::string authority = input.substr(
      authority_start, path_start == std::string::npos ? std::string::npos : path_start - authority_start);
  if (authority.empty() || authority.find('@') != std::string::npos) return false;
  if (path_start == std::string::npos) out->path = "/";
  else out->path = input.substr(path_start);
  if (out->path.find('#') != std::string::npos) return false;
  if (authority.front() == '[') {
    const size_t close = authority.find(']');
    if (close == std::string::npos) return false;
    out->host = authority.substr(1, close - 1);
    if (close + 1 < authority.size()) {
      if (authority[close + 1] != ':') return false;
      const std::string port_text = authority.substr(close + 2);
      const auto parsed = std::from_chars(port_text.data(), port_text.data() + port_text.size(), out->port);
      if (parsed.ec != std::errc() || parsed.ptr != port_text.data() + port_text.size()) return false;
    }
  } else {
    const size_t colon = authority.rfind(':');
    if (colon != std::string::npos && authority.find(':') == colon) {
      out->host = authority.substr(0, colon);
      const std::string port_text = authority.substr(colon + 1);
      const auto parsed = std::from_chars(port_text.data(), port_text.data() + port_text.size(), out->port);
      if (parsed.ec != std::errc() || parsed.ptr != port_text.data() + port_text.size()) return false;
    } else {
      out->host = authority;
    }
  }
  if (out->host.empty() || out->port < 1 || out->port > 65535) return false;
  return true;
}

class Fd final {
 public:
  explicit Fd(int value = -1) : value_(value) {}
  ~Fd() { if (value_ >= 0) close(value_); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
  Fd(Fd&& other) noexcept : value_(other.value_) { other.value_ = -1; }
  Fd& operator=(Fd&& other) noexcept {
    if (this != &other) { if (value_ >= 0) close(value_); value_ = other.value_; other.value_ = -1; }
    return *this;
  }
  int get() const { return value_; }
  int release() { int value = value_; value_ = -1; return value; }
 private:
  int value_;
};

bool Cancelled(const std::atomic<bool>* cancelled) {
  return cancelled != nullptr && cancelled->load(std::memory_order_relaxed);
}

bool Wait(int fd, short events, int timeout_ms, const std::atomic<bool>* cancelled) {
  const int slice = 100;
  int remaining = std::max(1, timeout_ms);
  while (remaining > 0) {
    if (Cancelled(cancelled)) return false;
    pollfd poll_fd{fd, events, 0};
    const int result = poll(&poll_fd, 1, std::min(slice, remaining));
    if (result > 0) return (poll_fd.revents & (events | POLLERR | POLLHUP)) != 0;
    if (result < 0 && errno != EINTR) return false;
    remaining -= slice;
  }
  return false;
}

bool SetNonBlocking(int fd, bool enabled) {
  const int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0) return false;
  return fcntl(fd, F_SETFL, enabled ? flags | O_NONBLOCK : flags & ~O_NONBLOCK) == 0;
}

Fd Connect(const Url& url, int timeout_ms, const std::atomic<bool>* cancelled) {
  addrinfo hints{};
  hints.ai_socktype = SOCK_STREAM;
  hints.ai_family = AF_UNSPEC;
  addrinfo* result = nullptr;
  const std::string service = std::to_string(url.port);
  const int address_error = getaddrinfo(url.host.c_str(), service.c_str(), &hints, &result);
  if (address_error != 0) {
    LogHttpsError("HTTPS DNS lookup failed host=" + url.host +
                  " gai=" + std::to_string(address_error));
    return Fd();
  }
  Fd socket;
  for (addrinfo* item = result; item != nullptr; item = item->ai_next) {
    if (Cancelled(cancelled)) break;
    Fd candidate(::socket(item->ai_family, item->ai_socktype, item->ai_protocol));
    if (candidate.get() < 0 || !SetNonBlocking(candidate.get(), true)) continue;
    const int connected = connect(candidate.get(), item->ai_addr, item->ai_addrlen);
    if (connected == 0 || (connected < 0 && errno == EINPROGRESS &&
                           Wait(candidate.get(), POLLOUT, timeout_ms, cancelled))) {
      int error = 0;
      socklen_t size = sizeof(error);
      if (getsockopt(candidate.get(), SOL_SOCKET, SO_ERROR, &error, &size) == 0 && error == 0) {
        socket = std::move(candidate);
        break;
      }
    }
  }
  freeaddrinfo(result);
  if (socket.get() < 0) LogHttpsError("HTTPS connect failed host=" + url.host);
  return socket;
}

bool WriteTls(SSL* ssl, const std::string& request, int timeout_ms,
              const std::atomic<bool>* cancelled) {
  size_t offset = 0;
  while (offset < request.size()) {
    if (Cancelled(cancelled)) return false;
    const int written = SSL_write(ssl, request.data() + offset,
                                  static_cast<int>(std::min<size_t>(request.size() - offset, 16384)));
    if (written > 0) { offset += written; continue; }
    const int error = SSL_get_error(ssl, written);
    if (error == SSL_ERROR_WANT_READ && Wait(SSL_get_fd(ssl), POLLIN, timeout_ms, cancelled)) continue;
    if (error == SSL_ERROR_WANT_WRITE && Wait(SSL_get_fd(ssl), POLLOUT, timeout_ms, cancelled)) continue;
    return false;
  }
  return true;
}

enum class BodyState { incomplete, complete, close_delimited, invalid };

BodyState DecodeChunked(const std::string& input, std::string* output) {
  output->clear();
  size_t offset = 0;
  while (offset < input.size()) {
    const size_t line_end = input.find("\r\n", offset);
    if (line_end == std::string::npos) return BodyState::incomplete;
    const std::string length_text = input.substr(offset, line_end - offset);
    const std::string size_text = length_text.substr(0, length_text.find(';'));
    size_t length = 0;
    const auto parsed = std::from_chars(size_text.data(), size_text.data() + size_text.size(), length, 16);
    if (parsed.ec != std::errc() || parsed.ptr != size_text.data() + size_text.size()) {
      return BodyState::invalid;
    }
    offset = line_end + 2;
    if (length == 0) {
      // The last chunk is followed by zero or more trailers and an empty line.
      for (;;) {
        const size_t trailer_end = input.find("\r\n", offset);
        if (trailer_end == std::string::npos) return BodyState::incomplete;
        if (trailer_end == offset) {
          return trailer_end + 2 == input.size() ? BodyState::complete : BodyState::invalid;
        }
        if (input.substr(offset, trailer_end - offset).find(':') == std::string::npos) {
          return BodyState::invalid;
        }
        offset = trailer_end + 2;
      }
    }
    if (length > kMaxResponseBytes - output->size()) return BodyState::invalid;
    if (length > input.size() - offset) return BodyState::incomplete;
    if (input.size() - offset - length < 2) return BodyState::incomplete;
    if (input.compare(offset + length, 2, "\r\n") != 0) return BodyState::invalid;
    output->append(input, offset, length);
    offset += length + 2;
  }
  return BodyState::incomplete;
}

std::string TrimHeader(std::string value) {
  const size_t begin = value.find_first_not_of(" \t");
  if (begin == std::string::npos) return {};
  return value.substr(begin, value.find_last_not_of(" \t") - begin + 1);
}

BodyState ParseHttpBody(const std::string& raw, std::string* body) {
  const size_t header_end = raw.find("\r\n\r\n");
  if (header_end == std::string::npos) return BodyState::incomplete;
  size_t cursor = raw.find("\r\n") + 2;
  bool has_length = false;
  bool chunked = false;
  size_t content_length = 0;
  while (cursor < header_end) {
    const size_t end = raw.find("\r\n", cursor);
    const std::string line = raw.substr(cursor, end - cursor);
    const size_t colon = line.find(':');
    if (colon == std::string::npos) return BodyState::invalid;
    std::string name = line.substr(0, colon);
    std::transform(name.begin(), name.end(), name.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    std::string value = TrimHeader(line.substr(colon + 1));
    if (name == "content-length") {
      if (has_length || chunked) return BodyState::invalid;
      const auto parsed = std::from_chars(value.data(), value.data() + value.size(), content_length);
      if (parsed.ec != std::errc() || parsed.ptr != value.data() + value.size() ||
          content_length > kMaxResponseBytes) return BodyState::invalid;
      has_length = true;
    } else if (name == "transfer-encoding") {
      std::transform(value.begin(), value.end(), value.begin(),
                     [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
      if (chunked || has_length || value != "chunked") return BodyState::invalid;
      chunked = true;
    }
    cursor = end + 2;
  }
  *body = raw.substr(header_end + 4);
  if (chunked) {
    const std::string encoded = std::move(*body);
    return DecodeChunked(encoded, body);
  }
  if (!has_length) return BodyState::close_delimited;
  if (body->size() < content_length) return BodyState::incomplete;
  return body->size() == content_length ? BodyState::complete : BodyState::invalid;
}

std::string ReadTls(SSL* ssl, int timeout_ms, const std::atomic<bool>* cancelled) {
  std::string response;
  char buffer[16384];
  while (response.size() < kMaxResponseBytes) {
    if (Cancelled(cancelled)) return {};
    const int read = SSL_read(ssl, buffer, sizeof(buffer));
    if (read > 0) {
      response.append(buffer, static_cast<size_t>(read));
      if (response.size() > kMaxResponseBytes) return {};
      std::string body;
      const BodyState framing = ParseHttpBody(response, &body);
      // Complete HTTP framing is enough; providers may omit TLS close_notify.
      if (framing == BodyState::complete) return response;
      if (framing == BodyState::invalid) return {};
      continue;
    }
    const int error = SSL_get_error(ssl, read);
    if (error == SSL_ERROR_ZERO_RETURN) {
      std::string body;
      const BodyState framing = ParseHttpBody(response, &body);
      return framing == BodyState::close_delimited || framing == BodyState::complete ? response : "";
    }
    if (read == 0 && error == SSL_ERROR_SYSCALL && !response.empty()) {
      std::string body;
      const BodyState framing = ParseHttpBody(response, &body);
      return framing == BodyState::close_delimited || framing == BodyState::complete ? response : "";
    }
    if (error == SSL_ERROR_WANT_READ && Wait(SSL_get_fd(ssl), POLLIN, timeout_ms, cancelled)) continue;
    if (error == SSL_ERROR_WANT_WRITE && Wait(SSL_get_fd(ssl), POLLOUT, timeout_ms, cancelled)) continue;
    return {};
  }
  return {};
}

}  // namespace

HttpResponse HttpsClient::PostJson(const std::string& url,
                                   const std::map<std::string, std::string>& headers,
                                   const std::string& body, int timeout_ms,
                                   const std::atomic<bool>* cancelled) const {
  HttpResponse result;
  Url parsed;
  if (!ParseUrl(url, &parsed)) { result.error = "invalid_https_url"; return result; }
  if (timeout_ms < 1) { result.error = "invalid_timeout"; return result; }
  Fd socket = Connect(parsed, timeout_ms, cancelled);
  if (socket.get() < 0) { result.error = Cancelled(cancelled) ? "cancelled" : "connect_failed"; return result; }

  static std::once_flag ssl_once;
  std::call_once(ssl_once, [] { OPENSSL_init_ssl(0, nullptr); });
  std::unique_ptr<SSL_CTX, decltype(&SSL_CTX_free)> context(SSL_CTX_new(TLS_client_method()), SSL_CTX_free);
  if (!context) {
    LogHttpsError("HTTPS TLS context creation failed");
    result.error = "tls_context_failed";
    return result;
  }
  SSL_CTX_set_verify(context.get(), SSL_VERIFY_PEER, nullptr);
#if defined(__ANDROID__)
  const char* ca_dir = access("/apex/com.android.conscrypt/cacerts", R_OK) == 0
      ? "/apex/com.android.conscrypt/cacerts" : "/system/etc/security/cacerts";
  if (SSL_CTX_load_verify_locations(context.get(), nullptr, ca_dir) != 1) {
#else
  if (SSL_CTX_set_default_verify_paths(context.get()) != 1) {
#endif
    LogHttpsError("HTTPS trust store load failed host=" + parsed.host);
    result.error = "tls_trust_store_unavailable"; return result;
  }
  std::unique_ptr<SSL, decltype(&SSL_free)> ssl(SSL_new(context.get()), SSL_free);
  if (!ssl) {
    LogHttpsError("HTTPS TLS session creation failed host=" + parsed.host);
    result.error = "tls_session_failed";
    return result;
  }
  SSL_set_fd(ssl.get(), socket.get());
  if (SSL_set_tlsext_host_name(ssl.get(), parsed.host.c_str()) != 1) {
    LogHttpsError("HTTPS SNI setup failed host=" + parsed.host);
    result.error = "tls_hostname_failed"; return result;
  }
  if (SSL_set1_host(ssl.get(), parsed.host.c_str()) != 1) {
    LogHttpsError("HTTPS hostname verification setup failed host=" + parsed.host);
    result.error = "tls_hostname_failed"; return result;
  }
  int handshake_error = 0;
  for (;;) {
    if (Cancelled(cancelled)) { result.error = "cancelled"; return result; }
    const int connected = SSL_connect(ssl.get());
    if (connected == 1) break;
    handshake_error = SSL_get_error(ssl.get(), connected);
    if (handshake_error == SSL_ERROR_WANT_READ && Wait(socket.get(), POLLIN, timeout_ms, cancelled)) continue;
    if (handshake_error == SSL_ERROR_WANT_WRITE && Wait(socket.get(), POLLOUT, timeout_ms, cancelled)) continue;
    LogHttpsError("HTTPS TLS handshake failed host=" + parsed.host +
                  " ssl_error=" + std::to_string(handshake_error));
    result.error = "tls_connect_failed"; return result;
  }

  std::ostringstream request;
  request << "POST " << parsed.path << " HTTP/1.1\r\n"
          << "Host: " << parsed.host << "\r\n"
          << "Content-Type: application/json\r\n"
          << "Content-Length: " << body.size() << "\r\n"
          << "Connection: close\r\n";
  for (const auto& [name, value] : headers) request << name << ": " << value << "\r\n";
  request << "\r\n" << body;
  if (!WriteTls(ssl.get(), request.str(), timeout_ms, cancelled)) {
    LogHttpsError("HTTPS request write failed host=" + parsed.host);
    result.error = Cancelled(cancelled) ? "cancelled" : "tls_write_failed"; return result;
  }
  std::string raw = ReadTls(ssl.get(), timeout_ms, cancelled);
  if (raw.empty()) {
    LogHttpsError("HTTPS response read failed host=" + parsed.host);
    result.error = Cancelled(cancelled) ? "cancelled" : "tls_read_failed";
    return result;
  }
  const size_t header_end = raw.find("\r\n\r\n");
  if (header_end == std::string::npos) { result.error = "invalid_http_response"; return result; }
  const size_t first_line_end = raw.find("\r\n");
  if (first_line_end == std::string::npos) { result.error = "invalid_http_status"; return result; }
  std::istringstream status_line(raw.substr(0, first_line_end));
  std::string version;
  status_line >> version >> result.status;
  if (result.status < 100 || result.status > 599) { result.error = "invalid_http_status"; return result; }
  LogHttpsError("HTTPS response status host=" + parsed.host +
                " status=" + std::to_string(result.status));
  std::string response_body;
  const BodyState framing = ParseHttpBody(raw, &response_body);
  if (framing != BodyState::complete && framing != BodyState::close_delimited) {
    result.error = "invalid_http_framing";
    return result;
  }
  result.body = std::move(response_body);
  if (result.status < 200 || result.status >= 300) result.error = "http_error";
  return result;
}

}  // namespace agentos
