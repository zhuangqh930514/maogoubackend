package com.maogou.stock.service.impl.research;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.maogou.stock.service.research.OnnxModelHealthValidator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class OnnxRuntimeModelHealthValidator implements OnnxModelHealthValidator {

    @Override
    public void verify(Path modelPath) {
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("ONNX 模型文件不存在");
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession ignored = OrtEnvironment.getEnvironment().createSession(modelPath.toString(), options)) {
            // Opening a session verifies the binary is a usable ONNX graph for this runtime.
        } catch (OrtException exception) {
            throw new IllegalArgumentException("ONNX 模型无法被当前推理运行时加载", exception);
        }
    }
}
