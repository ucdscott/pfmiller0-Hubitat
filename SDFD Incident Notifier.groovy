/**
 *  SDFD Incident Notifier
 *
 *  Copyright 2020 Peter Miller
 *
 * 2021-04-11: Switched logging to event log. Cleaned up incident list and variable names.
 * 2021-02-07: Switched to asynhttpget. Added tracking of updated incidents
 */
definition(
	name: "SDFD Incident Notifier",
	namespace: "hyposphere.net",
	parent: "hyposphere.net:P's Utilities",
	author: "Peter Miller",
	description: "Retrieve SDFD incidents, and provide notification for local incidents.",
	iconUrl: "",
	iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/pfmiller0/Hubitat/main/SDFD%20Incident%20Notifier.groovy"
)

preferences {
	section() {
		input "isPaused", "bool", title: "Pause app", defaultValue: false
	}
	if (state.activeIncidents != null) {
		section("Active Incidents:") {
			if (state.failCount > 0 ) {
				paragraph "<p align='center' style='font-size:110%;'><b>Connection down!</b></p>"
			}
			if (state.activeIncidents != []) {
				paragraph '<table style="border:1px solid silver; border-collapse:collapse; width:100%; font-size:90%;">' + incidentsToStr(state.activeIncidents, "table") + "</table>"
			} else {
				paragraph "<p align='center'>No active incidents</p>"
			}
			paragraph "<p align='right' style='font-size:90%;'><a href='http://hubitat/installedapp/events/${app.id}'>Incident history</a></p>"
		}
	}
	section("Settings") {
		input "update_interval", "number", title: "Update frequency (mins)", defaultValue: 5
		input "notifyUnits", "string", title: "Notification unit"
		input "notifyDevice", "capability.notification", title: "Notification device", multiple: false, required: false
	}
	section("Debug") {
		input "debugMode", "bool", title: "Enable debug logging", defaultValue: false
	}
}

List<String> IGNORE_INC() { ["Medical", "Medical Alert Alarm", "Advised Incident", "RAP", "Logistics", "Facilities", "Duty Mechanic", "Carbon Monoxide Alarm", "Page", "Move Up", "STAND BACK HOLD", "CAD Test", "Drill", "Ringing Alarm", "Elevator Rescue", "Lock in/out", "DMS", "Special Service", "yGT General Transport"] }
List<String> REDUNDANT_TYPES() { ["Advised Incident (misc.)", "Alert 1", "Alert 2 Brn/Mont", "Alert 2 Still Alarm", "Fuel in Bilge", "Pump Truck", "Traffic Accidents", "Single Resource", "Single Engine Response", "Hazmat", "TwoEngines", "Medical Multi-casualty", "Vegetation NO Special Response", "MTZ - Vegetaton Inital Attack", "Structure Commercial", "Rescue", "Gaslamp", "Traffic Accident Freeway (NC)", "Nat Gas Leak BB", "Nat Gas SING ENG SDGE", "Vehicle vs. Structure"] }
//List<String> NO_NOTIFICATION_TYPES() { ["Vehicle fire freeway", "Ringing alarm highrise", "Traffic Accident FWY", "Extinguished fire"] }
List<String> AMBULANCE_UNITS() { ["M", "AM", "BLS"] }
	
void installed() {
	if (debugMode) log.debug "Installed with settings: ${settings}"

	initialize()
	incidentCheck()
}

void updated() {
	if (debugMode) log.debug "Updated with settings: ${settings}"

	unsubscribe()
	unschedule()
	initialize()
	incidentCheck()
}

void initialize() {
	if (! isPaused) {
		if (debugMode) {
			state.prevIncNum = "FS00000000"
			state.activeIncidents = []
		} else {
			state.prevIncNum = state.prevIncNum ?: "FS00000000"
			state.failCount = state.failCount ?: 0
		}

		schedule('0 */' + update_interval + ' * ? * *', 'incidentCheck')
	}
}

