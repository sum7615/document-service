package com.spxam.document_service.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import com.spxam.document_service.enums.ScanStatus;

@Service
public class VirusScanService {

    private static final String CLAMAV_HOST = "localhost";
    private static final int CLAMAV_PORT = 3310;

    public boolean scanFile(InputStream inputStream, Document doc) throws IOException {
        try (Socket socket = new Socket(CLAMAV_HOST, CLAMAV_PORT);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[2048];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                byte[] length = ByteBuffer.allocate(4).putInt(read).array();
                out.write(length);
                out.write(buffer, 0, read);
            }
            out.write(ByteBuffer.allocate(4).putInt(0).array());
            out.flush();

            String response = new BufferedReader(new InputStreamReader(in)).readLine();

            if (response != null && response.contains("OK")) {
                doc.setScanStatus(ScanStatus.CLEAN);
                doc.setScanResult("OK");
                return true;
            } else {
                doc.setScanStatus(ScanStatus.INFECTED);
                doc.setScanResult(response);
                return false;
            }
        }
    }
}
