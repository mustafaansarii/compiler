package com.careehub.compiler.dto;

import lombok.Data;

@Data
public class CodeRunRequestForm {
    private String language;
    private String code;
    private String input;
    private int timeLimit;
    private int memoryLimit;
}
