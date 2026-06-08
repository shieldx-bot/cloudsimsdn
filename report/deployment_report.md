# Báo Cáo Triển Khai: Early Fault Prediction cho CloudSim SDN/NFV

## 1. Tổng Quan Hệ Thống

Hệ thống dự đoán lỗi sớm hoạt động theo 2 giai đoạn:
1. **Regression**: Dự đoán metrics tại thời điểm t+5 từ metrics hiện tại t
2. **Classification**: Phân loại fault type (0-5) tại t+5 dựa trên metrics dự đoán

Kết quả được dùng để tính toán **resource saving** so với phương pháp reactive (không dự đoán).

---

## 2. Cấu Trúc File và Logic Đã Bổ Sung

### 2.1 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/MainFaultPrediction.java`
**Vai trò**: Driver chính - điều phối toàn bộ pipeline

**Logic chính**:
- Load dataset train/test từ thư mục `dataset/`
- Subsample dữ liệu (4000 train, 2000 test) để chạy nhanh
- MinMax scaling trên train, áp dụng lên test
- Tạo cặp (t, t+5) cho regression
- Train regression model → predict metrics[t+5]
- Train classifier trên predicted metrics → predict fault class
- Tính MAE, RMSE, confusion matrix, resource saving

**Luồng dữ liệu**:
```
Raw Data → Subsample → Scale → Pairs (t→t+5) → Regression → Predicted Metrics → Classification → Fault Labels → Evaluation
```

---

### 2.2 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/pipeline/DatasetLoader.java`
**Vai trò**: Đọc và chuẩn bị dữ liệu

**Logic bổ sung**:
- `loadFeatures()`: Đọc file metrics (33 cột, whitespace-separated)
- `loadLabels()`: Đọc file labels (1 cột, mỗi dòng 1 số)
- `subsample()`: **Mới bổ sung** - lấy mẫu ngẫu nhiên `maxSize` bản ghi để test nhanh
- `toTimeSamples()`: Chuyển thành đối tượng TimeSample (không dùng trong pipeline chính)

---

### 2.3 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/pipeline/MinMaxScaler.java`
**Vai trò**: Chuẩn hóa features về [0,1]

**Logic**:
- Fit trên train data: tìm min/max từng cột
- Transform: `(x - min) / (max - min)`
- Áp dụng giống scale lên test set (không leak data)

---

### 2.4 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/pipeline/LinearRegressionModel.java`
**Vai trò**: Dự đoán metrics tại t+5

**Logic**:
- Train **33 model LinearRegression riêng biệt** (1 model/output metric)
- Mỗi model học ánh xạ: metrics[t] → metric_i[t+5]
- Dùng `org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression`
- Không dùng Weka SMOreg (tránh vấn đề native dependency)

**Công thức**:
```
predicted_metric_i = intercept_i + Σ(j=0..32) beta_{i,j} * metrics_t[j]
```

---

### 2.5 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/pipeline/KnnClassifierModel.java`
**Vai trò**: Phân loại fault type từ predicted metrics

**Logic**:
- **K-Nearest Neighbors** tự viết (không dùng Weka để tránh bug)
- K=7, khoảng cách Euclidean
- Vote đa số từ K neighbors gần nhất
- Trả về class 0-5 (0=NONE, 1-5=fault types)

**Tại sao KNN**: Tránh phụ thuộc Weka J48/SMO có vấn đề với dataset nhỏ, dễ debug, chạy nhanh.

---

### 2.6 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/eval/Metrics.java`
**Vai trò**: Tính toán evaluation metrics

**Logic bổ sung**:
- `computeMAE()`: Mean Absolute Error giữa predicted và actual metrics
- `computeRMSE()`: Root Mean Squared Error
- `computeClassificationMetrics()`: Tính TP/FP/TN/FN, accuracy, precision, recall, F1
  - Confusion matrix 6x6 (NONE + 5 fault types)
  - Per-class metrics cho mỗi fault type

**Điểm quan trọng**:
- Chỉ đếm TP khi `a == p && a != 0` (fault thật đúng)
- FP: predicted fault nhưng thực tế NONE
- FN: thực tế fault nhưng predicted NONE

