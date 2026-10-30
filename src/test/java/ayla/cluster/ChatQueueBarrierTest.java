package ayla.cluster;

import ayla.chat.queue.ChatJobContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatQueueBarrierTest {
    @Test void admissionRejectionBeforeAnyAcceptedInferenceRemainsSafeToResume(){
        var marked=new AtomicInteger();var cleared=new AtomicInteger();
        try(var context=new ChatJobContext(marked::incrementAndGet,cleared::incrementAndGet)){
            ChatJobContext.beforeInference();assertThat(context.dispatched()).isTrue();
            ChatJobContext.admissionRejected();assertThat(context.dispatched()).isFalse();
            assertThat(ChatJobContext.safeToRequeue()).isTrue();
            ChatJobContext.beforeInference();ChatJobContext.inferenceAccepted();
            ChatJobContext.admissionRejected();assertThat(context.dispatched()).isTrue();
            assertThat(ChatJobContext.safeToRequeue()).isFalse();
            assertThat(marked.get()).isEqualTo(2);assertThat(cleared.get()).isEqualTo(1);
        }
        assertThat(ChatJobContext.active()).isFalse();
        try(var context=new ChatJobContext(()->{})){
            ChatJobContext.mediaPrepared();ChatJobContext.beforeInference();ChatJobContext.admissionRejected();
            assertThat(ChatJobContext.safeToRequeue()).isFalse();
        }
    }
}
