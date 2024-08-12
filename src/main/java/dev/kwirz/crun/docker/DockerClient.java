package dev.kwirz.crun.docker;

import org.jspecify.annotations.NullUnmarked;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@NullUnmarked
public interface DockerClient extends AutoCloseable {

    static DockerClient fromUnixDomainSocket(String path) {
        return DefaultDockerClient.fromUnixDomainSocket(path);
    }

    <T> HttpResponse<T> send(HttpRequest req, Class<T> type);

    @Override
    void close();
}
