/**
 *  Solar-Driven AC Setback (with manual-override, sunset restore, power-invert & AC-meter)
 *
 *  Author:  John Abraham
 *  Date:    2025-04-28
 *
 *  Continuously adjust thermostat cooling setpoint by Δ° whenever
 *  measured power (plus optional AC consumption) crosses E/E2 kW,
 *  from T hours before sunset through sunset.
 *  Stops monitoring if you manually change the setpoint.
 *  Restores at sunset if still lowered (unless manually overridden).
 *  Supports Celsius/°F, optional power inversion, optional AUTO mode,
 *  and now optional AC power metering.
 *
 *  All props to ChatGPT.  This code has not yet been tested in production.
 */

definition(
    name: "Solar-Driven AC Setback",
    namespace: "jeabraham",
    author: "John Abraham",
    description: "Adjust setpoint by Δ° when solar > E / < E2, from sunset−T until sunset, with manual-override stop, sunset-restore, C/F, invert-power, optional AUTO & AC-meter support.",
    category: "Green Energy",
    iconUrl:   "",
    iconX2Url: ""
)

preferences {
    section("About Solar-Driven AC Setback") {
        paragraph """
This app monitors your solar production or net-grid power export starting T hours before sunset.

• If measured power > High threshold, it lowers your cooling setpoint by Δ°.
• If measured power < Low threshold, or at actual sunset, it restores the setpoint (unless you've manually changed it).

Options include Celsius/°F, inverting sign (for meters that report negative on export), applying in AUTO mode, and an optional AC power meter.
If an AC meter is provided, its consumption (W) is **added** to measured value—so the comparison is with what you’d export if the AC were off.
Leave the AC meter blank if you’re monitoring raw solar production.
"""
    }
    section("Select your devices") {
        input "solarMeter", "capability.powerMeter",
            title: "Power Meter (W) – solar production, or net grid usage/production", required: true
        input "thermostat", "capability.thermostat",
            title: "Thermostat (must support coolingSetpoint)", required: true
    }
    section("Optional devices") {
        input "acMeter", "capability.powerMeter",
            title: "Air conditioner power meter (optional; W)", required: false
    }
    section("Thresholds & offsets") {
        input "offsetHours",   "decimal",
            title: "T hours before sunset",    defaultValue: 4.0, required: true
        input "thresholdHigh","decimal",
            title: "High threshold E (kW)",   defaultValue: 1.0, required: true
        input "thresholdLow", "decimal",
            title: "Low threshold E2 (kW)",  defaultValue: 0.0, required: true
        input "tempChange",   "decimal",
            title: "Δ setpoint (°)",          defaultValue: 2.0, required: true
    }
    section("Options") {
        input "useCelsius",  "bool",
            title: "Use Celsius (uncheck for °F)", defaultValue: true
        input "invertPower","bool",
            title: "Invert power sign (e.g. if meter reports negative on power export)", defaultValue: false
        input "applyToAuto","bool",
            title: "Apply setback in AUTO mode (unchecked → only in COOL)", defaultValue: false
        input "checkInterval","number",
            title: "Re-check every TD minutes until sunset", defaultValue: 15, required: true
    }
}

def installed() {
    validateSettings()
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    validateSettings()
    initialize()
}

private void validateSettings() {
    def high = thresholdHigh.toDouble()
    def low  = thresholdLow.toDouble()
    if (high - low < 0.5) {
        log.error "${app.label}: Validation failed—Low threshold must be ≥0.5 kW below High threshold."
        throw new IllegalArgumentException("❌ Low threshold E2 must be at least 0.5 kW lower than High threshold E.")
    }
}

private initialize() {
    log.debug "initialize(): resetting state & subscriptions"
    state.lowered          = false
    state.monitoring       = false
    state.originalSetpoint = null
    state.loweredSetpoint  = null
    state.sunsetTime       = null

    subscribe(solarMeter,    "power",           onPowerEvent)
    subscribe(thermostat,    "thermostatMode",  onThermostatMode)
    subscribe(thermostat,    "coolingSetpoint", onSetpointChange)
    subscribe(location,      "sunriseTime",     resetDaily)

    scheduleDailyJobs()
}

private scheduleDailyJobs() {
    def sun    = getSunriseAndSunset()
    def sunset = sun.sunset
    state.sunsetTime = sunset.time
    log.debug "Scheduling startMonitoring & stopMonitoring around sunset=${sunset}"

    def startAt = new Date(sunset.time - (offsetHours.toDouble() * 3600 * 1000).toLong())
    if (startAt.time <= now()) {
        log.warn "Sunset−T is already past; starting monitoring immediately"
        startMonitoring()
    } else {
        schedule(startAt, "startMonitoring")
        log.debug "Scheduled startMonitoring() at ${startAt}"
    }

    schedule(sunset, "stopMonitoring")
    log.debug "Scheduled stopMonitoring() at ${sunset}"
}

