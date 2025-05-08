/**
 *  Solar-Driven AC Setback (with manual-override, sunset restore & power-invert)
 *
 *  Author:  John Abraham
 *  Date:    2025-04-28
 *
 *  Continuously adjust thermostat cooling setpoint by Δ° whenever
 *  solar export crosses E/E2 kW, from T hours before sunset through sunset.
 *  Stops monitoring if you manually change the setpoint.  
 *  Restores at sunset if still lowered (unless manually overridden).  
 *  Supports Celsius/°F & optional power-value inversion.
 *  Now you can also choose whether it applies in AUTO mode.
 *
 *  All props to ChatGPT.  This code has not yet been tested. 
 */

definition(
    name: "Solar-Driven AC Setback",
    namespace: "jeabraham",
    author: "John Abraham",
    description: "Continuously adjust setpoint by Temp° when solar export > E / < E2, from sunset-T until sunset, with manual-override stop, sunset-restore, C/F, invert-power & optional auto-mode support.",
    category: "Green Energy",
    iconUrl:   "",
    iconX2Url: ""
)

preferences {
    section("About Solar-Driven AC Setback") {
        paragraph """
This app monitors your solar power or net power export starting T hours before sunset.  
• If power > High threshold, it lowers your cooling setpoint by Δ°.  
• If pwer < Low threshold or at sunset, it restores the setpoint (unless you manually changed it).  
Supports Celsius/°F, optional power inversion (since export is often negative), and can apply in AUTO mode.
"""
    }
    section("Select your devices") {
        input "solarMeter",    "capability.powerMeter",
            title: "Power Meter (W) (solar quantity or net grid consumption/export)", required: true
        input "thermostat",    "capability.thermostat",
            title: "Thermostat (must support coolingSetpoint)", required: true
    }
    section("Thresholds & offsets") {
        input "offsetHours",    "decimal",
            title: "T hours before sunset",        defaultValue: 2.0, required: true
        input "thresholdHigh", "decimal",
            title: "High threshold E (kW)", defaultValue: 1.0, required: true
        input "thresholdLow",  "decimal",
            title: "Low threshold E2 (kW)", defaultValue: 0.5, required: true
        input "tempChange",    "decimal",
            title: "Δ setpoint (°)",               defaultValue: 2.0, required: true
    }
    section("Options") {
        input "useCelsius",    "bool",
            title: "Use Celsius (uncheck for °F)", defaultValue: true
        input "invertPower",   "bool",
            title: "Invert power sign (usually required if you're monitoring net power instead of solar production)", defaultValue: false
        input "applyToAuto",   "bool",
            title: "Apply setback in AUTO mode (unchecked: only in COOL)", defaultValue: false
        input "checkInterval","number",
            title: "Re-check every TD minutes until sunset", defaultValue: 15, required: true
    }
}

def installed() { 
    validateSettings()
    initialize() 
}

def updated()  {
    unsubscribe()
    unschedule()
    validateSettings()
    initialize()
}

