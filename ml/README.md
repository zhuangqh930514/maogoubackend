# 猫狗智投 V2 模型训练

训练器只接受 `AiTrainingDatasetService` 导出的不可变 JSONL 数据集。每行必须包含 `features`、`target`、`split`、样本/标签 ID 和双指纹，禁止直接用在线查询结果训练。

## 环境

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r ml/requirements.txt
```

## 训练

```bash
python ml/train_ranker.py \
  --dataset /var/lib/maogou/training/dataset-v1.jsonl \
  --output-dir /var/lib/maogou/models/challenger-v1 \
  --random-seed 930514
```

输出包含模型文件、ONNX 文件（转换依赖可用时）、特征清单和指标 JSON。只有通过质量门的模型才能注册为 `CANDIDATE`；它仍需完成 Walk-forward、组合回测和 Champion/Challenger 影子评估，不能自动替换当前 Champion。

Java 侧使用 `OrtOnnxInferenceRuntime` 加载 `.onnx` 文件，并由 `OnnxPredictionClient` 按特征清单排序和处理缺失值。生产模型路径应放在服务进程可读、不可被普通用户写入的目录。
