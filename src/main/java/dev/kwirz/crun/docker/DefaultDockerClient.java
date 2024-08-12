package dev.kwirz.crun.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NullUnmarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@NullUnmarked
public final class DefaultDockerClient implements DockerClient {

    public static final Logger LOG = LoggerFactory.getLogger(DefaultDockerClient.class);
    public static final int READ_CHUNK_SIZE = 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final SocketChannel chan;

    public DefaultDockerClient(SocketChannel chan) {
        this.chan = chan;
    }

    public static DockerClient fromUnixDomainSocket(String path) {
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(path);
        try {
            return new DefaultDockerClient(SocketChannel.open(addr));
        } catch (IOException e) {
            throw new DockerClientException("Failed to connect to docker socket: " + path, e);
        }
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest req, Class<T> type) {
        sendRequest(req);
        return readResponse(req, type);
    }

    @Override
    public void close() {
        try {
            chan.close();
        } catch (IOException e) {
            throw new DockerClientException("Failed to close docker socket: " + e.getMessage(), e);
        }
    }

    private void sendRequest(HttpRequest req) {
        var method = req.method();
        var path = req.uri().getPath();
        var host = req.uri().getHost();
        var httpVersion = req.version().map(v -> switch (v) {
            case HTTP_1_1 -> "HTTP/1.1";
            case HTTP_2 -> "HTTP/2";
        }).orElse("HTTP/1.1");

        var rawRequest = """
                %s %s %s
                Host: %s
                Accept: application/json
                \r
                """.formatted(method, path, httpVersion, host);
        LOG.debug("sending request:\n{}", rawRequest);

        if (!chan.isConnected()) {
            throw new DockerClientException("Failed to connect to docker socket");
        }

        var buf = ByteBuffer.wrap(rawRequest.getBytes());
        try {
            while (buf.hasRemaining()) {
                int bytesWritten = chan.write(buf);
                LOG.debug("wrote {} bytes to the socket", bytesWritten);
            }
        } catch (IOException e) {
            throw new DockerClientException("Failed to write to docker socket: " + e.getMessage(), e);
        }
    }

    private String readChunk(ByteBuffer buf) {
        try {
            buf.clear();
            int bytesRead = chan.read(buf);
            LOG.debug("read {} bytes from the socket", bytesRead);
            var result = new String(buf.array(), 0, bytesRead, StandardCharsets.UTF_8);

            buf.flip();

            return result;
        } catch (IOException e) {
            throw new DockerClientException("failed to read from socket: " + e.getMessage(), e);
        }
    }

    private String readRemainingChunks(ByteBuffer buf) {
        // first line is the size of incoming chunk
        buf.clear();
        try {
            int bytesRead = chan.read(buf);
            LOG.debug("read a chunk of size: {} bytes", bytesRead);
            var chunk = new String(buf.array(), 0, bytesRead, StandardCharsets.UTF_8);
            var lines = chunk.split(System.lineSeparator(), 3);
            // expected 3 lines
            // 1 -  size of current chunk
            // 2. - chunk of data
            // 3. - size of the remaining chunk
            if (lines.length != 3) {
                throw new DockerClientException("Expected exactly 3 lines in a chunk, got: " + lines.length);
            }

            var chunkSize = Integer.parseInt(lines[0].strip(), 16);
            var data = lines[1];
            var remaining = Integer.parseInt(lines[2].strip(), 16);

            LOG.debug("read chunk of size = {}, remaining = {}", chunkSize, remaining);

            if (remaining == 0) {
                return data;
            }

            return data + readRemainingChunks(buf);
        } catch (IOException e) {
            throw new DockerClientException("failed to read further chunks: " + e.getMessage(), e);
        }
    }

    private HttpHeaders parseHeaders(String s) {
        var headers = s.lines()
                .map(line -> line.split(":"))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(parts -> parts[0], parts -> List.of(parts[1])));
        return HttpHeaders.of(headers, (_, _) -> true);
    }

    private <T> HttpResponse<T> readResponse(HttpRequest req, Class<T> type) {
        // first read delivers the status line and the headers
        // look for transfer-encoding: chunked and then read again, first lien will be the size of the following chunk
        // last line how many bytes are to come

        var buf = ByteBuffer.allocate(READ_CHUNK_SIZE);

        // read first chunk
        String firstChunk = readChunk(buf);

        // parse status line
        // we are expecting http version and the http status code
        String[] lines = firstChunk.split(System.lineSeparator(), 2);
        if (lines.length == 0) {
            throw new DockerClientException("Failed to read response from docker socket");
        }
        var firstLine = lines[0].strip();
        var statusLine = firstLine.split("\\s+", 3);
        if (statusLine.length < 2) {
            throw new DockerClientException("Failed to parse status line: " + firstLine);
        }
        var httpVersion = statusLine[0].equals("HTTP/1.1") ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2;
        var statusCode = Integer.parseInt(statusLine[1]);

        // parse headers
        var headers = parseHeaders(firstChunk);

        // let's see if we have a transfer-encoding: chunked header
        var chunked = headers.firstValue("Transfer-Encoding").map(s -> s.equals("chunked")).orElse(false);

        LOG.info("firstChunk: {}", firstChunk);
        LOG.info("headers: {}", headers);
        LOG.info("chunked?: {}", chunked);

        String body = null;
        if (chunked) {
            body = readRemainingChunks(buf);
        }

        var reader = OBJECT_MAPPER.readerFor(type);
        try {
            return new DockerClientHttpResponse<>(
                    statusCode,
                    req,
                    headers,
                    reader.readValue(body),
                    req.uri(),
                    httpVersion
            );
        } catch (JsonProcessingException e) {
            throw new DockerClientException("Failed to parse response body: " + e.getMessage(), e);
        }
    }
}
