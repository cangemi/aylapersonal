package ayla.whatsapp;

public class WhatsappException extends RuntimeException {
    private final int status;
    public WhatsappException(int status,String message) { super(message);this.status=status; }
    public int status() { return status; }
}
