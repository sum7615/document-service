package com.spxam.document_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScanVerdict {

    private  boolean malicious;
    private  String reason;
    
    public static ScanVerdict clean() {
        return new ScanVerdict(false, null);
    }

    /** Factory method for a malicious file */
    public static ScanVerdict malicious(String reason) {
        return new ScanVerdict(true, reason);
    }

    /** Returns true if the file is malicious */
    public boolean isMalicious() {
        return malicious;
    }

    /** Returns the reason for malicious verdict (null if clean) */
    public String getReason() {
        return reason;
    }
}
