package org.integratedmodelling.klab.authority.un;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** Read-only SPARQL protocol client. Uses only JDK HTTP and the host's Jackson ABI. */
final class SparqlClient {
  void close() { http.close(); }
  private static final int MAX_BYTES = 32 * 1024 * 1024;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  static final ObjectMapper JSON = new ObjectMapper();

  JsonNode select(URI endpoint, Duration timeout, String query) {
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/sparql-results+json")
            .header("Content-Type", "application/sparql-query; charset=UTF-8")
            .header("User-Agent", "klab.authority.un/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build();
    var pending = http.sendAsync(request, info -> new BoundedBody());
    try {
      var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200)
        throw new IllegalStateException("Caliper HTTP " + response.statusCode());
      String type =
          response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
      if (!type.equalsIgnoreCase("application/sparql-results+json")
          && !type.equalsIgnoreCase("application/json"))
        throw new IllegalStateException("Caliper returned unexpected media type: " + type);
      var json = JSON.readTree(response.body());
      if (json == null
          || !json.path("head").path("vars").isArray()
          || !json.path("results").path("bindings").isArray())
        throw new IllegalStateException("Invalid SPARQL SELECT result");
      return json.path("results").path("bindings");
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Caliper request interrupted", e);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new IllegalStateException("Caliper request timed out", e);
    } catch (ExecutionException | java.io.IOException e) {
      throw new IllegalStateException("Caliper request failed", e);
    }
  }

  static URI endpoint(Object value) {
    if (!(value instanceof String text))
      throw new IllegalArgumentException("endpoint must be a URL string");
    URI uri = URI.create(text);
    boolean local =
        uri.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
      throw new IllegalArgumentException(
          "endpoint must use HTTPS (loopback HTTP is allowed for tests)");
    return uri;
  }

  static String iri(String text) {
    var uri = URI.create(text);
    if (!uri.isAbsolute()
        || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || text.chars().anyMatch(c -> c <= 32 || "<>\"{}|^`\\".indexOf(c) >= 0))
      throw new IllegalArgumentException("Invalid RDF IRI: " + text);
    return "<" + text + ">";
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(
              new IllegalStateException("Caliper response exceeds 32 MiB"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