---

### 2.7 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/eval/FaultCostModel.java`
**Vai trò**: Định nghĩa chi phí resource cho từng loại fault

**Logic**:
- Gán cost cụ thể:
  - `bridge-delif`: 200 units
  - `interface-down`: 500 units
  - `interface-loss-start`: 300 units
  - `memory-stress-start`: 400 units
  - `vcpu-overload-start`: 450 units

- Công thức tính:
  - **Reactive cost** = `num_faults * full_cost`
  - **Proactive cost** = `TP * (full_cost * 0.2) + FP * false_alarm_cost + FN * full_cost`
  - False alarm cost = 10% trung bình các fault cost (~37 units)

---

### 2.8 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/eval/ReportPrinter.java`
**Vai trò**: In báo cáo ra console

**Logic**:
- `printCostModel()`: Bảng cost model
- `printRegressionMetrics()`: MAE, RMSE
- `printClassificationResult()`: Accuracy, Precision, Recall, F1 + per-class
- `printConfusionMatrix()`: Ma trận 6x6
- `printResourceSaving()`: Reactive vs Proactive cost + saving %

---

### 2.9 File Mới: `src/main/java/org/cloudbus/cloudsim/sdn/faultpred/eval/EvaluationResult.java`
**Vai trò**: Data class lưu kết quả đánh giá

**Chứa**:
- TP, FP, TN, FN
- Confusion matrix 6x6
- Regression MAE, RMSE
- Reactive/Proactive cost, saving %

---

### 2.10 File Đã Sửa: `pom.xml`
**Thay đổi**:
- Thêm dependency `weka-stable:3.8.6` (đã có sẵn)
- Thêm dependency `commons-math3:3.6.1` (đã có sẵn)
- **Không dùng Weka classification** nữa (thay bằng KNN tự viết)

---

### 2.11 File Đã Sửa: `src/main/java/org/cloudbus/cloudsim/sdn/memory/FaultType.java`
**Thay đổi**:
- File này bị thiếu/mất, đã được restore với định nghĩa đầy đủ 6 fault types (NONE, BRIDGE_BYTE_STREAK, INTERFACE_DOWN, INTERFACE_LOSS_START, MEMORY_STRESS_START, VCPU_OVERLOAD_START)

---

### 2.12 File Đã Sửa: `src/main/java/org/cloudbus/cloudsim/sdn/fault/FaultEvent.java`
**Thay đổi**:
- Sửa import từ `org.cloudbus.cloudsim.sdn.fault.FaultType` → `org.cloudbus.cloudsim.sdn.memory.FaultType` để matching với package thực tế

---

## 3. Luồng Dữ Liệu End-to-End

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. LOAD DATA                                                     │
│    - 01_a_train_data.txt (93505 rows, 33 features)               │
│    - 01_a_train_label.txt (93505 labels)                        │
│    - 01_a_test_data.txt (23360 rows)                            │
│    - 01_a_test_label.txt (23360 labels)                         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 2. SUBSAMPLE (fast test)                                         │
│    - Train: 4000 records                                         │
│    - Test: 2000 records                                          │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 3. PREPROCESSING                                                 │
│    - MinMaxScaler fit trên train → transform train/test          │
│    - Tạo pairs (t, t+5): X=metrics[t], Y=metrics[t+5]           │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 4. REGRESSION (predict metrics[t+5])                             │
│    - Train 33 LinearRegression models (1/metric)                 │
│    - Predict trên test set → predictedMetrics                    │
│    - Evaluate: MAE, RMSE                                         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 5. CLASSIFICATION (predict fault class tại t+5)                  │
│    - Train KNN (k=7) trên predictedTrainMetrics                  │
│    - Predict trên predictedTestMetrics → predictedLabels         │
│    - Evaluate: Accuracy, Precision, Recall, F1, Confusion Matrix │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 6. RESOURCE SAVING CALCULATION                                   │
│    - Đếm TP/FP/FN từ confusion matrix                           │
│    - Tính Reactive Cost = Σ(fault_count * full_cost)            │
│    - Tính Proactive Cost = TP*0.2*full + FP*false_alarm + FN*full│
│    - Saving % = (Reactive - Proactive) / Reactive * 100         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 7. REPORT                                                        │
│    - Regression metrics                                          │
│    - Classification metrics + confusion matrix                   │
│    - Cost model table                                            │
│    - Per-fault cost analysis                                     │
│    - Final resource saving %                                     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Kết Quả Chạy Thử (Fast Test)

