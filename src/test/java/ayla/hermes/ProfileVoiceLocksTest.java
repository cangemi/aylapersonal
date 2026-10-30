package ayla.hermes;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class ProfileVoiceLocksTest {
 @Test void differentProfilesDoNotWaitForAnotherUsersVoice() throws Exception {
  var locks=new ProfileVoiceLocks();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var executor=Executors.newFixedThreadPool(2);
  try {
   var first=executor.submit(()->locks.run("user-a",()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}return "a";}));
   assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
   var second=executor.submit(()->locks.run("user-b",()->"b"));
   assertThat(second.get(2,TimeUnit.SECONDS)).isEqualTo("b");
   release.countDown();assertThat(first.get(2,TimeUnit.SECONDS)).isEqualTo("a");
  }finally{release.countDown();executor.shutdownNow();}
 }
 @Test void failedSynthesisReleasesProfileForAnotherThread() throws Exception {
  var locks=new ProfileVoiceLocks();
  assertThatThrownBy(()->locks.run("same-user",()->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
  var executor=Executors.newSingleThreadExecutor();
  try{
   assertThat(executor.submit(()->locks.run("same-user",()->"ok")).get(2,TimeUnit.SECONDS)).isEqualTo("ok");
  }finally{executor.shutdownNow();}
 }
}
