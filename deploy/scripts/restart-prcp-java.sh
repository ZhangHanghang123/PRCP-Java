pkill -9 -f prcp-app-1.0.0; sleep 2
cd /home/almd/prcp-java
rm -f /tmp/prcp-java.log
nohup /usr/bin/java -Xms256m -Xmx512m -jar prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar </dev/null >/tmp/prcp-java.log 2>&1 &
echo $! > /tmp/prcp-java.pid; disown
sleep 15
tail -5 /tmp/prcp-java.log
echo "PID: $(cat /tmp/prcp-java.pid)"