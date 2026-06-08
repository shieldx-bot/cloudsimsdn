java -Xmx6g -Dmax.train=50000 -Dmax.test=5000 -Dfaultpred.model=LSTM \
  -cp target/cloudsimsdn-1.0.jar \
  org.cloudbus.cloudsim.sdn.faultpred.MainFaultPrediction  


  mvn -q -DskipTests package


  # chạy 1 model
-Dfaultpred.model=KNN

# chạy nhiều model
-Dfaultpred.models=KNN,RF,GRU

# có thể dùng alias ngắn
-Dfaultpred.models=rf,gb,brf,bl,lstm,gru


java -Xmx8g -Dmax.train=180000 -Dmax.test=70000 -Dfaultpred.window=20 -Dfaultpred.models=all -cp target/cloudsimsdn-1.0-with-dependencies.jar org.cloudbus.cloudsim.sdn.faultpred.MainFaultPrediction



java -Xmx8g -Dmax.train=200000 -Dmax.test=70000 -Dfaultpred.window=20 -Dfaultpred.balance=none -Dfaultpred.models=rf -cp target/cloudsimsdn-1.0-with-dependencies.jar org.cloudbus.cloudsim.sdn.faultpred.MainFaultPrediction