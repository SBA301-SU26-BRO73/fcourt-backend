package com.fcourt.auth.staff;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ManagedStaffBoundary {
    private final RestClient staff;
    public ManagedStaffBoundary(RestClient.Builder builder,@Value("${STAFF_URL:http://localhost:8090}") String url) {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(3));
        staff=builder.clone().requestFactory(factory).baseUrl(url).build();
    }
    public void require(long user,long branch,String token) {
        try {
            var result=staff.get().uri(uri->uri.path("/api/admin/staff/accounts/{id}").queryParam("branchId",branch).build(user))
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+token).retrieve().body(Assignment.class);
            if(result==null||result.userId()!=user||result.branchId()!=branch)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Managed staff ownership required");
        }catch(HttpClientErrorException e){
            if(e.getStatusCode().value()==401||e.getStatusCode().value()==403||e.getStatusCode().value()==404) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Managed staff ownership required");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Staff verification unavailable");
        }catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Staff verification unavailable");}
    }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Assignment(long userId,long branchId) { }
}
