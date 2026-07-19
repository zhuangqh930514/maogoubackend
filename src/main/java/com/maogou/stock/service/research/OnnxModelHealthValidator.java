package com.maogou.stock.service.research;

import java.nio.file.Path;

/** Validates that an imported ONNX artifact can be opened by the production inference runtime. */
public interface OnnxModelHealthValidator {

    void verify(Path modelPath);
}