def startMonitoring() {
    state.monitoring = true
    log.info  "▶︎ startMonitoring(): ON until sunset (${new Date(state.sunsetTime)})"
    thresholdCheck()  // initial check & loop
}

def stopMonitoring() {
    log.info  "■ stopMonitoring(): OFF at sunset (${new Date()})"
    state.monitoring = false
    unschedule("thresholdCheck")

    if (state.lowered) {
        def curr = thermostat.currentValue("coolingSetpoint").toString().toDouble()
        if (curr == state.loweredSetpoint) {
            log.info "● Sunset restore: restoring original setpoint ${state.originalSetpoint}${unit()}"
            restoreSetpoint()
        } else {
            log.warn "⚠ Sunset restore skipped (manual override detected: ${curr}${unit()})"
        }
    }
}

def onPowerEvent(evt) {
    if (state.monitoring) {
        handlePower(evt.value.toString().toDouble())
    }
}

def thresholdCheck() {
    if (!state.monitoring) {
        log.trace "thresholdCheck(): skipped (monitoring off)"
        return
    }
    def rawPower = solarMeter.currentValue("power").toString().toDouble()
    handlePower(rawPower)
    if (now() < state.sunsetTime) {
        runIn(checkInterval.toInteger() * 60, "thresholdCheck")
        log.debug "thresholdCheck(): next in ${checkInterval} min"
    } else {
        log.debug "thresholdCheck(): past sunset, stopping checks"
    }
}

private handlePower(rawPower) {
    log.trace "handlePower(): rawPower=${rawPower} W"
    def power = invertPower ? -rawPower : rawPower
    if (invertPower) log.debug "  → inverted to ${power} W"

    // add AC consumption if provided
    def acPower = acMeter ? acMeter.currentValue("power").toString().toDouble() : 0.0
    log.trace "  → acPower=${acPower} W"
    def measured = power + acPower
    log.trace "  → adjusted export (AC off) = ${measured} W"

    def highW = thresholdHigh.toString().toDouble() * 1000
    def lowW  = thresholdLow.toString().toDouble()  * 1000
    log.trace "  → thresholds high=${highW} W, low=${lowW} W"

    def mode = thermostat.currentThermostatMode

    if (!state.lowered && measured > highW) {
        if (mode == "cool" || (mode == "auto" && applyToAuto)) {
            log.info  "handlePower(): measured ${measured} W > ${highW} W & mode=${mode} → lowering setpoint"
            lowerSetpoint()
        } else {
            log.debug "handlePower(): measured > high but mode=${mode}, applyToAuto=${applyToAuto}"
        }
    }
    else if (state.lowered && measured < lowW) {
        log.info  "handlePower(): measured ${measured} W < ${lowW} W → restoring setpoint"
        restoreSetpoint()
    }
    else {
        log.debug "handlePower(): no action (lowered=${state.lowered}, measured=${measured} W)"
    }
}

def onThermostatMode(evt) {
    log.debug "onThermostatMode(): mode→${evt.value}"
    if (state.monitoring && !state.lowered && evt.value == "cool") {
        log.trace "  → re-checking export on COOL mode entry"
        thresholdCheck()
    }
}

def onSetpointChange(evt) {
    def v = evt.value.toString().toDouble()
    // ignore our own programmatic changes
    if ((state.lowered && v == state.loweredSetpoint) ||
        (!state.lowered && state.originalSetpoint != null && v == state.originalSetpoint)) {
        log.trace "onSetpointChange(): programmatic change (${v}${unit()}), ignoring"
        return
    }
    log.warn  "onSetpointChange(): manual change detected (${v}${unit()}) → stopping monitoring"
    state.monitoring = false
    unschedule("thresholdCheck")
}

private lowerSetpoint() {
    def curr   = thermostat.currentValue("coolingSetpoint").toString().toDouble()
    state.originalSetpoint = curr
    def rawNew = curr - tempChange.toDouble()
    def newSet = useCelsius
        ? (Math.round(rawNew * 2) / 2.0)
        : rawNew.round(1)
    thermostat.setCoolingSetpoint(newSet)
    state.loweredSetpoint  = newSet
    state.lowered          = true
    log.info  "⬇ lowerSetpoint(): ${curr}${unit()} → ${newSet}${unit()}"
}

private restoreSetpoint() {
    try {
        def curr = thermostat.currentValue("coolingSetpoint").toString().toDouble()
        if (curr != state.loweredSetpoint) {
            log.warn "restoreSetpoint(): manual override (${curr}${unit()}), skipping"
        } else {
            thermostat.setCoolingSetpoint(state.originalSetpoint)
            log.info "⬆ restoreSetpoint(): back to ${state.originalSetpoint}${unit()}"
            state.lowered = false
        }
    } catch (e) {
        log.error "restoreSetpoint(): error ${e}"
    }
}

def resetDaily(evt) {
    log.debug "resetDaily(): sunrise—reset state for new day"
    unsubscribe()
    unschedule()
    initialize()
}

private String unit() {
    useCelsius ? "°C" : "°F"
}
