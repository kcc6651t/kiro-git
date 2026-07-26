package com.company.filepreview.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Uniform error body returned to the frontend.
 */
@Data
@AllArgsConstructor
public class ApiError {
    private String code;
    private String message;
    private String requestId;
}
