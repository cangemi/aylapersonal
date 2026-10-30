package ayla.chat.queue;

/** A durable barrier is written BEFORE the first external inference can execute tools. */
public final class ChatJobContext implements AutoCloseable {
    private static final ThreadLocal<ChatJobContext> CURRENT=new ThreadLocal<>();
    private final Runnable barrier;
    private final Runnable rejected;
    private boolean dispatched;
    private boolean accepted;
    private boolean preparedMedia;
    public ChatJobContext(Runnable barrier){this(barrier,()->{});}
    public ChatJobContext(Runnable barrier,Runnable rejected){this.barrier=barrier;this.rejected=rejected;CURRENT.set(this);}
    public boolean dispatched(){return dispatched;}
    public static boolean active(){return CURRENT.get()!=null;}
    public static boolean safeToRequeue(){var scope=CURRENT.get();return scope!=null&&!scope.accepted&&!scope.dispatched&&!scope.preparedMedia;}
    public static void mediaPrepared(){var scope=CURRENT.get();if(scope!=null)scope.preparedMedia=true;}
    public static void beforeInference(){
        var scope=CURRENT.get();
        if(scope!=null&&!scope.dispatched){scope.barrier.run();scope.dispatched=true;}
    }
    public static void inferenceAccepted(){var scope=CURRENT.get();if(scope!=null)scope.accepted=true;}
    public static void admissionRejected(){
        var scope=CURRENT.get();
        if(scope!=null&&!scope.accepted){scope.rejected.run();scope.dispatched=false;}
    }
    @Override public void close(){CURRENT.remove();}
}
