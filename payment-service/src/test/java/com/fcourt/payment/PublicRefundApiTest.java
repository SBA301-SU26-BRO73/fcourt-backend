package com.fcourt.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicRefundController.class)
@Import(PaymentSecurity.class)
class PublicRefundApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean RefundService service;
    @MockitoBean ReceiptUrls urls;
    @MockitoBean JwtDecoder decoder;

    @Test void anonymousGuestCanSubmitWithoutBankBinAndResponseIsNotCached() throws Exception {
        var refund=new RefundView(5,42,new java.math.BigDecimal("80000"),"PENDING","VCB",null,"123456789",
            "NGUYEN VAN A","Cancelled",null,null,java.time.Instant.EPOCH,java.time.Instant.EPOCH,"0901234567");
        when(service.guestRequest(any())).thenReturn(refund);when(urls.view(refund)).thenReturn(refund);
        mvc.perform(post("/api/public/refunds").contentType("application/json").content("""
            {"bookingCode":"fc-00000042","phone":"+84901234567","bankName":"VCB",
             "accountNumber":"123456789","accountHolder":"NGUYEN VAN A"}
            """))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.id").value(5)).andExpect(jsonPath("$.bankInfoMissing").value(false))
            .andExpect(jsonPath("$.bankAccountNumber").value("123456789"));
        verify(service).guestRequest(new RefundService.GuestRequest("fc-00000042","+84901234567","VCB","123456789","NGUYEN VAN A",null));
    }

    @Test void anonymousRefundLookupRequiresCodeAndPhoneAndDoesNotCache() throws Exception {
        var refund=new RefundView(5,42,java.math.BigDecimal.TEN,"PENDING",null,null,null,null,
            "Cancelled",null,null,java.time.Instant.EPOCH,java.time.Instant.EPOCH,"0901234567");
        when(service.guestState(any())).thenReturn(new RefundService.State(42,java.math.BigDecimal.TEN,true,refund));
        when(urls.view(refund)).thenReturn(refund);
        mvc.perform(post("/api/public/refunds/lookup").contentType("application/json")
            .content("{\"bookingCode\":\"FC00000042\",\"phone\":\"0901234567\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.bookingId").value(42)).andExpect(jsonPath("$.eligible").value(true))
            .andExpect(jsonPath("$.request.bankInfoMissing").value(true)).andExpect(jsonPath("$.request.customerPhone").value("0901234567"));
    }

    @Test void malformedIdentityAndBankDetailsAreRejectedBeforeAuthorityCall() throws Exception {
        String valid="""
            {"bookingCode":"FC00000042","phone":"0901234567","bankName":"VCB",
             "accountNumber":"123456789","accountHolder":"NGUYEN VAN A"}
            """;
        for(String body:java.util.List.of("{}",valid.replace("FC00000042","OTHER"),valid.replace("0901234567","abc"),
            valid.replace("123456789","bad"),valid.replace("NGUYEN VAN A"," "),valid.replace("VCB"," "),
            valid.replace("\"bankName\"","\"bankBin\":\"000000\",\"bankName\"")))
            mvc.perform(post("/api/public/refunds").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