def uninstalled() {
	unsubscribe()
	unschedule()
}

void incidentCheck() {
	String url="https://webapps.sandiego.gov/SDFireDispatch/api/v1/Incidents?_=" + now()

    Map params = [
	    uri: url,
		requestContentType: "application/json",
		contentType: "application/json",
		timeout: 30,
		ignoreSSLIssues: true
	]
 
	if (debugMode) {
		log.debug "url: $url"
		log.debug "params: $params"
	}

	try {
		asynchttpGet('httpResponse', params, [data: null])
	} catch (e) {
		log.error "There was an error: $e"	
	}
}

void httpResponse(hubitat.scheduling.AsyncResponse resp, Map data) {
	List<Map> allIncidents = []
	List<Map> fsIncidents = []
	List<Map> otherIncidents = []
	List<Map> activeIncidents = []
	List<Map> resolvedIncidents = []
	List<Map> updatedActiveIncidents = []
	String newMaxIncNum = ""

	/***** Backoff on error *****/
	if (resp.getStatus() != 200 ) {	
        state.failCount++
		unschedule('incidentCheck')
		if (state.failCount <= 4 ) {
            log.error "HTTP error: " + resp.getStatus()
			runIn(update_interval * state.failCount * 60, 'incidentCheck')
		} else if (state.failCount == 5 ) {
            log.error "HTTP error: " + resp.getStatus() + " (muting errors)"
			runIn(update_interval * state.failCount * 60, 'incidentCheck')
            notifyDevice.deviceNotification "SDFD notifier is down"
		} else {
			runIn(update_interval * 6 * 60, 'incidentCheck')
		}
		return
	} else {
		if (state.failCount > 0 ) {
			if (state.failCount >= 5 ) {
				log.info "HTTP error resolved ($state.failCount)"
				notifyDevice.deviceNotification "SDFD notifier is back up"
			}
			state.failCount = 0
			unschedule('incidentCheck')
            schedule('0 */' + update_interval + ' * ? * *', 'incidentCheck')
		}
	}

	// reorganize incident data -> remove ignored types -> remove medical incidents
	allIncidents = filterMedIncidents(filterIncidentType(cleanupList(resp.getJson()), IGNORE_INC()), AMBULANCE_UNITS())
	// Check incidents length, so we can filter rare incidents with no incidentnumber
	fsIncidents = allIncidents.findAll { it.IncidentNumber.length() > 4 && it.IncidentNumber.substring(0, 2) == "FS" }
	otherIncidents = allIncidents.findAll { it.IncidentNumber.length() > 4 && it.IncidentNumber.substring(0, 2) != "FS" }
	activeIncidents = state.activeIncidents ?: []
	
	// Get and log resolved incidents
	resolvedIncidents = getResolvedIncidents(allIncidents, activeIncidents)
	if (resolvedIncidents) logIncidents(resolvedIncidents, "RESOLVED")
	
	// Get and log updated incidents
	//activeIncidents.minus(resolvedIncidents) {it.IncidentNumber}
	activeIncidents = removeResolvedIncidents(allIncidents, activeIncidents)
	updatedActiveIncidents = getUpdatedActiveIncidents(allIncidents, activeIncidents)
	// Update active incidents with new data
	updatedActiveIncidents.each { cur ->
		activeIncidents[activeIncidents.findIndexOf { it.IncidentNumber == cur.IncidentNumber }].putAll(cur)
	}
	if (updatedActiveIncidents) logIncidents(updatedActiveIncidents, "UPDATED")
	
	// Get and log new incidents
	fsIncidents = newIncidents(fsIncidents)
	
	if (fsIncidents) {
		newMaxIncNum = fsIncidents*.IncidentNumber.max()
		activeIncidents.addAll(fsIncidents)
		
		logIncidents(fsIncidents + otherIncidents, "NEW")
		
		if (newMaxIncNum > state.prevIncNum) {
			state.prevIncNum = newMaxIncNum
		}
		
		fsIncidents = localIncidents(notifyUnits, fsIncidents)
		
		if (fsIncidents && notifyDevice) notifyDevice.deviceNotification incidentsToStr(fsIncidents, "min")	
	}
	
	state.activeIncidents = activeIncidents
}

