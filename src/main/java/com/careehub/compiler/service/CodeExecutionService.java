package com.careehub.compiler.service;

import com.careehub.compiler.dto.CodeRunRequestForm;
import com.careehub.compiler.dto.CodeRunResponseForm;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class CodeExecutionService {

    private static final int DEFAULT_TIME_LIMIT_MS = 5000;
    private static final int MAX_TIME_LIMIT_MS = 15000;

    public CodeRunResponseForm execute(CodeRunRequestForm request) {
        CodeRunResponseForm response = new CodeRunResponseForm();

        if (request.getCode() == null || request.getCode().isBlank()) {
            response.setStatus("ERROR");
            response.setStderr("Code is required");
            response.setStdout("");
            response.setExitCode(-1);
            response.setExecutionTimeMs(0);
            response.setMemoryUsed(0);
            return response;
        }

        String language = normalizeLanguage(request.getLanguage());
        if (language == null) {
            response.setStatus("ERROR");
            response.setStderr("Unsupported language");
            response.setStdout("");
            response.setExitCode(-1);
            response.setExecutionTimeMs(0);
            response.setMemoryUsed(0);
            return response;
        }

        int timeLimitMs = clamp(
                request.getTimeLimit() > 0 ? request.getTimeLimit() : DEFAULT_TIME_LIMIT_MS,
                1_000,
                MAX_TIME_LIMIT_MS
        );

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("code-run-");
            Path sourceFile = writeSourceFile(tempDir, language, request.getCode());

            ExecResult compileResult = null;
            ExecResult runResult;

            if ("java".equals(language) || "c".equals(language) || "cpp".equals(language)) {
                List<String> compileCommand = buildCompileCommand(language, sourceFile.getFileName().toString());
                compileResult = runProcess(compileCommand, tempDir, null, timeLimitMs);

                if (compileResult.timedOut) {
                    response.setStatus("TIMEOUT");
                    response.setExitCode(-1);
                    response.setStdout(limitSize(compileResult.stdout, 32 * 1024));
                    response.setStderr(limitSize(compileResult.stderr, 32 * 1024));
                    response.setExecutionTimeMs(compileResult.durationMs);
                    response.setMemoryUsed(0);
                    return response;
                }

                if (compileResult.exitCode != 0) {
                    response.setStatus("ERROR");
                    response.setExitCode(compileResult.exitCode);
                    response.setStdout(limitSize(compileResult.stdout, 32 * 1024));
                    response.setStderr(limitSize(compileResult.stderr, 32 * 1024));
                    response.setExecutionTimeMs(compileResult.durationMs);
                    response.setMemoryUsed(0);
                    return response;
                }

                int remainingTimeMs = Math.max(100, timeLimitMs - compileResult.durationMs);
                List<String> runCommand = buildRunCommand(language);
                runResult = runProcess(runCommand, tempDir, request.getInput(), remainingTimeMs);
            } else {
                List<String> runCommand = buildRunCommand(language);
                runResult = runProcess(runCommand, tempDir, request.getInput(), timeLimitMs);
            }

            if (runResult.timedOut) {
                response.setStatus("TIMEOUT");
                response.setExitCode(-1);
            } else {
                response.setExitCode(runResult.exitCode);
                response.setStatus(response.getExitCode() == 0 ? "OK" : "ERROR");
            }

            String combinedStdout = (compileResult != null ? compileResult.stdout : "") + runResult.stdout;
            String combinedStderr = (compileResult != null ? compileResult.stderr : "") + runResult.stderr;

            int totalDurationMs = runResult.durationMs + (compileResult != null ? compileResult.durationMs : 0);

            response.setStdout(limitSize(combinedStdout, 32 * 1024));
            response.setStderr(limitSize(combinedStderr, 32 * 1024));
            response.setExecutionTimeMs(totalDurationMs);
            response.setMemoryUsed(0);

        } catch (IOException e) {
            response.setStatus("ERROR");
            response.setStdout("");
            response.setStderr("Execution failed: " + e.getMessage());
            response.setExitCode(-1);
            response.setExecutionTimeMs(0);
            response.setMemoryUsed(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus("ERROR");
            response.setStdout("");
            response.setStderr("Execution interrupted");
            response.setExitCode(-1);
            response.setExecutionTimeMs(0);
            response.setMemoryUsed(0);
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }

        return response;
    }

    private String normalizeLanguage(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "java" -> "java";
            case "c" -> "c";
            case "c++", "cpp" -> "cpp";
            case "python", "python3", "py" -> "python";
            case "javascript", "js", "node", "nodejs" -> "node";
            default -> null;
        };
    }

    private Path writeSourceFile(Path dir, String language, String code) throws IOException {
        String fileName = switch (language) {
            case "java" -> "Main.java";
            case "c" -> "main.c";
            case "cpp" -> "main.cpp";
            case "python" -> "main.py";
            case "node" -> "main.js";
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
        Path file = dir.resolve(fileName);
        Files.writeString(file, code, StandardCharsets.UTF_8);
        return file;
    }

    private List<String> buildCompileCommand(String language, String sourceFileName) {
        List<String> cmd = new ArrayList<>();
        switch (language) {
            case "java" -> {
                cmd.add("javac");
                cmd.add(sourceFileName);
            }
            case "c" -> {
                cmd.add("gcc");
                cmd.add(sourceFileName);
                cmd.add("-O2");
                cmd.add("-std=c17");
                cmd.add("-o");
                cmd.add("main.out");
            }
            case "cpp" -> {
                cmd.add("g++");
                cmd.add(sourceFileName);
                cmd.add("-O2");
                cmd.add("-std=c++17");
                cmd.add("-o");
                cmd.add("main.out");
            }
            default -> throw new IllegalArgumentException("No compile step for language: " + language);
        }
        return cmd;
    }

    private List<String> buildRunCommand(String language) {
        List<String> cmd = new ArrayList<>();
        switch (language) {
            case "java" -> {
                cmd.add("java");
                cmd.add("Main");
            }
            case "c", "cpp" -> {
                cmd.add("./main.out");
            }
            case "python" -> {
                cmd.add("python3");
                cmd.add("main.py");
            }
            case "node" -> {
                cmd.add("node");
                cmd.add("main.js");
            }
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        }
        return cmd;
    }

    private ExecResult runProcess(List<String> command, Path workingDir, String input, int timeoutMs)
            throws IOException, InterruptedException {

        long start = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        if (input != null && !input.isEmpty()) {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
            )) {
                writer.write(input);
                writer.flush();
            }
        } else {
            process.getOutputStream().close();
        }

        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        Thread stdoutThread = streamToStringBuilder(process.getInputStream(), stdoutBuilder);
        Thread stderrThread = streamToStringBuilder(process.getErrorStream(), stderrBuilder);

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        long end = System.nanoTime();

        if (!finished) {
            process.destroyForcibly();
        }

        stdoutThread.join();
        stderrThread.join();

        int exitCode = finished ? process.exitValue() : -1;
        int durationMs = (int) Duration.ofNanos(end - start).toMillis();

        return new ExecResult(exitCode, stdoutBuilder.toString(), stderrBuilder.toString(), durationMs, !finished);
    }

    private Thread streamToStringBuilder(InputStream inputStream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // ignore stream errors
            }
        });
        t.start();
        return t;
    }

    private String limitSize(String text, int maxBytes) {
        if (text == null) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "\n...[truncated]...";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static final class ExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        final int durationMs;
        final boolean timedOut;

        ExecResult(int exitCode, String stdout, String stderr, int durationMs, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.durationMs = durationMs;
            this.timedOut = timedOut;
        }
    }
}
