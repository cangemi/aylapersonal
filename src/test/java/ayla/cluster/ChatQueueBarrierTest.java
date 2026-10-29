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
            ChatJobContext.beforeInference();ChatJobContext.inferenceAccepted();
            ChatJobContext.admissionRejected();assertThat(context.dispatched()).isTrue();
            assertThat(marked.get()).isEqualTo(2);assertThat(cleared.get()).isEqualTo(1);
        }
        assertThat(ChatJobContext.active()).isFalse();
    }
}
