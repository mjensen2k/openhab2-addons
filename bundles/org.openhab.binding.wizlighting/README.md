# WiZ Lighting Binding

This binding integrates the Wiz Color Lighting devices .

## Supported Things

- WiZ Connected Lights Color and Tunable White


## Discovery

The devices are discovered only once when the app is installed for the first time.
Once the app is configured it allows creating additional invite codes that allow installation of the app on other ios or android devices.
In order to start auto discovery, create a file named wizlighting.token in Userdata folder with the 7 digit invite token and initiate discovery.
If this invite code is provided, we mimic the flow of an ios device to obtain list of devices from the wiz world api end point.

## Binding Configuration

The binding does not require any special configuration. The device should be connected to the same network.

## Thing Configuration

To configure a device manually we need its ip address, mac address and homeId. These can be found in the ios or android app.

Wifi Socket thing parameters:

| Parameter ID | Parameter Type | Mandatory | Description | Default |
|--------------|----------------|------|------------------|-----|
| macAddress | text | true | The MAC address of the bulb |  |
| ipAddress | text | true | The Ip of the bulb |  |
| homeId | text | true | Your WiZ homeId |  |
| updateInterval | integer | false | Update time interval in seconds to request the status of the bulb. | 60 |


E.g.

```
Thing wizlighting:wizBulb:lamp [ macAddress="accf23343c50", ipAddress="192.168.0.183", homeId=18529 ]
```

## Channels

The Binding supports the following channel:

| Channel Type ID | Item Type | Description                                          | Access |
|-----------------|-----------|------------------------------------------------------|--------|
| switch          | Switch    | Power state (ON/OFF)                                 | R/W    |
| color           | Color     | State, intensity, and color of the LEDs              | R/W    |
| scene           | String    | Preset light mode name to run                        | R/W    |
| speed           | Dimmer    | Speed of the color changes in dynamic light modes    | R/W    |
