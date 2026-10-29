package ayla.household;
public class HouseholdException extends RuntimeException {
    private final int status;
    public HouseholdException(int status,String message) { super(message);this.status=status; }
    public int status() {return status;}
}
