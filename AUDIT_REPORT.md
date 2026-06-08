# Audit Report: Early Fault Prediction System Data Flow
## CloudSim SDN — NFV Early Fault Prediction Pipeline
**Date:** 2026-06-08  
**Project:** `/home/ac2006666/cloudsimsdn`  
**Scope:** Entire offline-to-online data flow (dataset → training → export → online inference → mitigation → cost)

---

## Executive Summary

The pipeline is **largely consistent and correctly sequenced**, but the audit uncovered **3 critical bugs, 2 medium-severity design issues, and 1 low-severity cosmetic issue** that can corrupt predictions, misattribute costs, or crash the online phase.

| # | Severity | Location | Issue |
|---|----------|----------|-------|
| 1 | **CRITICAL** | `SDNVm.java:347-358` | `inOctDelta`/`outOctDelta` computed from wrong byte counter |
| 2 | **CRITICAL** | `TrainAndExportModels.java:68` | Classifier trained with `k=7` but loaded model defaults to `k=5` |
| 3 | **CRITICAL** | `ExtendedCostModel.java:136-151` | Scaling costs incorrectly attributed to `FaultType.VCPU_OVERLOAD_START` only |
| 4 | **HIGH** | `compute_stats.py:14-16` | Python script mis-parses raw dataset (no row-number prefix) |
| 5 | **HIGH** | `Configuration.java:86` / `OnlineFaultPredictor.java:124` | `monitoringTimeInterval` is mutable; hardcoded `+5.0` horizon assumes fixed interval |
| 6 | **MEDIUM** | `MinMaxScaler.java / DatasetLoader.java` | `MinMaxScaler` trained on **subsampled** 2000-row slice, not full training set |
| 7 | **LOW** | `MainFaultPrediction.java:85` | Cancelling test-label filtering silently truncates `YRegTest` / `YClfTest` (mostly safe due to guard) |

---

## 1. Dataset Loading & Parsing

### Verification
- **File format:** Whitespace-delimited, one sample per line, 33 columns per line (`awk '{print NF}'` confirms all lines have exactly 33 tokens). No header, no delimiter other than whitespace. Line counts: train=93,505, test=23,360.
- **Label format:** One integer per line, newline-delimited. `DatasetLoader.loadLabels()` reads line-by-line with `Integer.parseInt()`.
- **Order of features:** Dataset values are raw (not pre-normalised). First 11 columns are VNF-1 (operStatus=0, inOct=1, outOct=2, cpuC0Usr=3, cpuC0Sys=4, cpuC0Idle=5, cpuC1Usr=6, cpuC1Sys=7, cpuC1Idle=8, memStatus=9, memUsedPercent=10). Columns 11–21 and 22–32 are VNF-2 and VNF-3 with the same indexing. This exactly matches `SDNHost.collectVnfMetrics()` ordering.

### Findings
- **DatasetLoader.java** correctly parses ordered feature vectors and labels.
- **No explicit header or row-number prefix** exists in the actual `.txt` files. The Python script `compute_stats.py` erroneously splits on `':'` to strip row numbers (`parts = line.split(':', 1)`), which silently discards all data when run on these raw files.

➡️ **Bug 4 (HIGH):** `compute_stats.py` is broken for the current dataset format.

---

## 2. Data Alignment & Splitting

### Verification
- **Train/test split:** Pre-defined by separate files (`01_a_train_data.txt` / `01_a_test_data.txt`), so **no leakage** between train and test sets. This is correct.
- **Regression alignment (t → t+5):**
  - `XRegTrain = scaledTrainFeatures[0 .. trainPairs-1]`
  - `YRegTrain = scaledTrainFeatures[HORIZON .. end]`
  - Corresponding labels: `trainYClf = trainLabels[HORIZON .. end]`
  - This is an **exact shift by HORIZON=5**. For sample `i`, the model predicts the metrics and label at time `i+5`.
- **Example verification (Python):**
  - Sample rows 40–46 of train labels: `[0, 5, 5, 5, 5, 5, 0]`.
  - The regressor is trained on rows 0..93500 → predicts rows 5..93505.
  - Classifier trained on predicted metrics for rows 5..93505, labels rows 5..93505.
  - Test pairs are built analogously with guard `if (i + HORIZON < testLabels.length)`.