private void validateSettings() {
    def high = thresholdHigh.toDouble()
    def low  = thresholdLow.toDouble()
    if (high - low < 0.5) {
        log.error "${app.label}: Validation failed—Low threshold (E2) must be at least 0.5 kW below High threshold (E)."
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

    subscribe(solarMeter,      "power",           onPowerEvent)
    subscribe(thermostat,      "thermostatMode",  onThermostatMode)
    subscribe(thermostat,      "coolingSetpoint", onSetpointChange)
    subscribe(location,        "sunriseTime",     resetDaily)

    scheduleDailyJobs()
}

private scheduleDailyJobs() {
    def sun    = getSunriseAndSunset()
    def sunset = sun.sunset
    state.sunsetTime = sunset.time
    log.debug "Scheduling startMonitoring and stopMonitoring around sunset at ${sunset}"

    // 1) start monitoring at sunset − offsetHours
    def startAt  = new Date(sunset.time - (offsetHours.toDouble() * 3600*1000).toLong())
    if (startAt.time <= now()) {
        log.warn "Sunset−T is already past; starting monitoring immediately"
        startMonitoring()
    } else {
        schedule(startAt, "startMonitoring")
        log.debug "Scheduled startMonitoring() at ${startAt}"
    }

    // 2) stop monitoring (and restore if needed) at actual sunset
    schedule(sunset, "stopMonitoring")
    log.debug "Scheduled stopMonitoring() at ${sunset}"
}

def startMonitoring() {
    state.monitoring = true
    log.info  "▶︎ startMonitoring(): ON from ${new Date()} until sunset (${new Date(state.sunsetTime)})"
    thresholdCheck()  // initial check + schedule repeats
}

def stopMonitoring() {
    log.info  "■ stopMonitoring(): OFF at sunset (${new Date()})"
    state.monitoring = false
    unschedule("thresholdCheck")

    if (state.lowered) {
        def curr = thermostat.currentValue("coolingSetpoint").toString().toDouble()
        if (curr == state.loweredSetpoint) {
            log.info "● Sunset restore: restoring original setpoint of ${state.originalSetpoint}${unit()}"
            restoreSetpoint()
        } else {
            log.warn "⚠ Sunset restore skipped: detected manual override (${curr}${unit()})"
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
        log.trace "thresholdCheck(): skipped because monitoring=false"
        return
    }
    def rawPower = solarMeter.currentValue("power").toString().toDouble()
    handlePower(rawPower)

    if (now() < state.sunsetTime) {
        log.debug "thresholdCheck(): scheduling next in ${checkInterval} min"
        runIn(checkInterval.toInteger() * 60, "thresholdCheck")
    } else {
        log.debug "thresholdCheck(): past sunset, no more checks"
    }
}

private handlePower(rawPower) {
    log.trace "handlePower(): rawPower=${rawPower} W"
    def power = invertPower ? -rawPower : rawPower
    if (invertPower) {
        log.debug "handlePower(): inverted power=${power} W"
    }

    double highW = thresholdHigh.toString().toDouble() * 1000
    double lowW  = thresholdLow.toString().toDouble()  * 1000
    log.trace "handlePower(): comparing ${power} W to high>${highW} and low<${lowW}"

    def mode = thermostat.currentThermostatMode

    // If not yet lowered & export > high threshold:
    if (!state.lowered && power > highW) {
        if (mode == "cool") {
            log.info  "handlePower(): mode=COOL & export ${power} W > ${highW} W → lowering setpoint"
            lowerSetpoint()
        }
        else if (mode == "auto" && applyToAuto) {
            log.info  "handlePower(): mode=AUTO & applyToAuto → lowering setpoint"
            lowerSetpoint()
        }
        else {
            log.debug "handlePower(): export > high, but mode=${mode}, applyToAuto=${applyToAuto}"
        }
    }
    // If already lowered & export < low threshold → restore
    else if (state.lowered && power < lowW) {
        log.info  "handlePower(): export ${power} W < ${lowW} W → restoring setpoint"
        restoreSetpoint()
    }
    else {
        log.debug "handlePower(): no action. lowered=${state.lowered}, mode=${mode}"
    }
}

def onThermostatMode(evt) {
    log.debug "onThermostatMode(): mode changed to ${evt.value}"
    if (state.monitoring && !state.lowered && evt.value == "cool") {
        log.trace "onThermostatMode(): mode→COOL, re-checking export"
        thresholdCheck()
    }
}

def onSetpointChange(evt) {
    def v = evt.value.toString().toDouble()
    // ignore our own lower/restore events
    if (state.lowered && v == state.loweredSetpoint ||
        !state.lowered && state.originalSetpoint != null && v == state.originalSetpoint) {
        log.trace "onSetpointChange(): programmatic change to ${v}${unit()}, ignoring"
        return
    }
    log.warn  "onSetpointChange(): detected manual setpoint change to ${evt.value}${unit()}"
    if (state.monitoring) {
        log.warn  "onSetpointChange(): stopping monitoring for today due to manual override"
        state.monitoring = false
        unschedule("thresholdCheck")
    }
}

private lowerSetpoint() {
    def curr   = thermostat.currentValue("coolingSetpoint").toString().toDouble()
    state.originalSetpoint = curr
    def rawNew = curr - tempChange.toDouble()
    // if Celsius, round to nearest 0.5; else one-decimal
    def newSet = useCelsius
        ? (Math.round(rawNew * 2) / 2.0)
        : rawNew.round(1)
    thermostat.setCoolingSetpoint(newSet)
    state.loweredSetpoint  = newSet
    state.lowered          = true
    log.info  "⬇ lowerSetpoint(): from ${curr}${unit()} → ${newSet}${unit()}"
}

private restoreSetpoint() {
    try {
        def curr = thermostat.currentValue("coolingSetpoint").toString().toDouble()
        if (curr != state.loweredSetpoint) {
            log.warn "restoreSetpoint(): manual override detected (${curr}${unit()}), skip restore"
        } else {
            thermostat.setCoolingSetpoint(state.originalSetpoint)
            log.info "⬆ restoreSetpoint(): back to ${state.originalSetpoint}${unit()}"
            state.lowered = false
        }
    } catch (e) {
        log.error "restoreSetpoint(): unexpected error: ${e}"
    }
}

def resetDaily(evt) {
    log.debug "resetDaily(): sunrise—clearing state for new day"
    unsubscribe()
    unschedule()
    initialize()
}

private String unit() {
    useCelsius ? "°C" : "°F"
}
