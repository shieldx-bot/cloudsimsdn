#!/bin/bash
set -e
cd /home/ac2006666/cloudsimsdn

CLASSPATH="target/classes:$(find /home/ac2006666/.m2/repository -name 'cloudsim-4.0.jar' 2>/dev/null):$(find /home/ac2006666/.m2/repository -name 'commons-math3-3.6.1.jar' 2>/dev/null):$(find /home/ac2006666/.m2/repository -name 'guava-17.0.jar' 2>/dev/null):$(find /home/ac2006666/.m2/repository -name 'json-simple-1.1.1.jar' 2>/dev/null):$(find /home/ac2006666/.m2/repository -name 'weka-stable-3.8.6.jar' 2>/dev/null)"

echo "Recompiling changed sources..."
javac -d target/classes -cp "$CLASSPATH" \
  src/main/java/org/cloudbus/cloudsim/sdn/faultpred/pipeline/LinearRegressionModel.java \
  src/main/java/org/cloudbus/cloudsim/sdn/faultpred/TrainAndExportModels.java \
  2>&1 | tail -5

rm -f models/reg.model models/clf.model

echo "Running training..."
mkdir -p models
java -Xmx2g -cp "$CLASSPATH" org.cloudbus.cloudsim.sdn.faultpred.TrainAndExportModels 2>&1 | tail -25

echo "Done. Files in models/:"
ls -la models/
