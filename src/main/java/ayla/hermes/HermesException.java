package ayla.hermes;

public class HermesException extends RuntimeException {
    private final int status;
    public HermesException(int status, String message) {
        super(message);
        this.status = status;
    }
    public int status() { return status; }
}
