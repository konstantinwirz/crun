package dev.kwirz.crun.docker;

public class DockerClientException extends RuntimeException {

    public DockerClientException(String message) {
        super(message);
    }

    public DockerClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
