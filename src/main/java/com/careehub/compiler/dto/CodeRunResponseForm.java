package com.careehub.compiler.dto;

import lombok.Data;

@Data
public class CodeRunResponseForm {
    private String status;
    private String stdout;
    private String stderr;
    private int exitCode;
    private int executionTimeMs;
    private int memoryUsed;
}