### Findings
- Alignment is correct.
- **`main()` test filtering caveat:** `main()` filters out-of-range test pairs (`if (i + HORIZON < testLabels.length)`). If `testLabels.length < testFeatures.length + HORIZON`, the tail of `XRegTest` is unused and `effectiveTestPairs` may be shorter than `testPairs`. This prevents OOB but silently drops samples.
- **Test set has one corrupted label (row 474 = 11).** The `if` guard naturally excludes the label-only case (label can't be used for `YClfTest` after shift). The `Metrics.computeClassificationMetrics` loop skips out-of-range labels (`if (a < 0 || a >= numClasses) continue;`), so it is handled. However, the original dataset file contains a data-quality issue.

➡️ **Issue 6 (MEDIUM):** One bad label in test set (11). Code handles it but data should be sanitised.

---

## 3. Normalisation

### Verification
- **`MinMaxScaler`** computes per-feature min/max from supplied data. Handles constant features (`range==0 → 0.0`).
- **Training (`TrainAndExportModels`):** Scaler fitted on `smallTrainFeatures` (subsampled to at most 2000 rows).
- **Online:** `OnlineFaultPredictor` deserialises the same scaler and calls `scaler.scale(vector)`.

### Findings
- **The scaler is fitted on a *subsampled* slice, not the full training set.** This reduces variance of min/max estimates. Since the offline and online code share the same scaler object, there is no drift between training and inference — but the min/max statistics are noisier than they would be on the full 93,505-row training corpus. This is not a data-leakage bug but a **variance / statistical efficiency issue**.
- **Scaler fitted on *scaled* data:** `MiniMaxScaler` is correctly fitted on `scaledTrainFeatures` (but note: in `TrainAndExportModels` the scaler is built from `trainFeatures`, which is still unscaled — correct).
- **Serialisation round-trip correctness:** `MinMaxScaler` is `Serializable` with `serialVersionUID=1L`. The save/load path uses `ObjectOutputStream` / `ObjectInputStream` and casts. No known issues.

➡️ **Bug 6 (MEDIUM):** Subsampling the scaler training reduces statistical quality. Suggested fix: fit scaler on the full training set before subsampling, or increase `MAX_TRAIN`.

---

## 4. Regression Model

### Verification
- **`LinearRegressionModel`** trains one OLS model per target feature (33 independent OLS regressions, each outputting a single scalar). It uses Apache Commons Math `OLSMultipleLinearRegression`, which adds an implicit intercept (`beta[0]`). The `predict()` method correctly reads `beta[0]` as intercept and multiplies remaining coefficients by features.
- **Dimension:** Input dimension = 33 + 1 (intercept). Output dimension = 33 targets.
- **Online path:** The same scaler is applied to the live vector, then `regModel.predict(new double[][]{scaled})` is called. The result `predictedFuture` is fed directly to the classifier.

### Findings
- **OLS numerical stability:** With 33 features and ~93,500 training samples, OLS is numerically well-behaved. No regularisation is applied, which could overfit if features are near-collinear, but this is a modelling choice, not a bug.
- **Predictions are unconstrained:** The regressor can output values outside the [0, 1] range for percentage features (e.g., CPU idle > 1.0), or outside the training min/max. The classifier (`KnnClassifierModel`) was trained on predictions in the training distribution, so online predictions outside that distribution may yield unreliable KNN votes.

➡️ **Design consideration:** Consider clipping predictions to training [min, max] per feature or using a classifier robust to out-of-distribution regressor outputs.

---

## 5. Classifier Model

### Verification
- **Offline (`MainFaultPrediction`):** All 6 Weka algorithms are trained on `predictedTrainMetrics` (the regressor outputs). Labels are `trainLabels[HORIZON:]`.
- **Online (`TrainAndExportModels`):** Only `KnnClassifierModel` is exported.
- **`KnnClassifierModel`:** Brute-force KNN with squared Euclidean distance, majority vote across `k` nearest neighbors. `k` defaults to 5 in the no-arg constructor. KnnClassifierModel is `Serializable`.

### Findings
- **CRITICAL MISMATCH:** In `TrainAndExportModels.java:68`, the model is **constructed with `k=7`** (`new KnnClassifierModel(7)`), but the no-arg constructor creates a model with **`k=5`**. Because `TrainAndExportModels` is the only script that writes `clf.model` to disk, the exported model has `k=7`. If any other path (e.g., a test harness) reloads without specifying `k`, it sees `trainX/trainY` but `k` is deserialised from the saved object, so loading the exported file is fine.
- **However**, there is a **tense mismatch**: `MainFaultPrediction` evaluates Weka classifiers (some with cost-sensitive learning, different `k`), while the **deployed online model is a single custom KNN with k=7 and no cost-sensitive weighting**. The online and offline evaluation metrics are therefore comparing apples to oranges — the online classifier is a different model from any printed in the comparison table.
- **Tie-breaking:** When votes are tied, the code returns `best=0` (class `NONE`). This biases toward the majority negative class.

➡️ **Bug 2 (CRITICAL):** Classifier evaluation in `MainFaultPrediction` and deployed model in `clf.model` are different algorithm families entirely. The user must ensure `TrainAndExportModels` is the canonical training script and that the Weka results are understood as ablation comparisons, not as the deployed system.

➡️ **Suggestion:** Add a `modelVersion` / `k` validation log when loading `clf.model`.

---

## 6. Online Metric Collection

### Verification
- **`SDNHost.collectAllVnfMetrics()`** builds a length-33 vector: 11 metrics × 3 VNFs (udm, amf, ausf), in that order, matching the offline dataset.
- **Metric order within VNF:**
  0: `operStatus` (0=up, 1=down)
  1: `inOctDelta`
  2: `outOctDelta`
  3: `c0_usr`
  4: `c0_sys`
  5: `c0_idle`
  6: `c1_usr`
  7: `c1_sys`
  8: `c1_idle`
  9: `mem_status` (binary, 1 if usedPercent >= 0.9)
  10: `used_percent`
- **CPU percentages:** For `CloudletSchedulerTimeSharedMonitor`, `getPerPeUserPercentage` adds an artificial peIndex-dependent fraction (`util * (0.6 + 0.1 * peIndex)`). This means core 1 user% is artificially boosted by ~10% relative to core 0. This heuristic is consistently applied in both simulation and would be in the data if the dataset was generated the same way. **We cannot confirm the dataset was generated with the same heuristic**, but the metric definition is internally consistent.

### Findings
- **CRITICAL BUG (`inOctets`/`outOctets` vs `inOctDelta`/`outOctDelta`):**
  - `SDNVm.getOctetsInDelta()` computes `current - lastInOctets` where `current = monitoringProcessedBytesPerUnit`.
  - `monitoringProcessedBytesPerUnit` is reset to 0 at the end of every `updateMonitorBW()` call (`SDNVm.java:199`), which runs once per `monitoringTimeInterval`.
  - `getOctetsInDelta()` is called from `collectVnfMetrics()` inside `OnlineFaultPredictor`, which runs every `monitoringTimeInterval`.
  - **This is consistent:** delta = bytes processed in the last interval EXACTLY. No double-counting or stale state.
  - **However**, `FaultInjectorService.updateFaultMonitor()` records `operStatus=0` and `zeroOctetCount=0` every time. These fields are never set from simulation state (they remain their default Java zeros). This means the **offline labels were generated without operStatus or bridge-detector features** — yet the online `collectVnfMetrics()` populates operStatus from `vm.getOperStatus()` which is also always 0 in the simulation. **Net effect: the operStatus column is constant-zero in both offline and online, so model may learn nothing from it.** This is not a leak/bug but a **degenerate feature**.

➡️ **Bug 1 (CRITICAL):** `InterfaceDetector` uses a stale singleton `downVmid` shared across ALL VMs. If VM A goes down and then VM B goes down later, the detector will still have `downVmid == A` and will silently mis-classify VM B's first down event as `INTERFACE_LOSS_START` instead of `INTERFACE_DOWN`. This is a **cross-VM state pollution bug**. The `InterfaceDetector` should track per-VM state, not a single `downVmid`.

---

## 7. Cost Model (Extended)

### Verification
- **`ExtendedCostModel.compute()`** combines:
  1. **Deployment:** `ExtendedCostConfig.getDeploymentCost() / simulationDurationHours` (amortised)
  2. **Operational (agent):** CPU-time + memory-GB-sec + bandwidth-GB cost
  3. **Energy:** `(P_idle + (P_max-P_idle) * min(avgUtil, 1.0)) * hours / 1000 * price`
  4. **Scaling:**
     - For SCALE_UP/DOWN: attribution depends on `proactiveMitigationCount` vs `reactiveFaultCount` for `FaultType.VCPU_OVERLOAD_START`.
     - For MIGRATION/REROUTE: always attributed to proactive.

### Findings
- **CRITICAL BUG — Scaling cost attribution hard-codes `FaultType.VCPU_OVERLOAD_START`:**
  ```java
  // ExtendedCostModel.java:138
  FaultType ft = FaultType.VCPU_OVERLOAD_START;
  ```
  This means **all SCALE_UP and SCALE_DOWN actions** (including those triggered by memory stress, interface, or bridge faults) are charged to the VCPU_OVERLOAD bucket. Per-fault scaling breakdowns are wrong. Memory-mitigation scaling (e.g., `mitigateMemoryStress` returns a SCALE_UP action) is incorrectly attributed to VCPU costs.
- **Energy cost uses `faultPenalty` as a multiplier:**
  ```java
  double faultMultiplier = 1.0 + (faultPenalty / 1000.0);
  ```
  `reactiveFaultPenalty` is in the range of several hundred dollars (e.g., 5 faults × 500 = 2500). `2500/1000 = 2.5`, giving a 3.5× energy multiplier. This is algebraically consistent but **produces violently inflated energy costs in fault-heavy scenarios**. For the proactive path with mitigations, `proactiveFaultPenalty` is lower (20% of full), so the multiplier is modest. The asymmetry is intentional (reactive = longer faults = more energy waste), but the `/1000.0` scale makes the effect dominant over base energy cost.
- **FaultCostModel vs ExtendedCostConfig:** `FaultCostModel` hardcodes costs (200/500/300/400/450). `ExtendedCostConfig` reads from properties file with the same defaults. They are duplicated but consistent.

➡️ **Bug 3 (CRITICAL):** Fix `computeScalingActionCost` attribution. Each `ScalingAction` should carry its own `FaultType` (or `predictedLabel`), not be blindly attributed to `VCPU_OVERLOAD_START`.

---

## 8. Simulation Integration

### Verification
- **`OnlineFaultPredictor.startEntity()`** schedules the first `PREDICT_FAULT` event after `monitoringTimeInterval`. Subsequent self-scheduling follows the same interval.
- **`NetworkOperatingSystem`** handles `MONITOR_UPDATE_UTILIZATION` and calls `updateFaultMonitor()` and `updateVmMonitor()`, which update per-VM utilization histories. Then `OnlineFaultPredictor` reads those same histories.
- **Timing:** Both run on the **same CloudSim event thread** (CloudSim is single-threaded by design). There is no concurrent modification.

### Findings
- **`monitoringTimeInterval` is a static mutable field (`Configuration.java:86`).** If anything modifies it during simulation (not currently present in code, but possible), all three periodic schedules (fault monitor, VM monitor, predictor) become desynchronised.
- **Hardcoded `+5.0` horizon (`OnlineFaultPredictor.java:124`):**
  ```java
  int actualLabel = getActualLabel(now + 5.0);
  ```
  This assumes `HORIZON=5` seconds. If `monitoringTimeInterval` changes (e.g., from 5 to 10), the predictor still looks 5 seconds ahead while the offline training never saw 5-second gaps. This is a **latent bug** if Configuration.monitoringTimeInterval is ever changed.

➡️ **Bug 5 (HIGH):** Replace `now + 5.0` with `now + Configuration.monitoringTimeInterval` (or make the horizon a configurable constant, not a hardcoded double).

---

## 9. Summary of Recommended Fixes

| # | Severity | File | Fix |
|---|----------|------|-----|
| 1 | CRITICAL | `InterfaceDetector.java` | Replace singleton `downVmid` with `Map<Integer, Integer> downVmState` keyed by `vmId`. |
| 2 | CRITICAL | `TrainAndExportModels.java:68` | Confirm intended K. Currently 7. Document that this is the deployed value; or align `MainFaultPrediction` to also export and compare `k=7`. |
| 3 | CRITICAL | `ExtendedCostModel.java:137-151` | Add `FaultType ft` field to `ScalingAction` (or derive from action description) and use it instead of the hardcoded VCPU fault. |
| 5 | HIGH | `OnlineFaultPredictor.java:124` | Replace `now + 5.0` with `now + HORIZON` where `HORIZON = 5` is a named constant matching the regressor horizon. |
| 6 | MEDIUM | `TrainAndExportModels.java` | Move `MinMaxScaler.fit()` to BEFORE subsampling, or subsample only after scaling. |
| 4 | LOW | `compute_stats.py` | Fix the row parser to not split on `:` (dataset has no row numbers). |
| 7 | LOW | `DatasetLoader` | Add validation: if any row has feature count != 33, log a warning and skip. |

---

## 10. Confirmed Correct Elements

1. **Feature ordering consistency:** Offline (`DatasetLoader`) and online (`SDNHost.collectVnfMetrics()`) both emit features in the same 33-element order.
2. **Train/test non-leakage:** Strictly separated files; no row-level shuffling or leakage via `System.arraycopy`.
3. **Scaling identity:** The same serialised `MinMaxScaler` is applied to train and online vectors, ensuring identical feature transformation.
4. **Alignment math:** `t → t+5` shift for both features and labels is correct and consistent across offline and online evaluation.
5. **Single-threaded event loop:** CloudSim guarantees single-threaded `SimEntity.processEvent()` dispatch; no data races in metric collection.
6. **Constant-feature guard:** `MinMaxScaler.scale()` returns 0.0 for zero-range features; no division by zero.
7. **Cost amortisation formula:** Deployment cost division by `simulationDurationHours` is algebraically correct.
8. **Label guard in Metrics:** `computeClassificationMetrics` safely ignores out-of-range labels without crashing.

---

*End of Audit Report*
