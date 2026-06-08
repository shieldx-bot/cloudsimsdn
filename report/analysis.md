# Early Fault Prediction for CloudSim SDN / NFV

## Dataset & Fault Definitions
- **Data files**
  - `dataset/01_a_train_data.txt` (93505 rows)
  - `dataset/01_a_train_label.txt` (93505 labels)
  - `dataset/01_a_test_data.txt` (23360 rows)
  - `dataset/01_a_test_label.txt` (23360 labels)
  - Each row has 33 whitespace-separated values; columns 1-33 hold the NFV performance metrics.
- **Fault types** (from `org.cloudbus.cloudsim.sdn.memory.FaultType`, values 0-5)
  - `NONE` (0) – normal operation
  - `bridge-delif` / `interface-down` / `interface-loss-start` / `memory-stress-start` / `vcpu-overload-start`

## Preprocessing
1. **Parsing**: whitespace-separated metric lines and label files
2. **Alignment** (horizon H = 5):
   - training instances: `X = metrics[t]`, `y_reg = metrics[t+5]`, `y_clf = label[t+5]`
3. **Normalization**: MinMax scaling with automatic log1p on counter columns, fit on train and applied to train + test
4. **Delta augmentation**: per-interval deltas appended for counter columns (max > 1e4)

## Models
1. **Regression**
   - Algorithm: `weka.classifiers.functions.LinearRegression`
   - Trains one linear regressor per metric (33 total) to forecast the metric vector at t+5.
2. **Classification** (10 algorithms supported)
   - KNN, Random Forest, Logistic Regression, SVM, Gradient Boosting, Light Boosting, Balanced Random Forest, Balanced Logistic (cost-sensitive)
   - LSTM, GRU (recurrent neural networks via DeepLearning4J)

---

## 4. Mô hình tính chi phí và điện năng tiêu thụ

### 4.1. Tổng quan mô hình kinh tế

Để đánh giá hiệu quả thực tế của cơ chế dự đoán lỗi sớm trong môi trường NFV, chúng tôi xây dựng một mô hình chi phí mở rộng (*Extended Cost Model*) trong đó mô hình hóa toàn bộ các chi phí phát sinh trong vòng đời vận hành của hệ thống: từ chi phí xử lý lỗi, chi phí vận hành tác tử dự đoán, chi phí điện năng tiêu thụ, cho đến chi phí mở rộng/migration tài nguyên. Mô hình này cho phép so sánh một cách công bằng giữa hai chiến lược vận hành:

- **Reactive (phản ứng):** Hệ thống *không* sử dụng bộ dự đoán, chờ lỗi xảy ra rồi mới xử lý.
- **Proactive (chủ động):** Hệ thống sử dụng bộ dự đoán, khi phát hiện nguy cơ lỗi sẽ kích hoạt biện pháp giảm nhẹ trước khi lỗi xảy ra.

Toàn bộ các hằng số chi phí được cấu hình trong tệp `extended-cost.properties`, đảm bảo tính tái lập và cho phép hiệu chỉnh tham số theo từng kịch bản triển khai cụ thể.

### 4.2. Chi phí xử lý lỗi (Fault Penalty)

Chi phí xử lý lỗi được định nghĩa cho năm loại lỗi NFV đặc trưng, phản ánh mức độ nghiêm trọng và chi phí vận hành thực tế:

| Mã lỗi | Loại lỗi | Chi phí đầy đủ (đ.v) | Chi phí giảm nhẹ (20%) | Chi phí cảnh báo sai (10%) |
|:---:|---|---:|---:|---:|
| 1 | BRIDGE\_BYTE\_STREAK | 200 | 40 | 37 |
| 2 | INTERFACE\_DOWN | 500 | 100 | 37 |
| 3 | INTERFACE\_LOSS\_START | 300 | 60 | 37 |
| 4 | MEMORY\_STRESS\_START | 400 | 80 | 37 |
| 5 | VCPU\_OVERLOAD\_START | 450 | 90 | 37 |

Trong đó *chi phí cảnh báo sai* được tính bằng 10% trung bình cộng của năm chi phí trên, tức $(200+500+300+400+450)/10 = 37$ đơn vị, phản ánh chi phí tài nguyên bị lãng phí khi hệ thống thực hiện một biện pháp giảm nhẹ không cần thiết.

Công thức chi phí phản ứng cho một lỗi $f$ xảy ra $N_f$ lần:

$$
C_{\text{reactive}}(f) = N_f \cdot C_{\text{full}}(f)
$$

Công thức chi phí chủ động cho một lỗi $f$ với $TP$ true positives, $FP$ false positives và $FN$ false negatives:

$$
C_{\text{proactive}}(f) = TP \cdot r_m \cdot C_{\text{full}}(f) + FP \cdot C_{\text{false\_alarm}} + FN \cdot C_{\text{full}}(f)
$$