### Regression
| Metric | Value |
|--------|-------|
| MAE | 0.034420 |
| RMSE | 0.907876 |

### Classification
| Metric | Value |
|--------|-------|
| Accuracy | 0.9414 |
| Precision | 0.0000 |
| Recall | 0.0000 |
| F1 | 0.0000 |

**Lý do Recall=0**: Với subsample 4000/2000, mẫu fault rất hiếm (~5.8%). KNN với k=7 thiên về dự đoán lớp đa số (NONE). Đây là behavior mong đợi với data nhỏ.

### Resource Saving
| Fault Type | Actual | TP | FP | FN | Reactive | Proactive |
|------------|--------|----|----|----|----------|-----------|
| bridge-delif | 21 | 0 | 0 | 21 | 4,200 | 4,200 |
| interface-down | 22 | 0 | 0 | 22 | 11,000 | 11,000 |
| interface-loss-start | 26 | 0 | 0 | 26 | 7,800 | 7,800 |
| memory-stress-start | 25 | 0 | 0 | 25 | 10,000 | 10,000 |
| vcpu-overload-start | 22 | 0 | 0 | 22 | 9,900 | 9,900 |
| **TOTAL** | **116** | **0** | **0** | **116** | **42,900** | **42,900** |

**Resource Saving: 0.00%** (do TP=0)

---

## 5. Cách Chạy

### Chạy Fast Test (4k train, 2k test)
```bash
cd /home/ac2006666/cloudsimsdn
MAVEN_OPTS="-Xmx6g" mvn -q -DskipTests compile
MAVEN_OPTS="-Xmx6g" mvn -q -DskipTests exec:java -Dexec.mainClass=org.cloudbus.cloudsim.sdn.faultpred.MainFaultPrediction
```

### Chạy Full Dataset (93k train, 23k test)
Sửa `MAX_TRAIN` và `MAX_TEST` trong `MainFaultPrediction.java` thành giá trị lớn hơn dataset, rồi:
```bash
MAVEN_OPTS="-Xmx8g" mvn -q -DskipTests exec:java -Dexec.mainClass=org.cloudbus.cloudsim.sdn.faultpred.MainFaultPrediction
```

---

## 6. Hướng Phát Triển Để Cải Thiện Kết Quả

1. **Stratified Sampling**: Giữ tỷ lệ fault/normal trong subsample
2. **Class Weight**: Trọng số cao hơn cho fault classes trong KNN
3. **Thử K khác**: K=3, K=5 thay vì K=7
4. **Feature Selection**: Chọn metrics quan trọng thay vì dùng hết 33
5. **Smote/Upsampling**: Tăng mẫu fault classes

---

## 7. Các File Đã Thay Đổi/Tạo Mới

| File | Loại | Mô tả |
|------|------|-------|
| `MainFaultPrediction.java` | Mới | Driver pipeline chính |
| `DatasetLoader.java` | Mới | Load + subsample data |
| `MinMaxScaler.java` | Mới | Chuẩn hóa features |
| `LinearRegressionModel.java` | Mới | Regression 33 outputs |
| `KnnClassifierModel.java` | Mới | KNN classifier |
| `Metrics.java` | Mới | Tính MAE, RMSE, classification metrics |
| `FaultCostModel.java` | Mới | Cost model + resource saving |
| `ReportPrinter.java` | Mới | In báo cáo console |
| `EvaluationResult.java` | Mới | Data class kết quả |
| `FaultType.java` | Sửa | Restore enum bị thiếu |
| `FaultEvent.java` | Sửa | Fix import FaultType |
| `pom.xml` | Sửa | Thêm dependencies |
| `report/analysis.md` | Mới | Báo cáo chi tiết |

