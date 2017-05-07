#!/system/bin/sh

# This is the GPIO pin connected to the lead on the switch labeled OUT
GPIOpin1=191  # 191 pin is 3 pin from the top on the left on XU3/XU4
echo "$GPIOpin1" > /sys/class/gpio/export
echo "in" > /sys/class/gpio/gpio$GPIOpin1/direction

# We do not use another PIN, instead we just supply power from USB
# because XU4 doesn't cut power from GPIO after shutdown.

requestSent=0 # Indicates whether we sent a request to shutdown

log -p d -t "BMW.PWR" "Script started"

while [ 1 = 1 ]
do
  power=$(cat /sys/class/gpio/gpio$GPIOpin1/value)
        
  log -p d -t "BMW.PWR" "PIN $GPIOpin1: $power"

  if [ $power = 0 ]; then
    # We have power
    if [ $requestSent = 1 ]; then
      # Cancel shutdown request
      log -p i -t "BMW.PWR" "Power ON again, cancel shutdown!"
      am startservice -n "org.bimdroid.bimservice/.BmwIBusService" -a org.bimdroid.ACTION_CANCEL_DELAYED_SHUTDOWN
      requestSent=0
    fi
  else
    sleep 0.5 # Just to make sure we really received power off signal.
    power=$(cat /sys/class/gpio/gpio$GPIOpin1/value)
    log -p d -t "BMW.PWR" "Poweroff signal received. Double check power: PIN $GPIOpin1: $power, requestSent: $requestSent"
    
    if [ $power = 1 ] && [ $requestSent = 0 ]; then
      log -p i -t "BMW.PWR" "Poweroff signal received from power supplier!"
      am start "org.bimdroid.bimservice/.ShutdownDialog" -a org.bimdroid.ACTION_SHUTDOWN_REQUEST
      requestSent=1
    fi
  fi

  sleep 1
done

