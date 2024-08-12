package dev.kwirz.crun;

import dev.kwirz.crun.docker.api.SystemVersion;
import dev.kwirz.crun.docker.DockerClient;

import java.net.URI;
import java.net.http.HttpRequest;

public class App {

    public static void main(String[] args) {
        try (var client = DockerClient.fromUnixDomainSocket("/var/run/docker.sock")) {
            var req = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create("http://docker/version"))
                    .header("Accept", "application/json")
                    .build();

            var response = client.send(req, SystemVersion.class);

            System.out.println(response.body());
        }
    }

}