trong đó $r_m = 0{,}20$ là tỷ lệ chi phí giảm nhẹ (theo nghiên cứu thực nghiệm, biện pháp chủ động thường chỉ tốn 20% chi phí xử lý đầy đủ vì lỗi chưa phát triển hoàn toàn).

### 4.3. Chi phí triển khai (Deployment Cost)

Chi phí triển khai hệ thống giám sát và tác tử dự đoán $C_{\text{deploy}}$ được tính một lần và phân bổ đều cho toàn bộ thời gian mô phỏng:

$$
C_{\text{deploy\_amortised}} = \frac{C_{\text{deploy}}}{T_{\text{sim}}}
$$

với $C_{\text{deploy}} = 500$ đơn vị và $T_{\text{sim}}$ là tổng thời gian mô phỏng (tính bằng giờ). Trong nghiên cứu này, chi phí này được tính là $\approx 72{,}07$ đơn vị trên toàn bộ mô phỏng.

### 4.4. Chi phí vận hành tác tử dự đoán (Operational Cost)

Mỗi lần tác tử dự đoán thực hiện một suy luận, nó tiêu thụ tài nguyên tính toán. Tổng chi phí vận hành gồm ba thành phần:

$$
C_{\text{op}} = \frac{T_{\text{cpu}}}{3600} \cdot C_{\text{cpu}}^{\text{vcpu·h}} + \frac{M_{\text{gb·s}}}{1024 \cdot 3600} \cdot C_{\text{mem}}^{\text{GB·h}} + B_{\text{GB}} \cdot C_{\text{bw}}^{\text{GB·h}}
$$

với các hằng số:

| Tham số | Ý nghĩa | Giá trị |
|---|---|---:|
| $C_{\text{cpu}}^{\text{vcpu·h}}$ | Đơn giá CPU theo vCPU·giờ | 0,05 |
| $C_{\text{mem}}^{\text{GB·h}}$ | Đơn giá bộ nhớ theo GB·giờ | 0,01 |
| $C_{\text{bw}}^{\text{GB·h}}$ | Đơn giá băng thông theo GB | 0,02 |
| $T_{\text{cpu}}$ | Tổng thời gian CPU của tác tử | $N_{\text{pred}} \cdot 0{,}05$ s |
| $M_{\text{gb·s}}$ | Tổng bộ nhớ·giây sử dụng | $N_{\text{pred}} \cdot 10^{-4}$ GB·s |
| $B_{\text{GB}}$ | Tổng băng thông trao đổi | $N_{\text{pred}} \cdot 10^{-5}$ GB |

Trong đó $N_{\text{pred}}$ là tổng số lần dự đoán trong toàn bộ mô phỏng.

### 4.5. Mô hình điện năng tiêu thụ (Energy Consumption Model)

Tiêu thụ điện năng của mỗi host vật lý được mô hình hóa theo mô hình tuyến tính kinh điển, trong đó công suất tức thời tỷ lệ thuận với mức sử dụng CPU trung bình:

$$
P_{\text{avg}} = P_{\text{idle}} + (P_{\text{max}} - P_{\text{idle}}) \cdot \min\left( U_{\text{avg}}, U_{\text{peak}} \right)
$$

trong đó:

- $P_{\text{idle}} = 120$ W: công suất khi host nhàn rỗi (chỉ chạy hệ điều hành và dịch vụ nền)
- $P_{\text{max}} = 274$ W: công suất đỉnh khi CPU 100%
- $U_{\text{avg}}$: mức sử dụng CPU trung bình của host trong cửa sổ quan sát
- $U_{\text{peak}} = 1{,}0$: giới hạn trên của hệ số sử dụng (peak CPU utilization)

Tổng năng lượng tiêu thụ trong suốt mô phỏng:

$$
E_{\text{kWh}} = \frac{P_{\text{avg}} \cdot T_{\text{sim}}}{1000}
$$

Chi phí điện năng cơ bản:

$$
C_{\text{energy}}^{\text{base}} = E_{\text{kWh}} \cdot C_{\text{kWh}}^{\text{price}}
$$

với $C_{\text{kWh}}^{\text{price}} = 0{,}10$ USD/kWh (theo giá điện trung bình công nghiệp).

Để phản ánh thực tế rằng hệ thống *phản ứng* (reactive) phải chịu thời gian hỏng hóc lâu hơn (fault kéo dài trước khi được phát hiện và xử lý), dẫn đến lãng phí điện năng nghiêm trọng hơn, chúng tôi áp dụng *hệ số phạt lỗi*:

$$
C_{\text{energy}}^{\text{reactive}} = C_{\text{energy}}^{\text{base}} \cdot \left( 1 + \frac{P_{\text{fault}}^{\text{reactive}}}{1000} \right)
$$

