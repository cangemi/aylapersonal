package ayla.cluster;

/** The protected operation has NOT started. A durable job may wait safely. */
public class OperationBusyException extends ayla.hermes.HermesException {
    public OperationBusyException(int status,String message){super(status,message);}
}
