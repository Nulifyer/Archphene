package org.archpheneos.manager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class PayloadLauncher {
    public static final class Result {
        public final String mode;
        public final String command;
        public final int exitCode;
        public final boolean timedOut;
        public final String stdout;
        public final String stderr;
        public final String startError;

        private Result(
                String mode,
                String command,
                int exitCode,
                boolean timedOut,
                String stdout,
                String stderr,
                String startError) {
            this.mode = mode;
            this.command = command;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout;
            this.stderr = stderr;
            this.startError = startError;
        }

        public String renderForUi() {
            return "Launch result\n\n"
                    + "Mode: " + mode + "\n"
                    + "Command: " + command + "\n"
                    + "Exit code: " + exitCode + "\n"
                    + "Timed out: " + timedOut + "\n"
                    + "Start error: " + startError + "\n"
                    + "Stdout:\n" + stdout + "\n"
                    + "Stderr:\n" + stderr + "\n";
        }
    }

    public static Result runDirectElf(PayloadStager.Result staged) {
        String command = staged.file.getAbsolutePath();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (Exception e) {
            return new Result("direct-linux-elf", command, -127, false, "", "", e.toString());
        }

        try {
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            if (!finished) {
                process.destroyForcibly();
                return new Result("direct-linux-elf", command, -1, true, stdout, stderr, "");
            }
            return new Result("direct-linux-elf", command, process.exitValue(), false, stdout, stderr, "");
        } catch (Exception e) {
            process.destroyForcibly();
            return new Result("direct-linux-elf", command, -126, false, "", "", e.toString());
        }
    }

    private static String readFully(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}