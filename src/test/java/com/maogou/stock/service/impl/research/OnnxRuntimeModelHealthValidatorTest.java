package com.maogou.stock.service.impl.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxRuntimeModelHealthValidatorTest {

    // A compact logistic-regression ONNX artifact produced by the pinned local trainer.
    private static final String VALID_MODEL_BASE64 = "CAgSCHNrbDJvbm54GgYxLjE3LjAiB2FpLm9ubngoADIAOt4ECoEBCghmZWF0dXJlcxIIdmFyaWFibGUaB0ltcHV0ZXIiB0ltcHV0ZXIqLQoUaW1wdXRlZF92YWx1ZV9mbG9hdHM9AAAAAD0AAAAAPQrXJ0E9xylqP6ABBioeChRyZXBsYWNlZF92YWx1ZV9mbG9hdBUAAMB/oAEBOgphaS5vbm54Lm1sCnIKCHZhcmlhYmxlEgl2YXJpYWJsZTEaBlNjYWxlciIGU2NhbGVyKh8KBm9mZnNldD3Tx009PU8SHD49g7mpQT0JGIc/oAEGKh4KBXNjYWxlPZYkNz89CQGuPj01+Aw9PYNXSz+gAQY6CmFpLm9ubngubWwK9QEKCXZhcmlhYmxlMRIFbGFiZWwSDXByb2JhYmlsaXRpZXMaEExpbmVhckNsYXNzaWZpZXIiEExpbmVhckNsYXNzaWZpZXIqGQoQY2xhc3NsYWJlbHNfaW50c0AAQAGgAQcqOQoMY29lZmZpY2llbnRzPciN6Lw9PzkOPT30Q+q9PQLY8jw9yI3oPD0/OQ69PfRD6j09AtjyvKABBioZCgppbnRlcmNlcHRzPcSxqjo9xLGquqABBioSCgttdWx0aV9jbGFzcxgAoAECKh0KDnBvc3RfdHJhbnNmb3JtIghMT0dJU1RJQ6ABAzoKYWkub25ueC5tbBIgODNmMmY2OTkwNGIxNDFjMGFlNjFmOTAzMmQ0Zjc4OTVaGAoIZmVhdHVyZXMSDAoKCAESBgoACgIIBGIRCgVsYWJlbBIICgYIBxICCgBiHQoNcHJvYmFiaWxpdGllcxIMCgoIARIGCgAKAggCQg4KCmFpLm9ubngubWwQAUIECgAQDw==";

    @TempDir
    Path tempDir;

    @Test
    void opensValidOnnxAndRejectsCorruptBinary() throws Exception {
        OnnxRuntimeModelHealthValidator validator = new OnnxRuntimeModelHealthValidator();
        Path valid = tempDir.resolve("model.onnx");
        Files.write(valid, Base64.getDecoder().decode(VALID_MODEL_BASE64));
        Path corrupt = tempDir.resolve("corrupt.onnx");
        Files.writeString(corrupt, "not an onnx model");

        assertThatCode(() -> validator.verify(valid)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.verify(corrupt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ONNX");
    }
}
