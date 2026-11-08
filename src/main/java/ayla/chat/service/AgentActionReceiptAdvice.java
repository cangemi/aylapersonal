package ayla.chat.service;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Runs after a successful controller transaction, before the tool receives its result. */
@RestControllerAdvice
public class AgentActionReceiptAdvice implements ResponseBodyAdvice<Object> {
    private final AgentActionReceipts receipts;
    public AgentActionReceiptAdvice(AgentActionReceipts receipts){this.receipts=receipts;}
    @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){
        String type=method.getContainingClass().getSimpleName();
        return java.util.Set.of("FinanceBridgeController","InvestmentBridge","HouseholdBridge","CalendarBridge","AlarmBridge","PersonalMemoryBridge").contains(type);
    }
    @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
        String[] path=request.getURI().getPath().split("/");
        if(path.length==5&&"internal".equals(path[1])&&"hermes".equals(path[2])){
            boolean shopping="finance".equals(path[3])&&"record".equals(path[4])
                &&body instanceof java.util.Map<?,?> result&&"SHOPPING".equals(result.get("destination"));
            String domain=shopping?"household":path[3],operation=shopping?"apply":path[4];
            if(AgentActionReceipts.mutation(domain,operation))
                receipts.record(request.getHeaders().getFirst("X-Ayla-Session"),domain,operation);
            if("household".equals(domain)&&"checkout".equals(operation)
                    &&body instanceof ayla.household.HouseholdData.CheckoutResult checkout&&checkout.financialEntry()!=null)
                receipts.record(request.getHeaders().getFirst("X-Ayla-Session"),"finance","record");
        }
        return body;
    }
}
