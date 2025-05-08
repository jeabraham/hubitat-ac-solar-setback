# Solar-Driven AC Setback

**A Hubitat App to Dynamically Adjust Thermostat Based on Solar Export**

## Overview

This Hubitat application monitors your solar power production, or net grid export, and dynamically adjusts your thermostat's
cooling setpoint to prioritize cooling your house with solar power. It:

* Starts monitoring from **T hours before sunset** until actual sunset.
* **Lowers** the cooling setpoint by Δ° when measured production, or export plus optional Air Conditioning (AC) power consumption, exceeds the **High threshold**.
* **Restores** the original setpoint when production, or export + AC power, drops below the **Low threshold** or at sunset (unless manually overridden).
* **Stops monitoring** if you manually change the setpoint.
* Supports **Celsius** (½° rounding) or **Fahrenheit** (1° rounding).
* Offers **optional** features: inverting power sign, applying in **AUTO** mode, include an **Air Conditioning (AC) Power Meter**, and **short-cycle protection** to avoid rapid setpoint changes.

## Prerequisites

* Hubitat Elevation Hub (2.2+ firmware recommended)
* A Power Meter device in Hubitat reporting in watts (solar production, export, or net usage)
* A Thermostat device supporting `capability.thermostat` and `coolingSetpoint`
* *(Optional)* A second Power Meter for your air conditioner load

## Installation

You have two options to install the app into Hubitat:

1. **Import from GitHub URL**

   * In the Hubitat Web UI, go to **Developer Tools → Apps Code**.
   * Click **Import** (or **Edit** if updating).
   * Paste the raw Groovy URL:

     ```
     https://raw.githubusercontent.com/jeabraham/hubitat-ac-solar-setback/main/solar-ac-setback.groovy
     ```
   * Click **Import**, then **Save** and **Publish → For me**.

2. **Manually copy/paste**

   * In **Developer Tools → Apps Code**, click **`+ New App`**.
   * Paste the full Groovy source code from your repository file (`solar-ac-setback.groovy`).
   * **Save** and **Publish → For me**.

After installing by either method, proceed to **Apps → Add User App → Solar-Driven AC Setback**, configure, and click **Save**.

Please watch your logs, especially in the first days of excess solar, to ensure the app is behaving appropriately and that your settings are effective. 

## Configuration

### Required Devices

* **Power Meter**: Select your solar or net-grid power meter.
* **Thermostat**: Select your cooling-capable thermostat.

### Optional Device

* **Air Conditioning (AC) Power Meter**: If provided, its power usage is **added** to the measured export power, giving you the net export you would have if your Air Conditioning (AC) unit were off. Use this only if you're measuring net usage (not solar production).

## Thresholds & Timing

* **T hours before sunset** (`offsetHours`): When to begin monitoring (e.g., `5.0`).
* **High threshold** (`thresholdHigh`): kW production/export above which the setpoint is lowered (e.g., `3.5`).
* **Low threshold** (`thresholdLow`): kW production/export below which the setpoint restores (e.g., `0.1`). Must be ≥0.5 kW below the High threshold. ⚠️ Caution: If you are measuring net export and don't have an AC power meter make sure the difference is greater than your AC power consumption, otherwise this app could cycle your AC and damage it. See Troubleshooting tips below.
* **Δ setpoint** (`tempChange`): Degrees to lower the setpoint (°C or °F).

## Options

* **Use Celsius** (`useCelsius`): Rounds setpoint to 0.5°C steps (uncheck for °F with 1° steps).
* **Invert power** (`invertPower`): Most net-power meters report negative values on export, enable this to invert the reading so export is positive.
* **Apply in AUTO** (`applyToAuto`): If checked, the setback also applies when the thermostat is in AUTO mode. Otherwise, only in COOL mode.
* **Re-check Interval** (`checkInterval`): How often (in minutes) to re-evaluate export if threshold not reached (default `15`).
* **Short-cycle protection** (`shortCycleMinutes`): Minimum delay (in minutes) between successive setpoint changes to prevent rapid AC cycling (default `5`).

## How It Works

1. **Startup**: At install/update the app validates thresholds and schedules two jobs: one to start monitoring at `sunset − offsetHours` and another to stop at actual sunset.
2. **Monitoring Loop**: Every `checkInterval` minutes (and on power events) it:

   * Reads `rawPower` from the main meter (and optional Air Conditioning (AC) load meter).
   * Applies `invertPower` if needed.
   * Calculates `measuredExport = rawPower + AC_load`.
   * If `measuredExport > High threshold` & (mode = COOL or AUTO+`applyToAuto`), it lowers the setpoint by Δ and remembers the original.
   * If already lowered and `measuredExport < Low threshold`, it restores the original setpoint.
   * **Short-cycle protection**: before any setpoint change, the app enforces a minimum wait of `shortCycleMinutes` before repeating any lower/restore action.
3. **Manual Override**: Any physical user change to the setpoint stops further monitoring until the next day.
4. **Sunset Reset**: At sunset, if still lowered (and not manually overridden), the original setpoint is restored.
5. **Sunrise Reset**: Each sunrise clears all state so the cycle can run again the next afternoon.

## Example Scenario

* **Offset** = 5 h before sunset, **High** = 3.5 kW, **Low** = 0.1 kW, **Δ** = 2°.
* **Air Conditioning (AC) load** = 2.5 kW.

1. At sunset−5 h, export = 0.8 kW → below High (even if air conditioner is on 0.8 kW + 2.5 kW < 3.5kW), keep checking.
2. Cloud passes, export jumps to 1.2 kW; if AC is on `measuredExport = 1.2 + 2.5 = 3.7 kW` → >3.5 kW → lower setpoint by 2°.
3. Cloud returns, export drops to 0.05 kW; `measuredExport = 0.05 + 2.5 = 2.55 kW` → still >Low threshold (0.1 kW) → no restore until below 0.1 kW.
4. The air conditioner successfully cools the house the extra 2°, so turns off, AC_load goes to zero. Export increases by 2.5 kW but export + AC_load does not change.
5. As the sun lowers, the export again falls to 0.05 kW; `measuredExport = 0.05 + 0 = 0.05 kW` → <0.1 kW → restore original setpoint.
5. Sunset arrives: if still lowered, setpoint resets automatically, monitoring stops.

## Troubleshooting

* **Validation Errors**: If the Low threshold is too close to High (<0.5 kW), the app will refuse to install/update.
* **Logging**: Use Hubitat’s **Logs** with INFO/DEBUG levels to trace scheduling and setpoint actions.
* **Capability Check**: Ensure your thermostat driver supports `coolingSetpoint` and `thermostatMode`. The AC-meter feature also requires a powerMeter capability.
* **Cycling**: If you're measuring net export and you don't have a measurement of your air conditioner's power, ensure the difference between `Low Threshold` and `High Threshold` is greater than your air conditioner's power consumption, otherwise the app could start cycling, which could damage it. (If the air conditioner turns on due to the lowered setpoint, power export will reduce by however much power your air conditioner uses, and you don't want the setpoint to be immediately restored.)

## License, Limit of Liability & Credits

Developed by **John Abraham**, leveraging assistance from **ChatGPT**. Feel free to modify and share under the terms of the **MIT License**.
Note the MIT license limits liability, even with the short cycling feature, this app could damage your air conditioner
by cycling it too often, ensure your thresholds are far enough apart to account for your
air conditioner's power (if you aren't monitoring it) and for clouds coming and going.  