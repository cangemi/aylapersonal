package ayla.cluster;

import ayla.hermes.HermesException;
import java.sql.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** PostgreSQL session locks coordinate replicas without holding a transaction across inference.
 * Each active operation occupies one pool connection; the chat limit leaves capacity for tools.
 * Connections are released even when the provider fails. A crashed process releases its locks.
 */
@Service
public class ClusterOperations {
    private final DataSource dataSource;
    private final Semaphore chats;
    private final int limit;
    private io.micrometer.core.instrument.Counter capacityRejected,conflictRejected;
    @org.springframework.beans.factory.annotation.Autowired
    void metrics(io.micrometer.core.instrument.MeterRegistry registry){
        io.micrometer.core.instrument.Gauge.builder("ayla.chat.active",chats,s->limit-s.availablePermits()).register(registry);
        capacityRejected=registry.counter("ayla.chat.rejected","reason","capacity");
        conflictRejected=registry.counter("ayla.operation.rejected","reason","already_running");
    }
    public ClusterOperations(DataSource dataSource,@Value("${ayla.cluster.max-concurrent-chats:3}") int limit){
        if(limit<1||limit>16)throw new IllegalArgumentException("Chat concurrency must be between 1 and 16");
        this.dataSource=dataSource;this.limit=limit;this.chats=new Semaphore(limit);
    }
    public <T> T chat(String conversation,Supplier<T> work){
        if(!chats.tryAcquire()){
            if(capacityRejected!=null)capacityRejected.increment();
            throw new OperationBusyException(429,"A Ayla está atendendo outros pedidos. Aguarde um instante e tente novamente.");
        }
        try{return exclusive("chat:"+conversation,work);}finally{chats.release();}
    }
    public <T> T exclusive(String key,Supplier<T> work){
        try(Connection connection=dataSource.getConnection()){
            boolean acquired;
            try(var statement=connection.prepareStatement("select pg_try_advisory_lock(hashtextextended(?,0))")){
                statement.setQueryTimeout(5);statement.setString(1,"ayla:"+key);
                try(var rows=statement.executeQuery()){acquired=rows.next()&&rows.getBoolean(1);}
            }
            if(!acquired){
                if(conflictRejected!=null)conflictRejected.increment();
                throw new OperationBusyException(409,"Esta operação já está em andamento. Aguarde sua conclusão.");
            }
            try{return work.get();}
            finally{
                // Never return a connection carrying a session lock to Hikari.
                try(var statement=connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?,0))")){
                    statement.setQueryTimeout(5);statement.setString(1,"ayla:"+key);statement.execute();
                }catch(SQLException failure){connection.abort(Runnable::run);throw failure;}
            }
        }catch(SQLException failure){throw new HermesException(503,"Não foi possível coordenar esta operação agora.");}
    }
}
