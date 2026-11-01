package com.fcourt.payment;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;

final class TicketQr {
    private static final SecureRandom RANDOM=new SecureRandom();
    private TicketQr() { }
    static String newCode() { return String.format(Locale.ROOT,"%032d",new BigInteger(106,RANDOM)); }
    static byte[] png(String code) {
        try {
            var matrix=new QRCodeWriter().encode(code,BarcodeFormat.QR_CODE,600,600,Map.of(EncodeHintType.MARGIN,4));
            var output=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",output);return output.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Ticket rendering failed",e); }
    }
}
