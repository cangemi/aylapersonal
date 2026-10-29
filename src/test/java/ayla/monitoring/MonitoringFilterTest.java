package ayla.monitoring;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MonitoringFilterTest {
    @Test void storageFailureNeverChangesASuccessfulApplicationResponse() throws Exception {
        var service=mock(MonitoringService.class);
        doThrow(new IllegalStateException("storage unavailable")).when(service).record(any());
        var request=new MockHttpServletRequest("GET","/api/v1/finance/entries");
        var response=new MockHttpServletResponse();
        new MonitoringFilter(service).doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(200));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Ayla-Request-Id")).isNotBlank();
        verify(service).record(any());
    }
}