List<Map> filterIncidentType(List<Map> incidents, List<String> types) {	
	return incidents.findAll { inc -> types.every {type -> type != inc.CallType} }
}

List<Map> filterMedIncidents(List<Map> incidents, List<String> medUnits) {	
	String unit_regexp = ""
	
	AMBULANCE_UNITS().every { if (unit_regexp) unit_regexp += "|"; unit_regexp +=  '(^' + it + '[0-9]+$)'}
	
	return incidents.findAll { inc -> !inc.Units.every {it =~ unit_regexp } }
}

boolean unitCalled(Map<String, List> incident, String unit) {
	return incident.Units.any { it == unit }
}

List<Map> localIncidents(String localUnit, List<Map> incidents) {
	return incidents.findAll { unitCalled(it, localUnit) }
}

List<Map> newIncidents(List<Map> incidents) {	
	return incidents.findAll { it.IncidentNumber.substring(2) > state.prevIncNum.substring(2) }
}

List<Map> cleanupList(List<Map> incidents) {
	List<Map> cleanInc = []
	
	incidents.each { inc ->
		cleanInc << [IncidentNumber: inc.MasterIncidentNumber, ResponseDate: inc.ResponseDate, CallType: inc.CallType, IncidentTypeName: inc.IncidentTypeName, Address: inc.Address, CrossStreet: inc.CrossStreet, Units: inc.Units*.Code.sort()]
	}

	
	return cleanInc
}

List<Map> getUpdatedActiveIncidents(List<Map> allIncidents, List<Map> activeIncidents) {
	List<Map> updatedInc = []
	Map prev = null
	
	allIncidents.each { cur ->
		prev = activeIncidents.find { it.IncidentNumber == cur.IncidentNumber }
		if (prev && (cur.CallType != prev.CallType || cur.Units != prev.Units)) {
			//updatedInc << [IncidentNumber: cur.MasterIncidentNumber, ResponseDate: cur.ResponseDate, CallType: cur.CallType, IncidentTypeName: cur.IncidentTypeName, Address: cur.Address, CrossStreet: cur.CrossStreet, Units: cur.Units]
			updatedInc << cur
		}
	}
	//log.debug "Updated list: " + updatedInc
	return updatedInc
}

List<Map> removeResolvedIncidents(List<Map> allIncidents, List<Map> activeIncidents) {
	return activeIncidents.findAll { inc ->
		allIncidents.any { it.IncidentNumber == inc.IncidentNumber }
	}
}

List<Map> getResolvedIncidents(List<Map> allIncidents, List<Map> activeIncidents) {
	return activeIncidents.findAll { inc ->
		allIncidents.every { it.IncidentNumber != inc.IncidentNumber }
	}
}