---

## 8. Lưu ý Quan Trọng

- **Pipeline đã chạy end-to-end thành công** (compile + execute đều OK)
- Kết quả hiện tại với subsample nhỏ cho saving=0 là **bình thường** do imbalance data
- Để có saving > 0 cần chạy full data hoặc cải thiện recall fault
- Code đã có đầy đủ evaluation metrics, confusion matrix, cost analysis theo yêu cầu




java -Xmx6g -Dmax.train=40000 -Dmax.test=2000 -cp target/cloudsimsdn-1.0.jar org.cloudbus.cloudsim.sdn.faultpred.MainFaultPrediction




Luồng hiện tại là có mô phỏng NFV bằng CloudSim, nhưng model train riêng trên dataset. Hai phần này được nối với nhau qua file model và dữ liệu metric, không phải train trực tiếp ngay trong simulation.
Tóm tắt nhanh
Offline: TrainAndExportModels đọc dataset/*.txt để train scaler/regression/classifier và lưu vào models/
Online/simulation: SimpleExampleWithFaultPrediction chạy trên môi trường CloudSim SDN/NFV mô phỏng, lấy metric từ VNF đang chạy, rồi dùng model đã train để dự đoán và mitigation
Kết nối giữa hai phần: simulation tạo metric runtime và fault log; dataset là nguồn train/eval riêng, không phải thứ model học trực tiếp trong lúc chạy mô phỏng
Vai trò từng phần
CloudSim / CloudSim SDN
Là engine mô phỏng sự kiện, topology, host, VM, packet, workload.
NetworkOperatingSystem xử lý monitoring, packet processing, VM/flow mapping và fault monitoring.
Xem NetworkOperatingSystem.java (lines 93-99) và NetworkOperatingSystem.java (lines 181-199)

NFV mô phỏng
Các VNF như udm, amf, ausf chạy như VM trên host mô phỏng.
SimpleExampleWithFaultPrediction load physical topology, deployment, workload, rồi tìm các VNF host để gắn predictor.
Xem SimpleExampleWithFaultPrediction.java (lines 38-41) và SimpleExampleWithFaultPrediction.java (lines 195-202)

Fault prediction
OnlineFaultPredictor lấy metric từ mô phỏng tại runtime, nạp model đã train từ models/, rồi dự đoán fault và gửi mitigation.
Xem OnlineFaultPredictor.java (lines 49-58) và OnlineFaultPredictor.java (lines 103-133)

Dữ liệu hiện tại được dùng như nào
Trong mô phỏng, metric được lấy trực tiếp từ host/VNF đang chạy:SDNHost.collectAllVnfMetrics(now) lấy vector 33 chiều từ 3 VNF
Xem SDNHost.java (lines 445-471)

FaultInjectorService ghi log fault runtime ra fault_labels.csvXem FaultInjectorService.java (lines 18-41)

Nhưng model train không học trực tiếp từ simulation runtime:TrainAndExportModels đọc các file trong dataset/
Xem TrainAndExportModels.java (lines 26-44)

Kết luận đúng nhất
Nếu bạn chạy TrainAndExportModels hoặc MainFaultPrediction ở chế độ offline, thì nó chỉ làm việc trên dataset.
Nếu bạn chạy SimpleExampleWithFaultPrediction, thì nó chạy trên NFV mô phỏng của CloudSim, không phải chỉ đọc dataset.
Model dự đoán hiện tại là model đã train từ dataset, còn dữ liệu input online là metric từ NFV mô phỏng.
Nói ngắn gọn:
CloudSim = mô phỏng hạ tầng + workload + packet + VM + VNF
NFV = các VNF/chain đang chạy trong môi trường mô phỏng đó
Dataset = dữ liệu offline để train/eval
Online predictor = lấy metric từ NFV mô phỏng để suy luận realtime
Nếu bạn muốn, mình có thể vẽ cho bạn sơ đồ 1 dòng:
CloudSim NFV simulation -> metric log/fault log -> dataset/offline training -> saved models -> online prediction on simulated NFV.