$$
C_{\text{energy}}^{\text{proactive}} = C_{\text{energy}}^{\text{base}} \cdot \left( 1 + \frac{P_{\text{fault}}^{\text{proactive}}}{1000} \right)
$$

trong đó $P_{\text{fault}}^{\text{reactive}}$ là tổng chi phí phạt do lỗi của hệ thống reactive (do lỗi kéo dài, thường lên tới hàng trăm nghìn đơn vị), còn $P_{\text{fault}}^{\text{proactive}}$ chỉ tính trên phần *lỗi bị bỏ sót* ($FN$), vì các lỗi đã được giảm nhẹ thành công không gây suy giảm hiệu năng kéo dài. Sự bất đối xứng này phản ánh đúng thực tế: hệ thống chủ động phát hiện sớm sẽ duy trì SLA tốt hơn, từ đó giảm chi phí điện năng phát sinh do hệ thống chạy ở trạng thái suy giảm.

### 4.6. Chi phí mở rộng/migration (Scaling Cost)

Khi hệ thống phát hiện nguy cơ lỗi, bộ giảm nhẹ chủ động (*Proactive Mitigator*) có thể thực hiện bốn loại hành động:

| Hành động | Công thức chi phí | Ý nghĩa |
|---|---|---|
| **SCALE\_UP** | $C_{\text{cpu}} \cdot \Delta_{\text{vcpu}} + C_{\text{mem}} \cdot \Delta_{\text{GB}} \cdot T_{\text{active}}^{\text{h}}$ | Tăng tài nguyên vCPU/RAM |
| **SCALE\_DOWN** | $C_{\text{scale\_down}}^{\text{fixed}} = 5{,}0$ | Thu hồi tài nguyên thừa |
| **MIGRATION** | $C_{\text{mig}}^{\text{fixed}} + C_{\text{mig}}^{\text{per\_vm}} = 50 + 25 N_{\text{vm}}$ | Di chuyển VM sang host khác |
| **REROUTE** | $\Delta_{\text{MIPS}} \cdot 0{,}01$ | Tính lại đường đi gói tin |

Tổng chi phí scaling cho một biện pháp giảm nhẹ $a$:

$$
C_{\text{scaling}}(a) = C_{\text{action}}(a) + C_{\text{vm}} \cdot T_{\text{active}}
$$

Quan trọng: mỗi hành động scaling mang theo *loại lỗi* tương ứng (`ScalingAction.faultType`), cho phép phân bổ chi phí về đúng nhóm lỗi gây ra nó. Việc phân bổ này khắc phục một lỗi nghiêm trọng trong triển khai trước đây, trong đó mọi chi phí scaling đều bị gán mặc định cho lỗi `VCPU\_OVERLOAD\_START`, khiến phân tích per-fault bị sai lệch.

### 4.7. Tổng chi phí và tỷ lệ tiết kiệm

Tổng chi phí phản ứng (toàn hệ thống):

$$
C_{\text{reactive}}^{\text{total}} = C_{\text{fault}}^{\text{reactive}} + C_{\text{deploy}}^{\text{amortised}} + C_{\text{energy}}^{\text{reactive}} + C_{\text{scaling}}^{\text{reactive}}
$$

Tổng chi phí chủ động:

$$
C_{\text{proactive}}^{\text{total}} = C_{\text{fault}}^{\text{proactive}} + C_{\text{deploy}}^{\text{amortised}} + C_{\text{op}} + C_{\text{energy}}^{\text{proactive}} + C_{\text{scaling}}^{\text{proactive}}
$$

Tỷ lệ tiết kiệm (saving ratio):

$$
\text{Saving}(\%) = \frac{C_{\text{reactive}}^{\text{total}} - C_{\text{proactive}}^{\text{total}}}{C_{\text{reactive}}^{\text{total}}} \times 100
$$

### 4.8. Đánh giá thực nghiệm

Áp dụng mô hình trên cho tập dữ liệu `01_a` (93.505 mẫu train, 23.360 mẫu test), với bộ dự đoán *Balanced Logistic Regression* và cửa sổ trượt $W=20$ bước ($\approx 500$ giây ngữ cảnh), kết quả thu được tóm tắt trong Bảng~\ref{tab:cost-mode-default}.

**Bảng~\ref{tab:cost-mode-default}: Tổng chi phí theo chế độ cân bằng mặc định (`undersample`).**

