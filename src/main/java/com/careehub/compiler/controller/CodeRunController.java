package com.careehub.compiler.controller;

import com.careehub.compiler.dto.CodeRunRequestForm;
import com.careehub.compiler.dto.CodeRunResponseForm;
import com.careehub.compiler.service.CodeExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CodeRunController {

    private final CodeExecutionService codeExecutionService;

    public CodeRunController(CodeExecutionService codeExecutionService) {
        this.codeExecutionService = codeExecutionService;
    }

    @PostMapping("/run")
    public ResponseEntity<CodeRunResponseForm> runCode(@RequestBody CodeRunRequestForm request) {
        CodeRunResponseForm result = codeExecutionService.execute(request);
        return ResponseEntity.ok(result);
    }
}
