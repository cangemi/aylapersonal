package ayla.hermes;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serialize mutable voice configuration only within the same profile. */
final class ProfileVoiceLocks {
    private static final class Slot { final ReentrantLock lock=new ReentrantLock();int users; }
    private final ConcurrentHashMap<String,Slot> slots=new ConcurrentHashMap<>();
    <T> T run(String profile,Supplier<T> action){
        if(profile==null||!profile.matches("[a-zA-Z0-9_-]{1,64}"))throw new HermesException(503,"Perfil de mídia inválido.");
        Slot slot=slots.compute(profile,(key,current)->{var value=current==null?new Slot():current;value.users++;return value;});
        boolean acquired=false;
        try{slot.lock.lockInterruptibly();acquired=true;return action.get();}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new HermesException(503,"Geração de áudio interrompida.");}
        finally{
            if(acquired)slot.lock.unlock();
            slots.computeIfPresent(profile,(key,current)->{current.users--;return current.users==0?null:current;});
        }
    }
}