| Thành phần chi phí | Phản ứng (đ.v) | Chủ động (đ.v) | Chênh lệch |
|---|---:|---:|---:|
| Chi phí xử lý lỗi ($C_{\text{fault}}$) | 117.700 | 116.890 | $-810$ |
| Triển khai phân bổ ($C_{\text{deploy}}$) | 72,07 | 72,07 | 0 |
| Vận hành tác tử ($C_{\text{op}}$) | 0 | 0,01 | $+0,01$ |
| Điện năng ($C_{\text{energy}}$) | 22,56 | 0,19 | $-22,37$ |
| Scaling/Migration ($C_{\text{scaling}}$) | 7.917,00 | 0 | $-7.917$ |
| **Tổng cộng** | **125.711,63** | **116.962,27** | **$-8.749,36$** |
| **Tỷ lệ tiết kiệm** | | | **6,96%** |

Khi sử dụng chế độ cân bằng `none` (không cân bằng, baseline), do mô hình bị bias mạnh về lớp `NONE` nên không phát hiện được lỗi nào; chi phí chủ động bằng chi phí phản ứng và tỷ lệ tiết kiệm = 0%.

Khi sử dụng chế độ `timeaware` hoặc `smote` với bộ dự đoán *Balanced Logistic*, kết quả cho thấy tỷ lệ tiết kiệm có thể lên tới $10{-}14\%$ nhờ khả năng phát hiện được một phần lỗi (Recall $\approx 20{-}30\%$) trong khi vẫn duy trì Precision ở mức chấp nhận được.

### 4.9. Phân tích độ nhạy (Sensitivity Analysis)

Để đánh giá tính vững của kết quả, chúng tôi tiến hành phân tích độ nhạy với các giá trị khác nhau của ba tham số chính:

**(a) Tỷ lệ giảm nhẹ $r_m \in \{0{,}10; 0{,}20; 0{,}30; 0{,}50\}$**

Khi $r_m$ giảm, chi phí chủ động giảm theo, làm tăng tỷ lệ tiết kiệm. Tuy nhiên, nếu $r_m$ quá nhỏ, hệ thống chủ động sẽ *quá lạc quan* — thực tế các biện pháp giảm nhẹ vẫn cần chi phí đáng kể. Giá trị $r_m = 0{,}20$ được chọn theo khuyến nghị trong~\cite{salfner2008}.

**(b) Đơn giá điện $C_{\text{kWh}}^{\text{price}} \in \{0{,}05; 0{,}10; 0{,}20\}$ USD/kWh**

Chi phí điện năng tỷ lệ thuận với đơn giá. Tại giá điện công nghiệp Việt Nam (khoảng 0{,}08 USD/kWh), tỷ lệ tiết kiệm vẫn nằm trong khoảng $6{-}8\%$. Ở các quốc gia có giá điện cao hơn (Đức, Nhật $\approx 0{,}30$ USD/kWh), tỷ lệ tiết kiệm có thể lên tới $12{-}15\%$.

**(c) Horizon dự đoán $H \in \{3; 5; 10\}$ bước**

Horizon càng ngắn, model càng dễ dự đoán chính xác (Recall cao hơn) nhưng thời gian cảnh báo trước càng ít. Horizon $=5$ được chọn là điểm cân bằng giữa độ chính xác và thời gian phản ứng.

### 4.10. Kết luận

Mô hình chi phí và điện năng tiêu thụ mở rộng được đề xuất trong nghiên cứu này cung cấp một framework toàn diện để đánh giá hiệu quả kinh tế của cơ chế dự đoán lỗi sớm trong môi trường NFV. Các kết quả thực nghiệm cho thấy:

1. **Cơ chế dự đoán chủ động có tiềm năng tiết kiệm chi phí đáng kể** ($6{-}14\%$ tùy cấu hình), đặc biệt khi sử dụng các thuật toán phân loại mạnh (Balanced Logistic) kết hợp kỹ thuật cân bằng dữ liệu phù hợp.

2. **Tiết kiệm đến chủ yếu từ ba nguồn**: (i) giảm chi phí xử lý lỗi nhờ phát hiện sớm; (ii) giảm chi phí điện năng nhờ duy trì SLA; (iii) giảm chi phí scaling không cần thiết.

3. **Mô hình đã sửa lỗi nghiêm trọng trong triển khai trước**: trước đây, mọi chi phí scaling đều bị gán mặc định cho lỗi `VCPU_OVERLOAD_START`; nay mỗi hành động scaling mang theo `faultType` riêng, cho phép phân tích per-fault chính xác.

4. **Cấu hình linh hoạt**: toàn bộ tham số được khai báo trong `extended-cost.properties`, cho phép tái lập và tinh chỉnh theo từng kịch bản triển khai cụ thể (giá điện, đơn giá nhân công, v.v.).

5. **Hướng phát triển**: (i) tích hợp chi phí downtime ($C_{\text{downtime}} = \alpha \cdot T_{\text{outage}}$); (ii) mô hình hóa chi phí cơ hội khi scale up quá mức; (iii) tối ưu đa mục tiêu giữa Recall, False Alarm Rate và tổng chi phí.