void logIncidents(List<Map> incidents, String LogType) {
	List<String> listIgnoreTypes = REDUNDANT_TYPES()

	String IncidentType = ""
	String CrossStreet = ""
	String incDesc = ""
	String incTime = ""
	
	incidents.each { inc ->
		IncidentType = inc.CallType == inc.IncidentTypeName || listIgnoreTypes.any { it == inc.IncidentTypeName } ? "" : " [$inc.IncidentTypeName]"
		CrossStreet = inc.CrossStreet ? " | $inc.CrossStreet" : ""
		if (LogType == "NEW") {
			java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("HH:mm")
	
			// Decimal seconds in "2022-07-22T11:59:20.68-07:00" causes errors, so strip that part out
			incTime = "REC: " + df.format(toDateTime(inc.ResponseDate.replaceAll('"\\.[0-9]*-', '-')))
		
			incDesc = "${inc.Address}${CrossStreet}:\n"
			inc.Units.each {
				incDesc = incDesc + " $it"
			}
		} else if (LogType == "UPDATED") {
			incTime = "UPDATED"

			incDesc = "${inc.Address}${CrossStreet}:\n"
			inc.Units.each {
				incDesc = incDesc + " $it"
			}
		} else if (LogType == "RESOLVED") {
			Integer incMins
			String resTime

			IncidentType = inc.CallType == inc.IncidentTypeName || listIgnoreTypes.any { it == inc.IncidentTypeName } ? "" : " [$inc.IncidentTypeName]"
			CrossStreet = inc.CrossStreet ? " | $inc.CrossStreet" : ""
		
			incMins = getIncidentMinutes(inc.ResponseDate)
			// round incident time down to nearest update_interval
			incMins = incMins - (incMins % update_interval)
			
			// Don't log short incidents
			if (incMins <= 20 || incMins <= update_interval*2) return
			resTime = sprintf('%d:%02d',(Integer) Math.floor(incMins / 60), incMins % 60)
			
			incTime = "RESOLVED"
		
			incDesc = "${inc.Address}${CrossStreet}:\nIncident time ${resTime}"
		}
		
		sendEvent(name: "${inc.CallType}${IncidentType}", value: "$inc.IncidentNumber ($incTime)", descriptionText: incDesc) 
	}
}

String incidentToStr(Map<String, List> inc, String format) {
    List<String> listIgnoreTypes = REDUNDANT_TYPES()
	String out = ""
	String CrossStreet = inc.CrossStreet ? "|$inc.CrossStreet" : ""
	String IncidentType = inc.CallType == inc.IncidentTypeName || listIgnoreTypes.any { it == inc.IncidentTypeName } ? "" : "[$inc.IncidentTypeName]"
	String incNum = debugMode ? " ($inc.IncidentNumber)" : ""
	
	if (format == "full") {
		java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("HH:mm:ss");
		out = df.format(toDateTime(inc.ResponseDate)) + incNum + " $inc.CallType $IncidentType $inc.Address$CrossStreet:"
	} else if (format == "table") {
		Integer incMins
		String incTime
		
		incMins = getIncidentMinutes(inc.ResponseDate)
		incTime = sprintf('%d:%02d',(Integer) Math.floor(incMins / 60) ,incMins % 60)
		
		String td = '<td style="border:1px solid silver;">'
		String tdc = '</td>'
		//java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("HH:mm:ss");
		//out = td + df.format(toDateTime(inc.ResponseDate)) + " (" + incTime + ")"+ tdc + td + " $inc.CallType $IncidentType" + tdc + td + "$inc.Address$CrossStreet" + tdc + td
		out = td + incTime + tdc + td + " $inc.CallType $IncidentType" + tdc + td + "$inc.Address$CrossStreet" + tdc + td
	} else if (format == "min") {
		out = "$inc.CallType - $inc.Address$CrossStreet:"
	} else if (format == "updated") {
		out = "UPDATED" + incNum + " $inc.CallType - $inc.Address$CrossStreet:"
	}
		
	inc.Units.each {
		out = out + " $it"
	}
	
	if (format == "table") {
		return "<tr>" + out + "</tr>"
	} else {
		return out
	}
}

String incidentsToStr(List<Map> incidents, String format) {
	String out = ""
	
	incidents.each {
		out = out + incidentToStr(it, format) + "\n"
	}
	
	return out
}

Integer getIncidentMinutes(String responseDate) {
	// Decimal seconds in "2022-07-22T11:59:20.68-07:00" causes errors, so strip that part out
	return  ((now() - toDateTime(responseDate.replaceAll('"\\.[0-9]*-', '-')).getTime()) / (1000 * 60))
}
